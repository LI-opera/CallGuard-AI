package com.callguard.app.screening

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.callguard.app.data.AppDatabase
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.UUID

/** Read-only inspection of an explicit real event; does not seed or modify application records. */
@RunWith(AndroidJUnit4::class)
class LiveRecordInspectionTest {
    @Test fun export() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val id=UUID.fromString(requireNotNull(InstrumentationRegistry.getArguments().getString("eventId"))).toString()
        val db=AppDatabase.open(context)
        try {
            val record=requireNotNull(db.callRecords().get(id))
            val profile=requireNotNull(db.callerProfiles().get(record.numberToken))
            assertNotNull(record.latencyMs)
            File(context.noBackupFilesDir,"wp04-record-$id.json").writeText(JSONObject()
                .put("eventId",record.id).put("numberToken",record.numberToken).put("receivedAt",record.receivedAt)
                .put("decision",record.decision).put("riskScore",record.riskScore ?: JSONObject.NULL)
                .put("latencyMs",record.latencyMs).put("matchedRules",JSONArray(record.matchedRules))
                .put("fallbackUsed",record.fallbackUsed).put("policyVersion",record.policyVersion)
                .put("normalizationStatus",record.normalizationStatus).put("profileCallCount",profile.callCount).toString())
        } finally { db.close() }
    }
}
