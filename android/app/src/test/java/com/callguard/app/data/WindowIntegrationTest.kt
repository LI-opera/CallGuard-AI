package com.callguard.app.data

import com.callguard.app.caller.*
import com.callguard.app.data.entity.*
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
class WindowIntegrationTest {
    @get:Rule val workerThread = org.junit.rules.TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
                try { executor.submit(java.util.concurrent.Callable { base.evaluate() }).get() }
                catch (error: java.util.concurrent.ExecutionException) { throw error.cause!! }
                finally { executor.shutdownNow() }
            }
        }
    }
    private val app get()=RuntimeEnvironment.getApplication()
    private lateinit var db:AppDatabase
    private lateinit var source:AndroidScreeningDataSource
    private lateinit var name:String
    private var time=WindowTime(1_000_000,10_000,7)
    private val clock=WindowClock {time}
    private val tokenizer=HmacNumberTokenizer(SecretKeySpec(ByteArray(32){7},"HmacSHA256"))
    private val token get()=tokenizer.token("+12025550123")
    @Before fun setup() {
        name="window-${UUID.randomUUID()}.db"
        db=AppDatabase.open(app,name)
        source=AndroidScreeningDataSource(app,name,{tokenizer},clock,{db})
    }
    @After fun close() { db.close() }
    private fun call(id:String):Pair<PolicyResult,CallRecordEntity> {
        val request=ScreeningRequest(id,"tel:+12025550123",true,time.wallMs,ScreeningDeadline(time.elapsedMs))
        val state=source.load(request){}
        val result=PolicyEngine().decide(state.input)
        request.respondedAtMs=time.elapsedMs+1
        source.persist(request,state,result)
        return result to db.callRecords().get(id)!!
    }
    @Test fun firstSecondThirdReplayUsesFixedWindowAndSingleDiscount() {
        val first=call("first");assertEquals(20,first.first.riskScore);assertEquals(0,first.second.repeatCount)
        time=time.copy(wallMs=time.wallMs+60_000,elapsedMs=time.elapsedMs+60_000)
        val second=call("second");assertEquals(14,second.first.riskScore);assertEquals(1,second.second.repeatCount)
        // Close and reopen Room: no in-memory window cache can satisfy this assertion.
        db.close();db=AppDatabase.open(app,name)
        source=AndroidScreeningDataSource(app,name,{tokenizer},clock,{db})
        time=time.copy(wallMs=time.wallMs+60_000,elapsedMs=time.elapsedMs+60_000)
        val third=call("third");assertEquals(16,third.first.riskScore);assertEquals(2,third.second.repeatCount)
        assertEquals(first.second.retryWindowUntil,third.second.retryWindowUntil)
        assertTrue(third.first.matchedRules.contains("REPEAT_CALL"))
        assertEquals(1,db.callerProfiles().count());assertEquals(3L,db.callerProfiles().get(token)!!.callCount)
        time=time.copy(elapsedMs=first.second.retryWindowUntil!!+1)
        assertFalse(call("outside").first.matchedRules.contains("REPEAT_CALL"))
    }
    @Test fun repeatCannotOverrideMediumRiskOrBlacklist() {
        call("first")
        db.openHelper.writableDatabase.execSQL("UPDATE caller_profiles SET userLabel='MARKETING' WHERE numberToken=?",arrayOf(token))
        time=time.copy(wallMs=time.wallMs+1000,elapsedMs=time.elapsedMs+1000)
        val second=call("second");assertEquals(59,second.first.riskScore);assertEquals(Decision.SILENCE,second.first.decision)
        db.rules().put(RuleEntryEntity("block",token,"BLOCK",time.wallMs,null,"test"))
        time=time.copy(wallMs=time.wallMs+1000,elapsedMs=time.elapsedMs+1000)
        assertEquals(Decision.BLOCK,call("third").first.decision)
    }
    @Test fun temporaryAllowSurvivesWallClockChangesButExpiresAtExactMonotonicDeadline() {
        call("first")
        val actions=RuleActionsRepository(db,clock);actions.allowForFiveMinutes("first")
        db.openHelper.writableDatabase.execSQL("UPDATE caller_profiles SET userLabel='MARKETING' WHERE numberToken=?",arrayOf(token))
        time=time.copy(wallMs=1,elapsedMs=309_999)
        assertEquals("TEMP_ALLOW",call("before").first.matchedRules.single())
        time=time.copy(wallMs=8_000_000,elapsedMs=310_000)
        val expired=call("expired").first
        assertFalse(expired.matchedRules.contains("TEMP_ALLOW"));assertEquals(Decision.SILENCE,expired.decision)
        assertEquals(0,db.rules().count())
    }
    @Test fun rebootInvalidatesWindowsAndTemporaryRuleButPreservesPermanentAllow() {
        call("first");RuleActionsRepository(db,clock).allowForFiveMinutes("first")
        time=time.copy(bootCount=8,elapsedMs=1)
        val rebooted=call("reboot")
        assertFalse(rebooted.first.matchedRules.contains("REPEAT_CALL"));assertFalse(rebooted.first.matchedRules.contains("TEMP_ALLOW"))
        assertEquals(0,rebooted.second.repeatCount)
        val actions=RuleActionsRepository(db,clock);actions.addToAllowList("first");actions.addToAllowList("first")
        assertEquals(1,db.rules().count())
        time=time.copy(bootCount=9,elapsedMs=1,wallMs=0)
        assertEquals("ALLOW_RULE",call("permanent").first.matchedRules.single())
    }
    @Test fun temporaryAllowDoesNotOverrideBlockAndUnknownCannotCreateRule() {
        call("first");RuleActionsRepository(db,clock).allowForFiveMinutes("first")
        db.rules().put(RuleEntryEntity("block",token,"BLOCK",0,null,"test"))
        assertEquals(Decision.BLOCK,call("blocked").first.decision)
        db.callRecords().recordIncomingCallAndUpdateProfile(CallRecordEntity("unknown","u1:unknown",0))
        assertThrows(IllegalArgumentException::class.java) { RuleActionsRepository(db,clock).allowForFiveMinutes("unknown") }
    }
    @Test fun missingBootIdentityNeverEnablesTemporaryAllowOrRepeat() {
        call("first");time=time.copy(bootCount=null)
        assertThrows(IllegalArgumentException::class.java) {RuleActionsRepository(db,clock).allowForFiveMinutes("first")}
        assertNull(call("unknownBoot").second.retryWindowUntil)
    }
    @Test fun repeatBoundaryIsInclusiveAndDoesNotSlide() {
        val first=call("first")
        time=time.copy(elapsedMs=first.second.retryWindowUntil!!)
        assertTrue(call("edge").first.matchedRules.contains("REPEAT_CALL"))
        time=time.copy(elapsedMs=time.elapsedMs+1)
        assertEquals(0,call("after").second.repeatCount)
    }
}
