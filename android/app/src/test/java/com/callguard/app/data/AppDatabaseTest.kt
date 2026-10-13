package com.callguard.app.data
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.callguard.app.data.entity.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35])
class AppDatabaseTest {
    @get:Rule val temp=TemporaryFolder()
    private val context: Context get()=RuntimeEnvironment.getApplication()
    private fun open(file: File): AppDatabase = Room.databaseBuilder(context,AppDatabase::class.java,file.absolutePath)
        .allowMainThreadQueries().addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5).build()
    private fun record(id:String, token:String="n1:test", at:Long=100)=CallRecordEntity(id,token,at,normalizationStatus="NORMALIZED")
    @Test fun freshDatabaseRepeatedCallsAndReopenPersist() {
        val file=File(temp.root,"fresh.db")
        open(file).use { db ->
            assertEquals(0,db.callRecords().count())
            db.callRecords().recordIncomingCallAndUpdateProfile(record("one"))
            db.callRecords().recordIncomingCallAndUpdateProfile(record("two",at=200))
            assertEquals(1,db.callerProfiles().count())
            assertEquals(2L,db.callerProfiles().get("n1:test")!!.callCount)
        }
        open(file).use { db -> assertEquals(2,db.callRecords().count()); assertEquals(200L,db.callerProfiles().get("n1:test")!!.lastSeenAt) }
    }
    @Test fun failedRecordRollsBackExistingAndNewProfiles() {
        open(File(temp.root,"rollback.db")).use { db ->
            db.callRecords().recordIncomingCallAndUpdateProfile(record("one"))
            assertThrows(Exception::class.java) { db.callRecords().recordIncomingCallAndUpdateProfile(record("one",at=999)) }
            assertEquals(1L,db.callerProfiles().get("n1:test")!!.callCount)
            assertEquals(100L,db.callerProfiles().get("n1:test")!!.lastSeenAt)
            assertThrows(Exception::class.java) { db.callRecords().recordIncomingCallAndUpdateProfile(record("one",token="n1:other")) }
            assertNull(db.callerProfiles().get("n1:other"));assertEquals(1,db.callRecords().count())
        }
    }
    @Test fun expiryBoundaryPurgesExpiredAndDoesNotEnableDisabledRules() {
        open(File(temp.root,"rules.db")).use { db ->
            val dao=db.rules()
            dao.put(RuleEntryEntity("expired","n1:test","TEMP_ALLOW",0,100,"test"))
            dao.put(RuleEntryEntity("live","n1:test","TEMP_ALLOW",0,101,"test"))
            dao.put(RuleEntryEntity("off","n1:test","ALLOW",0,null,"test",false))
            dao.put(RuleEntryEntity("block","n1:test","BLOCK",0,null,"test"))
            assertEquals(setOf("live","block"),dao.activeForNumber("n1:test",100).map { it.id }.toSet())
            assertEquals(3,dao.count())
            assertThrows(IllegalArgumentException::class.java) { dao.put(RuleEntryEntity("bad","n1:test","TEMP_ALLOW",0,null,"test")) }
        }
    }
    private fun legacy(file:File,seed:Boolean) {
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            val sql=javaClass.getResource("/schema-v1.sql")!!.readText()
            sql.split(';').filter { it.isNotBlank() }.forEach { db.execSQL(it) }
            if(seed) {
                db.execSQL("INSERT INTO caller_profiles VALUES ('legacy',10,20,3,1,1,'ALLOW','friend',5)")
                db.execSQL("INSERT INTO call_records VALUES ('old','legacy',20,2,NULL,'NOT_EVALUATED','ALLOW','[]',0,'legacy-v1')")
                db.execSQL("INSERT INTO rule_entries VALUES ('rule','legacy','TEMP_ALLOW',0,100,'test',1)")
            }
            db.version=1
        }
    }
    @Test fun migratesV2WithoutInventingHistoricalLatency() {
        val f=File(temp.root,"v2.db");legacy(f,true)
        SQLiteDatabase.openDatabase(f.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("ALTER TABLE caller_profiles ADD COLUMN normalizationStatus TEXT NOT NULL DEFAULT 'UNKNOWN'")
            db.execSQL("ALTER TABLE call_records ADD COLUMN normalizationStatus TEXT NOT NULL DEFAULT 'UNKNOWN'")
            db.version=2
        }
        open(f).use { db ->
            assertNull(db.callRecords().get("old")!!.latencyMs)
            db.callRecords().recordIncomingCallAndUpdateProfile(record("timed").copy(latencyMs=800))
            assertEquals(800L,db.callRecords().get("timed")!!.latencyMs)
        }
    }
    @Test fun migratesV3WithoutInventingBootOrRetryWindows() {
        val f=File(temp.root,"v3.db");legacy(f,true)
        SQLiteDatabase.openDatabase(f.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("ALTER TABLE caller_profiles ADD COLUMN normalizationStatus TEXT NOT NULL DEFAULT 'UNKNOWN'")
            db.execSQL("ALTER TABLE call_records ADD COLUMN normalizationStatus TEXT NOT NULL DEFAULT 'UNKNOWN'")
            db.execSQL("ALTER TABLE call_records ADD COLUMN latencyMs INTEGER DEFAULT NULL")
            db.version=3
        }
        open(f).use { db ->
            assertNull(db.callRecords().get("old")!!.retryWindowUntil)
            assertNull(db.callRecords().get("old")!!.bootCount)
            assertEquals(3L,db.callerProfiles().get("legacy")!!.callCount)
            assertTrue(db.rules().activeForClock("legacy",100,1).isEmpty())
        }
    }
    @Test fun migratesV4PreservingRecordsRulesAndProfiles() {
        val f=File(temp.root,"v4.db")
        SQLiteDatabase.openOrCreateDatabase(f,null).use { db ->
            val schema=org.json.JSONObject(javaClass.getResource("/schema-v4.json")!!.readText()).getJSONObject("database")
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val entity=entities.getJSONObject(i)
                db.execSQL(entity.getString("createSql").replace("${'$'}{TABLE_NAME}",entity.getString("tableName")))
                val indexes=entity.optJSONArray("indices") ?: org.json.JSONArray()
                for(j in 0 until indexes.length()) db.execSQL(indexes.getJSONObject(j).getString("createSql").replace("${'$'}{TABLE_NAME}",entity.getString("tableName")))
            }
            db.execSQL("INSERT INTO caller_profiles (numberToken,firstSeenAt,lastSeenAt,callCount,answeredCount,rejectedCount,lastDecision,userLabel,profileVersion) VALUES ('legacy',1,2,3,0,0,'BLOCK','MARKETING',4)")
            db.execSQL("INSERT INTO call_records (id,numberToken,receivedAt,repeatCount,riskScore,riskLevel,decision,matchedRules,fallbackUsed,policyVersion,latencyMs) VALUES ('old','legacy',2,0,70,'HIGH','BLOCK','[]',0,'wp03-local-v1',67)")
            db.execSQL("INSERT INTO rule_entries (id,numberToken,type,createdAt,expiresAt,source,enabled) VALUES ('rule','legacy','ALLOW',1,NULL,'user',1)")
            db.version=4
        }
        open(f).use { db ->
            assertEquals("BLOCK",db.callRecords().get("old")!!.decision)
            assertEquals(67L,db.callRecords().get("old")!!.latencyMs)
            assertEquals("MARKETING",db.callerProfiles().get("legacy")!!.userLabel)
            assertEquals(3L,db.callerProfiles().get("legacy")!!.callCount)
            assertEquals(1,db.rules().count());assertEquals(0,db.feedback().count())
        }
    }
    @Test fun migratesEmptyV1WithSchemaValidation() {
        val f=File(temp.root,"empty-old.db");legacy(f,false)
        open(f).use { db -> assertEquals(0,db.callerProfiles().count());db.callRecords().recordIncomingCallAndUpdateProfile(record("new")) }
    }
    @Test fun migratesSeededV1PreservingValuesAndUnknownDefaults() {
        val f=File(temp.root,"seed-old.db");legacy(f,true)
        open(f).use { db ->
            val profile=db.callerProfiles().get("legacy")!!
            assertEquals(3L,profile.callCount);assertEquals(5L,profile.profileVersion)
            assertEquals("friend",profile.userLabel);assertEquals("UNKNOWN",profile.normalizationStatus)
            assertEquals("UNKNOWN",db.callRecords().get("old")!!.normalizationStatus)
            assertEquals(1,db.rules().activeForNumber("legacy",50).size)
        }
    }
}

private inline fun <T> AppDatabase.use(block: (AppDatabase) -> T): T =
    try { block(this) } finally { close() }
