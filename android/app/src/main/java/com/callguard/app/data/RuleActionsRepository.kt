package com.callguard.app.data

import com.callguard.app.data.entity.RuleEntryEntity
import com.callguard.app.platform.WindowClock

/** Worker-thread operations, targets an existing event without exposing or re-entering a raw number. */
class RuleActionsRepository(private val database: AppDatabase, private val clock: WindowClock) {
    fun allowForFiveMinutes(eventId: String) = save(eventId, false)
    fun addToAllowList(eventId: String) = save(eventId, true)
    private fun save(eventId: String, permanent: Boolean) {
        val record = requireNotNull(database.callRecords().get(eventId))
        require(record.normalizationStatus == "NORMALIZED") { "Unknown numbers cannot share rules" }
        val now = clock.now()
        if (!permanent) requireNotNull(now.bootCount) { "Boot identity unavailable" }
        val type = if (permanent) "ALLOW" else "TEMP_ALLOW"
        database.rules().put(RuleEntryEntity(
            id = "user-$type-${record.numberToken}", numberToken = record.numberToken, type = type,
            createdAt = now.wallMs, expiresAt = if (permanent) null else Math.addExact(now.wallMs, 300_000),
            source = "user", createdElapsedMs = if (permanent) null else now.elapsedMs,
            expiresElapsedMs = if (permanent) null else Math.addExact(now.elapsedMs, 300_000),
            bootCount = if (permanent) null else now.bootCount,
        ))
    }
}
