package com.callguard.app.data.entity
import androidx.room.*
@Entity(tableName = "rule_entries", indices = [Index(value = ["numberToken", "type"])])
data class RuleEntryEntity(
    @PrimaryKey val id: String, val numberToken: String, val type: String,
    val createdAt: Long, val expiresAt: Long? = null, val source: String, val enabled: Boolean = true,
    @ColumnInfo(defaultValue = "NULL") val createdElapsedMs: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val expiresElapsedMs: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val bootCount: Int? = null,
)
