package com.callguard.app.screening

import java.io.File
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticEvidenceStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun record() = ScreeningEvidence(
        "11111111-1111-4111-8111-111111111111", 100, 102, 1_700_000_000_000,
        42, 35, "0.1.0-wp01-diag1", 1,
        "received eventId=11111111-1111-4111-8111-111111111111 api=35 receivedAtMs=100 direction=0",
        "responded eventId=11111111-1111-4111-8111-111111111111 decision=ALLOW respondedAtMs=102 latencyMs=2",
    )
    @Test fun committedRecordCanBeReadBackWithExactLogsAndSchema() {
        val directory = File(temporary.root, "evidence")
        val record = record()
        DiagnosticEvidenceStore.write(directory, record)
        val files = directory.listFiles()!!
        assertEquals(1, files.size)
        val json = JSONObject(files.single().readText())
        assertEquals(record.eventId, json.getString("eventId"))
        assertEquals(2, json.getLong("latencyMs"))
        assertEquals(record.receivedLog, json.getString("receivedLog"))
        assertEquals(record.respondedLog, json.getString("respondedLog"))
        assertEquals("respondToCall_returned", json.getString("responseStatus"))
        assertEquals(setOf("schemaVersion", "eventId", "receivedAtMs", "respondedAtMs",
            "latencyMs", "receivedWallTimeMs", "pid", "api", "versionName", "versionCode",
            "direction", "decision", "responseStatus", "receivedLog", "respondedLog"), json.keys().asSequence().toSet())
    }
    @Test fun duplicateDoesNotOverwriteOriginalEvidence() {
        val directory = temporary.newFolder()
        DiagnosticEvidenceStore.write(directory, record())
        val before = directory.listFiles()!!.single().readText()
        assertThrows(IOException::class.java) { DiagnosticEvidenceStore.write(directory, record()) }
        assertEquals(before, directory.listFiles()!!.single().readText())
    }
    @Test fun fullBatchPreservesExistingFilesAndRejectsNewRecord() {
        val directory = temporary.newFolder()
        repeat(DiagnosticEvidenceStore.MAX_RECORDS) { File(directory, "$it.json").writeText("existing") }
        assertThrows(IOException::class.java) { DiagnosticEvidenceStore.write(directory, record()) }
        assertEquals(100, directory.listFiles()!!.size)
        assertTrue(directory.listFiles()!!.all { it.readText() == "existing" })
    }
    @Test fun invalidEventCannotEscapePrivateDirectory() {
        val directory = temporary.newFolder()
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticEvidenceStore.write(directory, record().copy(eventId = "../escape"))
        }
        assertTrue(directory.listFiles()!!.isEmpty())
    }
    @Test fun unavailableDirectoryReportsFailure() {
        val file = temporary.newFile()
        assertThrows(IOException::class.java) { DiagnosticEvidenceStore.write(file, record()) }
    }
}
