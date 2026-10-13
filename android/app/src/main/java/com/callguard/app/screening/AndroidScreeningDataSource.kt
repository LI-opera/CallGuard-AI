package com.callguard.app.screening

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import com.callguard.app.caller.*
import com.callguard.app.data.AppDatabase
import com.callguard.app.data.entity.CallRecordEntity
import com.callguard.app.policy.*
import com.callguard.app.risk.*
import org.json.JSONArray
import com.callguard.app.platform.AndroidWindowClock
import com.callguard.app.platform.WindowClock

/** All methods run off the callback thread. No database/Keystore initialization in constructor. */
class AndroidScreeningDataSource(
    context: Context,
    private val databaseName: String = "callguard.db",
    private val tokenizerFactory: () -> NumberTokenizer = { AndroidNumberTokenizer.create() },
    private val windowClock: WindowClock = AndroidWindowClock(context),
    private val databaseFactory: (() -> AppDatabase)? = null,
) : ScreeningDataSource {
    private val app = context.applicationContext
    private val database by lazy { databaseFactory?.invoke() ?: AppDatabase.open(app, databaseName) }
    private val normalizer by lazy { PhoneNumberNormalizer(tokenizerFactory()) }

    override fun load(request: ScreeningRequest, publish: (ScreeningState) -> Unit): ScreeningState {
        val region = try {
            val phone = app.getSystemService(TelephonyManager::class.java)
            phone?.networkCountryIso?.takeIf { it.isNotBlank() } ?: phone?.simCountryIso
        } catch (_: Exception) { null }
        val number = normalizer.normalize(request.rawHandle, region, request.presentationAllowed)
        val now = windowClock.now()
        var state = ScreeningState(PolicyInput(request.receivedWallTimeMs), number, bootCount = now.bootCount)
        publish(state)
        if (number.status != NormalizationStatus.NORMALIZED) return state
        val isContact = isContact(number.e164!!)
        state = state.copy(input = state.input.copy(isContact = isContact))
        publish(state)
        if (isContact) return state
        val rules = database.rules().activeForClock(number.numberToken, now.elapsedMs, now.bootCount)
        val temporary = rules.filter { it.type == "TEMP_ALLOW" }.maxByOrNull { it.expiresElapsedMs ?: 0 }
        state = state.copy(input = state.input.copy(
            allowRule = rules.any { it.type == "ALLOW" }, blockRule = rules.any { it.type == "BLOCK" },
            temporaryAllow = temporary?.let { TemporaryAllow(request.receivedWallTimeMs, Math.addExact(request.receivedWallTimeMs, it.expiresElapsedMs!! - now.elapsedMs)) },
        ))
        publish(state) // Preserve verified rules even if the following profile/history query fails.
        val previous = now.bootCount?.let { database.callRecords().previousWindow(number.numberToken, it) }
        state = state.copy(retryWindow = RetryWindow.next(previous, request.deadline.receivedAtMs, now.bootCount))
        publish(state)
        if (state.input.allowRule || state.input.blockRule) return state
        val profile = database.callerProfiles().get(number.numberToken)
        state = state.copy(input = state.input.copy(userConfirmedScam = profile?.userLabel == "SCAM"))
        publish(state)
        if (state.input.userConfirmedScam || temporary != null) return state
        val history = database.callRecords().recent(number.numberToken,
            (request.receivedWallTimeMs - RiskFeatureExtractor.DAY_MS).coerceAtLeast(0), request.receivedWallTimeMs)
        // Repetition uses a persisted same-boot fixed window. Answer/reject/callback collectors remain unimplemented.
        val snapshot = RiskSnapshot(previousCalls = history.map { HistoricalIncoming(it.id, it.receivedAt) },
            previousIncomingAgeMs = state.retryWindow?.takeIf { it.repeatCount > 0 }?.let { request.deadline.receivedAtMs - it.startedAtMs },
            answeredCount = profile?.answeredCount ?: 0, rejectedCount = profile?.rejectedCount ?: 0,
            userLabel = when (profile?.userLabel) { "MARKETING" -> UserLabel.MARKETING; "FAMILY" -> UserLabel.FAMILY; "HARASSMENT" -> UserLabel.HARASSMENT; else -> UserLabel.NONE })
        return state.copy(input = state.input.copy(snapshot = snapshot)).also(publish)
    }

    private fun isContact(number: String): Boolean {
        // Platform contract: without READ_CONTACTS only non-contact calls reach CallScreeningService.
        if (app.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return false
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return app.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)
            ?.use { it.moveToFirst() } ?: throw IllegalStateException("contacts unavailable")
    }

    override fun persist(request: ScreeningRequest, state: ScreeningState, result: PolicyResult) {
        val score = result.riskScore
        database.callRecords().recordIncomingCallAndUpdateProfile(CallRecordEntity(
            id = request.eventId, numberToken = state.number?.numberToken ?: "u1:${request.eventId}",
            receivedAt = request.receivedWallTimeMs,
            repeatCount = state.retryWindow?.repeatCount ?: 0,
            receivedElapsedMs = request.deadline.receivedAtMs, bootCount = state.bootCount,
            retryStartedAtMs = state.retryWindow?.startedAtMs, retryWindowUntil = state.retryWindow?.retryWindowUntil,
            riskScore = score,
            riskLevel = when { score == null -> "NOT_EVALUATED"; score < 40 -> "LOW"; score < 70 -> "MEDIUM"; else -> "HIGH" },
            decision = result.decision.name, matchedRules = JSONArray(result.matchedRules).toString(),
            fallbackUsed = result.fallbackUsed, policyVersion = result.policyVersion,
            normalizationStatus = state.number?.status?.name ?: "UNKNOWN",
            latencyMs = request.respondedAtMs?.let { it - request.deadline.receivedAtMs },
        ))
    }
}
