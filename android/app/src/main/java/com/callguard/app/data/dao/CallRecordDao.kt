package com.callguard.app.data.dao
import androidx.room.*
import com.callguard.app.data.entity.*
/** Single owner of cross-table mutations. All entry points delegate here, never duplicate transactions. */
@Dao abstract class CallRecordDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) protected abstract fun insertRecord(record: CallRecordEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) protected abstract fun insertProfile(profile: CallerProfileEntity): Long
    @Query("UPDATE caller_profiles SET firstSeenAt = MIN(firstSeenAt, :at), lastSeenAt = MAX(lastSeenAt, :at), callCount = callCount + 1, lastDecision = CASE WHEN :at >= lastSeenAt THEN :decision ELSE lastDecision END, profileVersion = profileVersion + 1 WHERE numberToken = :token")
    protected abstract fun increment(token: String, at: Long, decision: String)
    @Query("SELECT * FROM call_records WHERE id = :id") abstract fun get(id: String): CallRecordEntity?
    @Query("SELECT * FROM call_records WHERE numberToken = :token AND receivedAt > :after AND receivedAt <= :now ORDER BY receivedAt DESC LIMIT 10")
    abstract fun recent(token: String, after: Long, now: Long): List<CallRecordEntity>
    @Query("SELECT * FROM call_records WHERE numberToken=:token AND bootCount=:boot AND retryWindowUntil IS NOT NULL ORDER BY receivedElapsedMs DESC LIMIT 1")
    abstract fun previousWindow(token: String, boot: Int): CallRecordEntity?
    @Query("SELECT * FROM call_records ORDER BY receivedAt DESC, id DESC LIMIT 5")
    abstract fun latest(): List<CallRecordEntity>
    @Query("SELECT * FROM call_records WHERE :beforeTime IS NULL OR receivedAt < :beforeTime OR (receivedAt = :beforeTime AND id < :beforeId) ORDER BY receivedAt DESC, id DESC LIMIT :limit")
    abstract fun page(beforeTime:Long?,beforeId:String,limit:Int):List<CallRecordEntity>
    @Query("SELECT COUNT(*) AS total, COALESCE(SUM(CASE WHEN decision='ALLOW' THEN 1 ELSE 0 END),0) AS allowed, COALESCE(SUM(CASE WHEN decision='SILENCE' THEN 1 ELSE 0 END),0) AS silenced, COALESCE(SUM(CASE WHEN decision='BLOCK' THEN 1 ELSE 0 END),0) AS blocked, COALESCE(SUM(CASE WHEN fallbackUsed=1 THEN 1 ELSE 0 END),0) AS fallback FROM call_records WHERE receivedAt>=:start AND receivedAt<:end")
    abstract fun summary(start:Long,end:Long):DaySummary
    @Query("SELECT COUNT(*) FROM call_records") abstract fun count(): Int
    @Transaction open fun recordIncomingCallAndUpdateProfile(record: CallRecordEntity) {
        require(record.numberToken.isNotBlank() && record.id.isNotBlank())
        require(record.receivedAt >= 0 && record.repeatCount >= 0)
        require(record.normalizationStatus in setOf("NORMALIZED", "UNKNOWN"))
        val inserted = insertProfile(CallerProfileEntity(record.numberToken, record.receivedAt, record.receivedAt,
            callCount = 1, lastDecision = record.decision, normalizationStatus = record.normalizationStatus))
        if (inserted == -1L) increment(record.numberToken, record.receivedAt, record.decision)
        // ABORT on duplicate event rolls back the earlier profile insert/update too.
        insertRecord(record)
    }
}

data class DaySummary(val total:Int=0,val allowed:Int=0,val silenced:Int=0,val blocked:Int=0,val fallback:Int=0)
