package com.callguard.app.data.dao
import androidx.room.*
import com.callguard.app.data.entity.RuleEntryEntity
@Dao abstract class RuleEntryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) protected abstract fun insert(entry: RuleEntryEntity)
    @Query("DELETE FROM rule_entries WHERE type = 'TEMP_ALLOW' AND expiresAt IS NOT NULL AND expiresAt <= :now")
    protected abstract fun deleteExpired(now: Long): Int
    @Query("SELECT * FROM rule_entries WHERE numberToken = :token AND enabled = 1 AND createdAt <= :now AND (type IN ('ALLOW','BLOCK') OR (type = 'TEMP_ALLOW' AND expiresAt > :now)) ORDER BY id")
    protected abstract fun selectActive(token: String, now: Long): List<RuleEntryEntity>
    @Query("DELETE FROM rule_entries WHERE type='TEMP_ALLOW' AND (bootCount IS NULL OR :boot IS NULL OR bootCount!=:boot OR createdElapsedMs IS NULL OR expiresElapsedMs IS NULL OR createdElapsedMs>:elapsed OR expiresElapsedMs<=:elapsed)")
    protected abstract fun purgeInvalidWindows(elapsed: Long, boot: Int?)
    @Query("SELECT * FROM rule_entries WHERE numberToken=:token AND enabled=1 AND (type IN ('ALLOW','BLOCK') OR (type='TEMP_ALLOW' AND bootCount=:boot AND createdElapsedMs<=:elapsed AND expiresElapsedMs>:elapsed)) ORDER BY id")
    protected abstract fun selectForClock(token: String, elapsed: Long, boot: Int?): List<RuleEntryEntity>
    @Transaction open fun activeForClock(token: String, elapsed: Long, boot: Int?): List<RuleEntryEntity> {
        require(elapsed >= 0)
        purgeInvalidWindows(elapsed, boot)
        return selectForClock(token, elapsed, boot)
    }
    @Query("UPDATE rule_entries SET enabled=0 WHERE numberToken=:token AND type=:type AND enabled=1")
    abstract fun disableType(token:String,type:String)
    @Query("SELECT COUNT(*) FROM rule_entries") abstract fun count(): Int
    open fun put(entry: RuleEntryEntity) {
        require(entry.id.isNotBlank() && entry.numberToken.isNotBlank())
        require(entry.type in setOf("ALLOW", "BLOCK", "TEMP_ALLOW"))
        require(entry.createdAt >= 0)
        require(if (entry.type == "TEMP_ALLOW") entry.expiresAt != null && entry.expiresAt > entry.createdAt else entry.expiresAt == null)
        if (entry.createdElapsedMs != null || entry.expiresElapsedMs != null || entry.bootCount != null) {
            require(entry.type == "TEMP_ALLOW")
            require(entry.createdElapsedMs != null && entry.createdElapsedMs >= 0)
            require(entry.expiresElapsedMs != null && entry.expiresElapsedMs > entry.createdElapsedMs)
            require(entry.bootCount != null && entry.bootCount >= 0)
        }
        insert(entry)
    }
    @Transaction open fun activeForNumber(token: String, now: Long): List<RuleEntryEntity> {
        require(now >= 0)
        deleteExpired(now)
        return selectActive(token, now)
    }
}
