package com.callguard.app.screening

import android.telecom.Call
import android.telecom.CallScreeningService.CallResponse
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import java.util.concurrent.*
import com.callguard.app.policy.*
import com.callguard.app.risk.RiskSnapshot
import org.mockito.Mockito
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallGuardScreeningServiceTest {
    private val services = mutableListOf<CapturingService>()
    private fun service() = CapturingService().also { services += it }
    @After fun cleanup() { services.forEach { it.timer.shutdownNow() } }
    class CapturingService : CallGuardScreeningService() {
        val responses = mutableListOf<Pair<Call.Details, CallResponse>>()
        val evidence = mutableListOf<ScreeningEvidence>()
        val timer = Executors.newSingleThreadScheduledExecutor()
        override fun coordinator() = ScreeningCoordinator(object : ScreeningDataSource {
            override fun load(request: ScreeningRequest, publish: (ScreeningState) -> Unit) =
                ScreeningState(PolicyInput(request.receivedWallTimeMs, snapshot=RiskSnapshot()))
            override fun persist(request: ScreeningRequest, state: ScreeningState, result: PolicyResult) = Unit
        }, Executor { it.run() }, timer, Executor { it.run() }, MonotonicClock { android.os.SystemClock.elapsedRealtime() })
        var failWrite = false
        var failResponse = false
        override fun persistEvidence(record: ScreeningEvidence) {
            check(responses.size == 1) { "Evidence must follow response" }
            if (failWrite) throw java.io.IOException("simulated disk full")
            evidence.add(record)
        }
        override fun submitResponse(details: Call.Details, response: CallResponse) {
            if (failResponse) throw IllegalStateException("simulated platform failure")
            responses.add(details to response)
        }
    }

    @Test fun evidenceIsWrittenOnlyAfterAllowSubmission() {
        val service = service()
        val call = details(Call.Details.DIRECTION_INCOMING)
        service.onScreenCall(call)
        val record = service.evidence.single()
        assertTrue(record.respondedAtMs >= record.receivedAtMs)
        assertTrue(record.respondedLog.contains("decision=ALLOW"))
        Mockito.verify(call).handle
    }

    @Test fun diskFailureDoesNotRetryOrChangeAllow() {
        val service = service().apply { failWrite = true }
        service.onScreenCall(details(Call.Details.DIRECTION_INCOMING))
        assertEquals(1, service.responses.size)
        assertFalse(service.responses.single().second.disallowCall)
    }

    @Test fun responseFailureCannotProduceSubmittedEvidence() {
        val service = service().apply { failResponse = true }
        service.onScreenCall(details(Call.Details.DIRECTION_INCOMING))
        assertTrue(service.responses.isEmpty())
        assertTrue(service.evidence.isEmpty())
    }

    private fun details(direction: Int): Call.Details {
        val details = Mockito.mock(Call.Details::class.java)
        Mockito.`when`(details.callDirection).thenReturn(direction)
        return details
    }

    @Test fun incomingCallWithoutNumberIsAllowedExactlyOnce() {
        // Callback-only test: no lifecycle resources are acquired.
        val service = service()
        val details = details(Call.Details.DIRECTION_INCOMING)
        service.onScreenCall(details)
        assertEquals(1, service.responses.size)
        assertSame(details, service.responses.single().first)
        val response = service.responses.single().second
        assertFalse(response.disallowCall)
        assertFalse(response.rejectCall)
        assertFalse(response.silenceCall)
        assertFalse(response.skipCallLog)
        assertFalse(response.skipNotification)
    }

    @Test fun outgoingCallDoesNotSubmitIncomingScreeningResponse() {
        // Callback-only test: no lifecycle resources are acquired.
        val service = service()
        service.onScreenCall(details(Call.Details.DIRECTION_OUTGOING))
        assertTrue(service.responses.isEmpty())
        assertTrue(service.evidence.isEmpty())
    }
}
