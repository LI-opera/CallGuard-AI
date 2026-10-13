package com.callguard.app.screening

import com.callguard.app.caller.NormalizedNumber
import com.callguard.app.policy.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// Raw input is transient and never included in logs, database entities or evidence.
class ScreeningRequest(val eventId: String, val rawHandle: String?, val presentationAllowed: Boolean,
    val receivedWallTimeMs: Long, val deadline: ScreeningDeadline) {
    @Volatile var respondedAtMs: Long? = null
        internal set
}

data class ScreeningState(val input: PolicyInput, val number: NormalizedNumber? = null,
    val retryWindow: RetryWindow? = null, val bootCount: Int? = null)

interface ScreeningDataSource {
    fun load(request: ScreeningRequest, publish: (ScreeningState) -> Unit): ScreeningState
    fun persist(request: ScreeningRequest, state: ScreeningState, result: PolicyResult)
}

/** Deadline executor is independent of potentially locked provider/Room work. Exactly one submission attempt. */
class ScreeningCoordinator(
    private val source: ScreeningDataSource,
    private val workers: Executor,
    private val timer: ScheduledExecutorService,
    private val writes: Executor,
    private val clock: MonotonicClock,
    private val cloud: com.callguard.app.cloud.CloudRiskGateway? = null,
    private val report: (String, String) -> Unit = { _, _ -> },
) {
    private val engine = PolicyEngine()

    fun screen(request: ScreeningRequest, respond: (PolicyResult) -> Unit,
        afterResponse: (PolicyResult) -> Unit) {
        val latest = AtomicReference(ScreeningState(PolicyInput(request.receivedWallTimeMs)))
        val localDecision = AtomicReference<PolicyResult?>(null)
        val submitted = AtomicBoolean(false)
        val timeout = AtomicReference<ScheduledFuture<*>?>()

        fun fallback(state: ScreeningState, reason: String): PolicyResult {
            val local = engine.decide(state.input.copy(snapshot = null))
            return local.copy(fallbackUsed = true, matchedRules = local.matchedRules + reason)
        }
        fun finish(state: ScreeningState, proposed: PolicyResult) {
            if (!submitted.compareAndSet(false, true)) return
            timeout.get()?.cancel(false)
            try {
                respond(proposed)
                if (request.respondedAtMs == null) request.respondedAtMs = clock.nowMs()
            } catch (_: Exception) {
                report(request.eventId, "response_failed")
                return // An uncertain platform submission must not be retried or logged as success.
            }
            try {
                writes.execute {
                    try { afterResponse(proposed) } catch (_: Exception) { report(request.eventId, "evidence_write_failed") }
                    try { source.persist(request, state, proposed); report(request.eventId, "record_saved") }
                    catch (_: Exception) { report(request.eventId, "record_write_failed") }
                }
            } catch (_: RejectedExecutionException) { report(request.eventId, "post_response_queue_full") }
        }
        try {
            timeout.set(timer.schedule({
                val state = latest.get()
                finish(state, localDecision.get()?.copy(fallbackUsed=true, matchedRules=localDecision.get()!!.matchedRules+"CLOUD_TIMEOUT") ?: fallback(state, "LOCAL_DEADLINE"))
            }, request.deadline.remainingMs(clock.nowMs()), TimeUnit.MILLISECONDS))
            if (submitted.get()) timeout.get()?.cancel(false)
        } catch (_: RejectedExecutionException) {
            finish(latest.get(), fallback(latest.get(), "TIMER_UNAVAILABLE")); return
        }
        try {
            workers.execute {
                if (submitted.get()) return@execute
                try {
                    val loaded = source.load(request) { if (!submitted.get()) latest.set(it) }
                    if (submitted.get()) return@execute
                    val decision = engine.decide(loaded.input)
                    if (request.deadline.expired(clock.nowMs())) {
                        finish(loaded, fallback(loaded, "LOCAL_DEADLINE")); return@execute
                    }
                    latest.set(loaded)
                    if(cloud != null) localDecision.set(decision)
                    val proposed = if (cloud == null) decision else try {
                        cloud.refine(request,loaded,decision,request.deadline.remainingMs(clock.nowMs()))
                    } catch (_:Exception) {
                        decision.copy(fallbackUsed=true,matchedRules=decision.matchedRules+"CLOUD_UNAVAILABLE")
                    }
                    finish(loaded, if(request.deadline.expired(clock.nowMs()))
                        decision.copy(fallbackUsed=true,matchedRules=decision.matchedRules+"CLOUD_TIMEOUT") else proposed)
                } catch (_: Exception) {
                    val state = latest.get()
                    finish(state, fallback(state, "LOCAL_READ_FAILED"))
                }
            }
        } catch (_: RejectedExecutionException) {
            val state = latest.get(); finish(state, fallback(state, "LOCAL_QUEUE_FULL"))
        }
    }
}
