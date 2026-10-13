package com.callguard.app.screening

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/** Diagnostic data only: deliberately accepts no Call.Details or phone number. */
data class ScreeningEvidence(
    val eventId: String,
    val receivedAtMs: Long,
    val respondedAtMs: Long,
    val receivedWallTimeMs: Long,
    val pid: Int,
    val api: Int,
    val versionName: String,
    val versionCode: Int,
    val receivedLog: String,
    val respondedLog: String,
    val decision: String = "ALLOW",
    val fallbackUsed: Boolean = false,
    val policyVersion: String? = null,
    val matchedRules: List<String> = emptyList(),
    val riskScore: Int? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schemaVersion", if (policyVersion == null) 1 else 2).put("eventId", eventId)
        .put("receivedAtMs", receivedAtMs).put("respondedAtMs", respondedAtMs)
        .put("latencyMs", respondedAtMs - receivedAtMs)
        .put("receivedWallTimeMs", receivedWallTimeMs).put("pid", pid).put("api", api)
        .put("versionName", versionName).put("versionCode", versionCode)
        .put("direction", "INCOMING").put("decision", decision)
        .put("responseStatus", "respondToCall_returned")
        .put("receivedLog", receivedLog).put("respondedLog", respondedLog)
        .also { if (policyVersion != null) it.put("fallbackUsed", fallbackUsed).put("policyVersion", policyVersion)
            .put("matchedRules", org.json.JSONArray(matchedRules)).put("riskScore", riskScore ?: JSONObject.NULL) }
}

object DiagnosticEvidenceStore {
    const val DIRECTORY = "screening-evidence-v1"
    const val MAX_RECORDS = 100

    /** Called only AFTER response returns. Run on the process-owned post-response executor. */
    @Synchronized
    fun write(directory: File, evidence: ScreeningEvidence) {
        require(UUID.fromString(evidence.eventId).toString() == evidence.eventId)
        require(evidence.respondedAtMs >= evidence.receivedAtMs)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("directory unavailable")
        val files = directory.listFiles() ?: throw IOException("directory unreadable")
        // Do not prune evidence silently. Fail closed once the diagnostic batch is full.
        if (files.size >= MAX_RECORDS) throw IOException("evidence capacity reached")
        val target = File(directory, evidence.eventId + ".json")
        if (target.exists()) throw IOException("duplicate event")
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try {
            stream.write(evidence.toJson().toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }
}
