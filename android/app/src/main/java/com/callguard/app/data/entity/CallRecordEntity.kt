package com.callguard.app.data.entity
import androidx.room.*
@Entity(tableName = "call_records", indices = [Index(value = ["numberToken", "receivedAt"])])
data class CallRecordEntity(
    @PrimaryKey val id: String, val numberToken: String, val receivedAt: Long,
    val repeatCount: Int = 0, val riskScore: Int? = null, val riskLevel: String = "NOT_EVALUATED",
    val decision: String = "ALLOW", val matchedRules: String = "[]", val fallbackUsed: Boolean = false,
    val policyVersion: String = "wp02-no-policy",
    @ColumnInfo(defaultValue = "'UNKNOWN'") val normalizationStatus: String = "UNKNOWN",
    @ColumnInfo(defaultValue = "NULL") val latencyMs: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val receivedElapsedMs: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val bootCount: Int? = null,
    @ColumnInfo(defaultValue = "NULL") val retryStartedAtMs: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val retryWindowUntil: Long? = null,
)
