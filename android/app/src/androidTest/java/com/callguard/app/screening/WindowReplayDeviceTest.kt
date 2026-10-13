package com.callguard.app.screening

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.callguard.app.data.AppDatabase
import com.callguard.app.data.RuleActionsRepository
import com.callguard.app.data.entity.RuleEntryEntity
import com.callguard.app.platform.*
import com.callguard.app.policy.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/** One synthetic replay on real Room/Keystore. Advances injected clock, never changes device clock. */
@RunWith(AndroidJUnit4::class)
class WindowReplayDeviceTest {
    @Test fun replayAndUserRules() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val name="wp05-window-check.db"
        val db=AppDatabase.open(app,name)
        var time=AndroidWindowClock(app).now()
        assertNotNull(time.bootCount)
        val clock=WindowClock {time}
        var source=AndroidScreeningDataSource(app,name,windowClock=clock)
        val rows=JSONArray()
        fun call(id:String):PolicyResult {
            val request=ScreeningRequest(id,"tel:+12025550123",true,time.wallMs,ScreeningDeadline(time.elapsedMs))
            val state=source.load(request){}
            val result=PolicyEngine().decide(state.input)
            request.respondedAtMs=time.elapsedMs+1 // Synthetic timing, NOT a real platform response.
            source.persist(request,state,result)
            val record=db.callRecords().get(id)!!
            rows.put(JSONObject().put("id",id).put("decision",result.decision.name)
                .put("score",result.riskScore ?: JSONObject.NULL).put("repeatCount",record.repeatCount)
                .put("reasons",JSONArray(result.matchedRules)))
            return result
        }
        try {
            db.clearAllTables() // Dedicated synthetic test database only.
            assertEquals(20,call("first").riskScore)
            time=time.copy(wallMs=time.wallMs+60_000,elapsedMs=time.elapsedMs+60_000)
            assertEquals(14,call("second").riskScore)
            source=AndroidScreeningDataSource(app,name,windowClock=clock)
            time=time.copy(wallMs=time.wallMs+60_000,elapsedMs=time.elapsedMs+60_000)
            assertEquals(16,call("third").riskScore)
            val token=db.callRecords().get("first")!!.numberToken
            assertEquals(2,db.callRecords().get("third")!!.repeatCount)
            assertEquals(db.callRecords().get("first")!!.retryWindowUntil,db.callRecords().get("third")!!.retryWindowUntil)
            db.openHelper.writableDatabase.execSQL("UPDATE caller_profiles SET userLabel='MARKETING' WHERE numberToken=?",arrayOf(token))
            val actions=RuleActionsRepository(db,clock)
            actions.allowForFiveMinutes("first")
            time=time.copy(wallMs=1,elapsedMs=time.elapsedMs+299_999)
            assertEquals("TEMP_ALLOW",call("temporary-before-expiry").matchedRules.single())
            time=time.copy(wallMs=System.currentTimeMillis()+1_000_000,elapsedMs=time.elapsedMs+1)
            assertFalse(call("temporary-expired").matchedRules.contains("TEMP_ALLOW"))
            db.rules().put(RuleEntryEntity("fixture-block",token,"BLOCK",0,null,"test"))
            assertEquals(Decision.BLOCK,call("blocked-repeat").decision)
            actions.addToAllowList("first");actions.addToAllowList("first")
            time=time.copy(bootCount=time.bootCount!!+1,elapsedMs=1)
            assertEquals("ALLOW_RULE",call("permanent-after-reboot").matchedRules.single())
            File(app.noBackupFilesDir,"wp05-window-check.json").writeText(JSONObject()
                .put("syntheticInput",true).put("injectedClock",true).put("simCallTest",false)
                .put("cases",rows).toString())
        } finally { db.close() }
    }
}
