package com.callguard.app.screening
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.callguard.app.data.AppDatabase
import com.callguard.app.feedback.*
import com.callguard.app.platform.*
import com.callguard.app.policy.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File

/** Real SQLite/Keystore, synthetic input only. Never writes the production database. */
@RunWith(AndroidJUnit4::class)
class FeedbackDeviceTest {
    @Test fun durableFeedbackAndNextDecision() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val name="wp06-feedback-check.db"
        var db=AppDatabase.open(app,name)
        val time=AndroidWindowClock(app).now()
        val clock=WindowClock {time}
        try {
            db.clearAllTables()
            val source=AndroidScreeningDataSource(app,name,windowClock=clock)
            val request=ScreeningRequest("first","tel:+12025550123",true,time.wallMs,ScreeningDeadline(time.elapsedMs))
            val state=source.load(request){}
            source.persist(request,state,PolicyEngine().decide(state.input))
            val record=db.callRecords().get("first")!!
            val h=FeedbackHandler(db)
            h.enqueue("first",FeedbackAction.HARASSMENT,1,"first-feedback")
            assertFalse(db.feedback().get("first-feedback")!!.applied)
            db.close();db=AppDatabase.open(app,name)
            FeedbackHandler(db).drain()
            val current=db.callerProfiles().get(record.numberToken)!!
            assertEquals(2L,current.profileVersion)
            assertEquals("HARASSMENT",current.userLabel)
            assertEquals("first-feedback",FeedbackHandler(db).enqueue("first",FeedbackAction.HARASSMENT,1,"double-tap"))
            FeedbackHandler(db).drain();assertEquals(1,db.feedback().count())
            val next=ScreeningRequest("next","tel:+12025550123",true,time.wallMs+1000,ScreeningDeadline(time.elapsedMs+1000))
            val harassment=PolicyEngine().decide(source.load(next){}.input)
            assertTrue("USER_HARASSMENT" in harassment.matchedRules)
            assertEquals(Decision.SILENCE,harassment.decision)
            FeedbackHandler(db).enqueue("first",FeedbackAction.NORMAL,2,"normal")
            FeedbackHandler(db).drain()
            val normal=PolicyEngine().decide(source.load(next){}.input)
            assertEquals(Decision.ALLOW,normal.decision)
            assertEquals(record,db.callRecords().get("first"))
            assertEquals(1L,db.callerProfiles().get(record.numberToken)!!.callCount)
            File(app.noBackupFilesDir,"wp06-feedback-check.json").writeText(JSONObject()
                .put("syntheticInput",true).put("simCallTest",false).put("pendingSurvivedReopen",true)
                .put("duplicateIgnored",true).put("historyUnchanged",true).put("harassmentDecision",harassment.decision.name)
                .put("normalDecision",normal.decision.name).put("feedbackCount",db.feedback().count())
                .put("syncStatus",db.feedback().get("normal")!!.syncStatus).toString())
        } finally { db.close() }
    }
}
