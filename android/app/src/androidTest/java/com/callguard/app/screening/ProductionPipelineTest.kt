package com.callguard.app.screening

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.SystemClock
import com.callguard.app.caller.*
import com.callguard.app.data.AppDatabase
import com.callguard.app.data.entity.RuleEntryEntity
import com.callguard.app.policy.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference

/** Real Room and Keystore on device; synthetic input and captured platform boundary, not a SIM call. */
@RunWith(AndroidJUnit4::class)
class ProductionPipelineTest {
    @Test fun roomDecisionsAndRealDatabaseLockMeetDeadline() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="wp04-pipeline-check.db"
        val db=AppDatabase.open(context,name)
        val source=AndroidScreeningDataSource(context,name)
        val workers=Executors.newFixedThreadPool(2)
        val writes=Executors.newSingleThreadExecutor()
        val timer=Executors.newScheduledThreadPool(2)
        val rows=JSONArray()
        val number=PhoneNumberNormalizer(AndroidNumberTokenizer.create()).normalize("+12025550123","US")
        fun run(label:String,expected:Decision,fallback:Boolean=false) {
            val event=UUID.randomUUID().toString()
            val done=CountDownLatch(1);val saved=CountDownLatch(1)
            val actual=AtomicReference<PolicyResult>();val elapsed=java.util.concurrent.atomic.AtomicLong()
            val start=SystemClock.elapsedRealtime()
            val coordinator=ScreeningCoordinator(source,workers,timer,writes,MonotonicClock {SystemClock.elapsedRealtime()}) { id,status ->
                if(id==event && (status=="record_saved" || status=="record_write_failed")) saved.countDown()
            }
            coordinator.screen(ScreeningRequest(event,"tel:+12025550123",true,System.currentTimeMillis(),ScreeningDeadline(start)),{
                actual.set(it);elapsed.set(SystemClock.elapsedRealtime()-start);done.countDown()
            },{})
            assertTrue("response missing",done.await(2,TimeUnit.SECONDS))
            assertEquals(expected,actual.get().decision);assertEquals(fallback,actual.get().fallbackUsed)
            assertTrue("response exceeded 2 seconds",elapsed.get()<2000)
            rows.put(JSONObject().put("scenario",label).put("eventId",event).put("decision",actual.get().decision.name)
                .put("latencyMs",elapsed.get()).put("fallbackUsed",actual.get().fallbackUsed))
            if(!fallback) {
                assertTrue(saved.await(3,TimeUnit.SECONDS))
                assertEquals(expected.name,db.callRecords().get(event)!!.decision)
                assertNotNull(db.callRecords().get(event)!!.latencyMs)
            }
        }
        val unlock=CountDownLatch(1);var locker:Thread?=null
        try {
            db.clearAllTables() // Dedicated synthetic test DB only.
            run("fresh_room_allow",Decision.ALLOW)
            db.openHelper.writableDatabase.execSQL("UPDATE caller_profiles SET userLabel='MARKETING' WHERE numberToken=?",arrayOf(number.numberToken))
            run("room_profile_silence",Decision.SILENCE)
            db.rules().put(RuleEntryEntity("test-block",number.numberToken,"BLOCK",0,null,"instrumentation"))
            run("room_rule_block",Decision.BLOCK)
            val locked=CountDownLatch(1)
            locker=Thread {
                db.runInTransaction { locked.countDown();unlock.await(5,TimeUnit.SECONDS) }
            }.apply {start()}
            assertTrue(locked.await(1,TimeUnit.SECONDS))
            run("room_write_lock_fallback",Decision.ALLOW,true)
            // Lock prevents rule lookup; no claim that an unread blacklist can be preserved.
            unlock.countDown();locker.join(3000)
            writes.shutdown();assertTrue(writes.awaitTermination(4,TimeUnit.SECONDS))
            File(context.noBackupFilesDir,"wp04-pipeline-check.json").writeText(JSONObject()
                .put("syntheticInput",true).put("platformResponseCaptured",true).put("simCallTest",false)
                .put("cases",rows).toString())
        } finally {
            unlock.countDown();locker?.join(3000)
            workers.shutdownNow();writes.shutdownNow();timer.shutdownNow();db.close()
        }
    }
}
