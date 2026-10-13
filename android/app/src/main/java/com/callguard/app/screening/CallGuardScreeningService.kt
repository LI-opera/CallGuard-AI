package com.callguard.app.screening

import android.os.Build
import android.os.SystemClock
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.TelecomManager
import android.util.Log
import java.util.UUID
import java.io.File
import com.callguard.app.BuildConfig

open class CallGuardScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        val receivedAt = SystemClock.elapsedRealtime()
        val receivedWallTime = System.currentTimeMillis()
        val eventId = UUID.randomUUID().toString()
        val receivedLog = "received eventId=$eventId api=${Build.VERSION.SDK_INT} receivedAtMs=$receivedAt direction=${details.callDirection}"
        Log.i(TAG, receivedLog)
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) {
            Log.i(TAG, "ignored eventId=$eventId reason=not_incoming"); return
        }
        val request = ScreeningRequest(eventId, details.handle?.toString(),
            details.handlePresentation == TelecomManager.PRESENTATION_ALLOWED,
            receivedWallTime, ScreeningDeadline(receivedAt))
        var submittedEvidence: ScreeningEvidence? = null
        coordinator().screen(request, respond = { result ->
            submitResponse(details, CallResponseMapper.map(result.decision))
            val respondedAt = SystemClock.elapsedRealtime()
            request.respondedAtMs = respondedAt
            val respondedLog = "responded eventId=$eventId decision=${result.decision} respondedAtMs=$respondedAt latencyMs=${respondedAt - receivedAt}"
            Log.i(TAG, respondedLog)
            submittedEvidence = ScreeningEvidence(eventId, receivedAt, respondedAt, receivedWallTime,
                android.os.Process.myPid(), Build.VERSION.SDK_INT, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
                receivedLog, respondedLog, result.decision.name, result.fallbackUsed, result.policyVersion, result.matchedRules, result.riskScore)
        }, afterResponse = {
            if (BuildConfig.PERSIST_SCREENING_EVIDENCE) {
                submittedEvidence?.let { persistEvidence(it); Log.i(TAG, "evidence_saved eventId=$eventId") }
            }
        })
    }

    protected open fun coordinator(): ScreeningCoordinator = ScreeningRuntime.coordinator(applicationContext)
    protected open fun submitResponse(details: Call.Details, response: CallResponse) { respondToCall(details, response) }
    protected open fun persistEvidence(evidence: ScreeningEvidence) {
        DiagnosticEvidenceStore.write(File(noBackupFilesDir, DiagnosticEvidenceStore.DIRECTORY), evidence)
    }
    companion object { private const val TAG = "CallGuard" }
}
