package com.callguard.app.data
import com.callguard.app.caller.*
import com.callguard.app.data.entity.*
import com.callguard.app.feedback.*
import com.callguard.app.platform.*
import com.callguard.app.policy.*
import com.callguard.app.screening.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class FeedbackIntegrationTest {
    @get:Rule val worker = org.junit.rules.TestRule { base, _ -> object:org.junit.runners.model.Statement() {
        override fun evaluate() {
            val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
            try { executor.submit(java.util.concurrent.Callable { base.evaluate() }).get() }
            catch(e:java.util.concurrent.ExecutionException) { throw e.cause!! }
            finally { executor.shutdownNow() }
        }
    } }
    private val app get()=RuntimeEnvironment.getApplication()
    private lateinit var db:AppDatabase
    private lateinit var name:String
    private val tokenizer=HmacNumberTokenizer(SecretKeySpec(ByteArray(32){7},"HmacSHA256"))
    private val token get()=tokenizer.token("+12025550123")
    @Before fun setup() {
        name="feedback-${UUID.randomUUID()}.db";db=AppDatabase.open(app,name)
        db.callRecords().recordIncomingCallAndUpdateProfile(CallRecordEntity("first",token,1000,normalizationStatus="NORMALIZED"))
    }
    @After fun close() { db.close() }
    private fun feedback(action:FeedbackAction,id:String=UUID.randomUUID().toString()):String {
        val handler=FeedbackHandler(db)
        val result=handler.enqueue("first",action,db.callerProfiles().get(token)!!.profileVersion,id,2000)
        handler.drain();return result
    }
    private fun decision():PolicyResult {
        val source=AndroidScreeningDataSource(app,name,{tokenizer},WindowClock {WindowTime(400_000,400_000,1)},{db})
        val request=ScreeningRequest("next","tel:+12025550123",true,400_000,ScreeningDeadline(400_000))
        return PolicyEngine().decide(source.load(request){}.input)
    }
    @Test fun harassmentThenNormalAffectsNextDecisionWithoutChangingHistory() {
        val old=db.callRecords().get("first")
        feedback(FeedbackAction.HARASSMENT)
        val result=decision();assertEquals(Decision.SILENCE,result.decision);assertTrue("USER_HARASSMENT" in result.matchedRules)
        feedback(FeedbackAction.NORMAL)
        assertEquals(Decision.ALLOW,decision().decision)
        assertEquals(old,db.callRecords().get("first"));assertEquals(1L,db.callerProfiles().get(token)!!.callCount)
    }
    @Test fun duplicateTapAndReplayDoNotIncrementVersionOrWeight() {
        val first=feedback(FeedbackAction.HARASSMENT,"id")
        val version=db.callerProfiles().get(token)!!.profileVersion
        assertEquals(first,feedback(FeedbackAction.HARASSMENT,"second-id"))
        FeedbackHandler(db).drain()
        assertEquals(version,db.callerProfiles().get(token)!!.profileVersion);assertEquals(1,db.feedback().count())
        assertEquals("LOCAL_ONLY",db.feedback().get("id")!!.syncStatus)
        assertThrows(IllegalArgumentException::class.java) { feedback(FeedbackAction.NORMAL,"id") }
    }
    @Test fun pendingEventSurvivesCloseAndReopen() {
        FeedbackHandler(db).enqueue("first",FeedbackAction.HARASSMENT,1,"pending",2000)
        assertNull(db.callerProfiles().get(token)!!.userLabel)
        db.close();db=AppDatabase.open(app,name)
        FeedbackHandler(db).drain()
        assertTrue(db.feedback().get("pending")!!.applied)
        assertEquals("HARASSMENT",db.callerProfiles().get(token)!!.userLabel)
    }
    @Test fun projectionFailureRollsBackButKeepsPendingEventForRetry() {
        val before=db.callRecords().get("first")
        FeedbackHandler(db).enqueue("first",FeedbackAction.HARASSMENT,1,"pending",2000)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_label BEFORE UPDATE OF userLabel ON caller_profiles BEGIN SELECT RAISE(ABORT,'test failure'); END")
        assertThrows(Exception::class.java) { FeedbackHandler(db).drain() }
        assertFalse(db.feedback().get("pending")!!.applied);assertNull(db.callerProfiles().get(token)!!.userLabel)
        assertEquals(before,db.callRecords().get("first"))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_label")
        FeedbackHandler(db).drain();assertEquals(2L,db.callerProfiles().get(token)!!.profileVersion)
    }
    @Test fun explicitOppositeListsReplaceRatherThanSilentlyConflict() {
        feedback(FeedbackAction.ALLOW);assertEquals("ALLOW_RULE",decision().matchedRules.single())
        feedback(FeedbackAction.BLOCK);assertEquals("BLOCK_RULE",decision().matchedRules.single())
        feedback(FeedbackAction.ALLOW);assertEquals("ALLOW_RULE",decision().matchedRules.single())
        assertEquals(1,db.rules().activeForClock(token,400000,1).size)
    }
    @Test fun staleProfileAndUnknownNumberCannotCreateNewFeedback() {
        db.callRecords().recordIncomingCallAndUpdateProfile(CallRecordEntity("second",token,2000,normalizationStatus="NORMALIZED"))
        assertThrows(IllegalStateException::class.java) { FeedbackHandler(db).enqueue("first",FeedbackAction.NORMAL,1) }
        db.callRecords().recordIncomingCallAndUpdateProfile(CallRecordEntity("unknown","u1:unknown",2000))
        assertThrows(IllegalArgumentException::class.java) { FeedbackHandler(db).enqueue("unknown",FeedbackAction.NORMAL,1) }
        assertEquals(0,db.feedback().count())
    }
    @Test fun orderedPendingCorrectionsUseLastChoiceOnce() {
        val h=FeedbackHandler(db)
        h.enqueue("first",FeedbackAction.HARASSMENT,1,"a");h.enqueue("first",FeedbackAction.NORMAL,1,"b")
        h.drain();h.drain()
        assertEquals("NORMAL",db.callerProfiles().get(token)!!.userLabel)
        assertEquals(3L,db.callerProfiles().get(token)!!.profileVersion)
    }
    @Test fun keysetPagesAreStableOnTiesAndNewInsertionAndSummaryUsesExclusiveEnd() {
        for(i in 1..25) db.callRecords().recordIncomingCallAndUpdateProfile(CallRecordEntity("r%02d".format(i),token,3000,decision="SILENCE",fallbackUsed=true))
        val first=db.callRecords().page(null,"",20);val tail=first.last()
        db.callRecords().recordIncomingCallAndUpdateProfile(CallRecordEntity("new",token,4000,decision="BLOCK"))
        val rest=db.callRecords().page(tail.receivedAt,tail.id,20)
        assertEquals(26,(first+rest).map {it.id}.toSet().size);assertFalse(rest.any {it.id=="new"})
        val summary=db.callRecords().summary(1000,4000)
        assertEquals(26,summary.total);assertEquals(25,summary.silenced);assertEquals(25,summary.fallback);assertEquals(0,summary.blocked)
    }
}
