package com.callguard.app.screening

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.callguard.app.data.AppDatabase
import com.callguard.app.data.entity.RuleEntryEntity
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import org.json.JSONObject

/** Debug test APK only. Operates on an explicit existing event token; no global forced decision. */
@RunWith(AndroidJUnit4::class)
class LiveValidationFixture {
    @Test fun configure() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val args=InstrumentationRegistry.getArguments()
        val id=UUID.fromString(requireNotNull(args.getString("eventId"))).toString()
        val mode=requireNotNull(args.getString("mode"))
        require(mode in setOf("MARKETING","BLOCK","RESTORE"))
        val backup=File(context.noBackupFilesDir,"wp04-validation-backup-$id.json")
        val ruleId="wp04-validation-$id"
        val db=AppDatabase.open(context)
        try {
            val event=requireNotNull(db.callRecords().get(id))
            require(event.normalizationStatus=="NORMALIZED")
            val profile=requireNotNull(db.callerProfiles().get(event.numberToken))
            if(mode!="RESTORE" && !backup.exists()) backup.writeText(JSONObject()
                .put("eventId",id).put("numberToken",event.numberToken)
                .put("originalLabel",profile.userLabel ?: JSONObject.NULL).toString())
            when(mode) {
                "MARKETING" -> db.openHelper.writableDatabase.execSQL(
                    "UPDATE caller_profiles SET userLabel='MARKETING' WHERE numberToken=?",arrayOf(event.numberToken))
                "BLOCK" -> db.rules().put(RuleEntryEntity(ruleId,event.numberToken,"BLOCK",System.currentTimeMillis(),null,"wp04-validation"))
                "RESTORE" -> {
                    val saved=JSONObject(backup.readText())
                    require(saved.getString("numberToken")==event.numberToken)
                    db.runInTransaction {
                        db.openHelper.writableDatabase.execSQL("DELETE FROM rule_entries WHERE id=? AND source='wp04-validation'",arrayOf(ruleId))
                        db.openHelper.writableDatabase.execSQL("UPDATE caller_profiles SET userLabel=? WHERE numberToken=?",
                            arrayOf(if(saved.isNull("originalLabel")) null else saved.getString("originalLabel"),event.numberToken))
                    }
                    assertTrue(backup.delete())
                }
            }
            assertNotNull(db.callRecords().get(id))
        } finally { db.close() }
    }
}
