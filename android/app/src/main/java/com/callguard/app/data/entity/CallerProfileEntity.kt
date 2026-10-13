package com.callguard.app.data.entity
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
@Entity(tableName = "caller_profiles")
data class CallerProfileEntity(
    @PrimaryKey val numberToken: String,
    val firstSeenAt: Long, val lastSeenAt: Long,
    val callCount: Long = 0, val answeredCount: Long = 0, val rejectedCount: Long = 0,
    val lastDecision: String = "ALLOW", val userLabel: String? = null, val profileVersion: Long = 1,
    @ColumnInfo(defaultValue = "'UNKNOWN'") val normalizationStatus: String = "UNKNOWN",
)
