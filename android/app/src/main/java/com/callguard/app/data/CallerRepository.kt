package com.callguard.app.data
import com.callguard.app.caller.NormalizedNumber
import com.callguard.app.data.entity.CallRecordEntity
/** Worker-thread API; deliberately not wired into onScreenCall until WP-04. */
class CallerRepository(private val database: AppDatabase) {
    fun recordIncomingCallAndUpdateProfile(id: String, number: NormalizedNumber, receivedAt: Long) {
        database.callRecords().recordIncomingCallAndUpdateProfile(CallRecordEntity(
            id, number.numberToken, receivedAt, normalizationStatus = number.status.name))
    }
}
