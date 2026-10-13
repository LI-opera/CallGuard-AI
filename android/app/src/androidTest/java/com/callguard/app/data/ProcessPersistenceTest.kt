package com.callguard.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.callguard.app.caller.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import org.json.JSONObject

/** Invoke seed and readAfterRestart separately, with an explicit am force-stop between runs. */
@RunWith(AndroidJUnit4::class)
class ProcessPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun marker() = File(context.noBackupFilesDir, "wp02-process-check.json")
    @Test fun seed() {
        val number = PhoneNumberNormalizer(AndroidNumberTokenizer.create()).normalize("+12025550123", "US")
        AppDatabase.open(context, "wp02-persistence-check.db").use { db ->
            db.clearAllTables() // Dedicated synthetic verification DB only; never callguard.db.
            val repository = CallerRepository(db)
            repository.recordIncomingCallAndUpdateProfile("wp02-seed-1", number, 100)
            repository.recordIncomingCallAndUpdateProfile("wp02-seed-2", number, 200)
            assertEquals(2, db.callRecords().count())
            assertEquals(1, db.callerProfiles().count())
            assertEquals(2L, db.callerProfiles().get(number.numberToken)!!.callCount)
        }
        marker().writeText(JSONObject().put("seedPid", android.os.Process.myPid())
            .put("token", number.numberToken).put("seedPassed", true).toString())
    }
    @Test fun readAfterRestart() {
        val saved = JSONObject(marker().readText())
        assertNotEquals(saved.getInt("seedPid"), android.os.Process.myPid())
        val number = PhoneNumberNormalizer(AndroidNumberTokenizer.create()).normalize("202-555-0123", "US")
        assertEquals(saved.getString("token"), number.numberToken)
        AppDatabase.open(context, "wp02-persistence-check.db").use { db ->
            assertEquals(2, db.callRecords().count())
            assertEquals(1, db.callerProfiles().count())
            val profile = db.callerProfiles().get(number.numberToken)!!
            assertEquals(2L, profile.callCount)
            assertEquals(100L, profile.firstSeenAt)
            assertEquals(200L, profile.lastSeenAt)
            assertEquals("NORMALIZED", profile.normalizationStatus)
        }
        marker().writeText(saved.put("readPid", android.os.Process.myPid())
            .put("readPassed", true).put("recordCount", 2).put("profileCount", 1).toString())
    }
}

private inline fun <T> AppDatabase.use(block: (AppDatabase) -> T): T =
    try { block(this) } finally { close() }
