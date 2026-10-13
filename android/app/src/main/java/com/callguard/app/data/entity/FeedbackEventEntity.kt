package com.callguard.app.data.entity
import androidx.room.*
@Entity(tableName="feedback_events", indices=[Index(value=["feedbackId"], unique=true)])
data class FeedbackEventEntity(
    @PrimaryKey(autoGenerate=true) val sequence: Long=0,
    val feedbackId: String, val callId: String, val numberToken: String,
    val action: String, val createdAt: Long, val profileVersion: Long,
    val applied: Boolean=false,
    // No server exists yet. Local application must not imply upload success.
    val syncStatus: String="LOCAL_ONLY",
)
