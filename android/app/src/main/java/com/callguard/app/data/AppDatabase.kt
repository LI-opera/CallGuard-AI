package com.callguard.app.data
import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.callguard.app.data.dao.*
import com.callguard.app.data.entity.*
@Database(entities = [CallerProfileEntity::class, CallRecordEntity::class, RuleEntryEntity::class, FeedbackEventEntity::class], version = 5, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun callerProfiles(): CallerProfileDao
    abstract fun callRecords(): CallRecordDao
    abstract fun rules(): RuleEntryDao
    abstract fun feedback(): FeedbackDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE caller_profiles ADD COLUMN normalizationStatus TEXT NOT NULL DEFAULT 'UNKNOWN'")
                db.execSQL("ALTER TABLE call_records ADD COLUMN normalizationStatus TEXT NOT NULL DEFAULT 'UNKNOWN'")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE call_records ADD COLUMN latencyMs INTEGER DEFAULT NULL")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (column in listOf("receivedElapsedMs", "bootCount", "retryStartedAtMs", "retryWindowUntil"))
                    db.execSQL("ALTER TABLE call_records ADD COLUMN $column INTEGER DEFAULT NULL")
                for (column in listOf("createdElapsedMs", "expiresElapsedMs", "bootCount"))
                    db.execSQL("ALTER TABLE rule_entries ADD COLUMN $column INTEGER DEFAULT NULL")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS feedback_events (sequence INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, feedbackId TEXT NOT NULL, callId TEXT NOT NULL, numberToken TEXT NOT NULL, action TEXT NOT NULL, createdAt INTEGER NOT NULL, profileVersion INTEGER NOT NULL, applied INTEGER NOT NULL, syncStatus TEXT NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_feedback_events_feedbackId ON feedback_events(feedbackId)")
            }
        }
        /** Database and non-exportable token key must not be restored independently. */
        fun open(context: Context, name: String = "callguard.db"): AppDatabase {
            require(name.matches(Regex("[a-zA-Z0-9_-]+\\.db")))
            val file = java.io.File(context.noBackupFilesDir, name)
            return Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, file.absolutePath)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
        }
    }
}
