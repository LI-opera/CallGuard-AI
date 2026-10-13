package com.callguard.app.data.dao
import androidx.room.*
import com.callguard.app.data.entity.FeedbackEventEntity
@Dao interface FeedbackDao {
    @Insert(onConflict=OnConflictStrategy.ABORT) fun insert(event: FeedbackEventEntity): Long
    @Query("SELECT * FROM feedback_events WHERE feedbackId=:id") fun get(id:String): FeedbackEventEntity?
    @Query("SELECT * FROM feedback_events WHERE numberToken=:token ORDER BY sequence DESC LIMIT 1") fun latest(token:String): FeedbackEventEntity?
    @Query("SELECT * FROM feedback_events WHERE applied=0 ORDER BY sequence LIMIT 1") fun nextPending(): FeedbackEventEntity?
    @Query("UPDATE feedback_events SET applied=1 WHERE sequence=:sequence") fun markApplied(sequence:Long)
    @Query("SELECT COUNT(*) FROM feedback_events") fun count():Int
}
