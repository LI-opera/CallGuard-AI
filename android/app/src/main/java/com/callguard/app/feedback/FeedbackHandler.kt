package com.callguard.app.feedback

import com.callguard.app.data.AppDatabase
import com.callguard.app.data.entity.*
import java.util.UUID

enum class FeedbackAction { NORMAL, HARASSMENT, ALLOW, BLOCK }
/** Worker-thread only. Durable enqueue commits BEFORE applying the projection.
 * Ordered replay + a single transaction per event makes crash/retry idempotent.
 * No network, no mutations to historical CallRecord rows or call counters. */
class FeedbackHandler(private val db:AppDatabase) {
    fun enqueue(callId:String, action:FeedbackAction, profileVersion:Long,
                feedbackId:String=UUID.randomUUID().toString(), at:Long=System.currentTimeMillis()):String {
        require(feedbackId.isNotBlank() && at>=0)
        return db.runInTransaction(java.util.concurrent.Callable {
            val record=requireNotNull(db.callRecords().get(callId))
            require(record.normalizationStatus=="NORMALIZED")
            db.feedback().get(feedbackId)?.let {
                require(it.callId==callId && it.action==action.name) { "Feedback identity reused" }
                return@Callable it.feedbackId
            }
            val profile=requireNotNull(db.callerProfiles().get(record.numberToken))
            // UI double tap may have a stale profile version. The identical latest action is safe to reuse.
            db.feedback().latest(record.numberToken)?.let {
                if (it.callId==callId && it.action==action.name) return@Callable it.feedbackId
            }
            check(profile.profileVersion==profileVersion) { "Profile changed; refresh before retry" }
            db.feedback().insert(FeedbackEventEntity(feedbackId=feedbackId,callId=callId,
                numberToken=record.numberToken,action=action.name,createdAt=at,profileVersion=profileVersion))
            feedbackId
        })
    }
    fun drain() {
        while (applyNext()) { /* ordered local replay */ }
    }
    private fun applyNext():Boolean = db.runInTransaction(java.util.concurrent.Callable {
        val event=db.feedback().nextPending() ?: return@Callable false
        checkNotNull(db.callerProfiles().get(event.numberToken))
        when (FeedbackAction.valueOf(event.action)) {
            FeedbackAction.NORMAL, FeedbackAction.HARASSMENT -> db.callerProfiles().setLabel(event.numberToken,event.action)
            FeedbackAction.ALLOW, FeedbackAction.BLOCK -> {
                val opposite=if(event.action=="ALLOW") "BLOCK" else "ALLOW"
                db.rules().disableType(event.numberToken,opposite)
                db.rules().put(RuleEntryEntity("user-${event.action}-${event.numberToken}",event.numberToken,
                    event.action,event.createdAt,null,"user"))
            }
        }
        db.feedback().markApplied(event.sequence)
        true
    })
}
