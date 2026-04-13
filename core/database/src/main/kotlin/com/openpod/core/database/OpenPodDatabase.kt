package com.openpod.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.openpod.core.database.converter.InfusionSiteConverter
import com.openpod.core.database.converter.InstantConverter
import com.openpod.core.database.converter.InsulinTypeConverter
import com.openpod.core.database.converter.LocalTimeConverter
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.openpod.core.database.dao.AuditEventDao
import com.openpod.core.database.dao.BasalProgramDao
import com.openpod.core.database.dao.HistoryEventDao
import com.openpod.core.database.dao.InsulinProfileDao
import com.openpod.core.database.dao.PodSessionDao
import com.openpod.core.database.entity.AuditEventEntity
import com.openpod.core.database.entity.BasalProgramEntity
import com.openpod.core.database.entity.HistoryEventEntity
import com.openpod.core.database.entity.BasalSegmentEntity
import com.openpod.core.database.entity.CorrectionFactorSegmentEntity
import com.openpod.core.database.entity.IcRatioSegmentEntity
import com.openpod.core.database.entity.InsulinProfileEntity
import com.openpod.core.database.entity.PodSessionEntity
import com.openpod.core.database.entity.TargetGlucoseSegmentEntity

/**
 * Room database for the OpenPod application.
 *
 * Stores all persistent medical data including the insulin therapy profile,
 * basal programs, and pod session history. The database is encrypted at rest
 * using SQLCipher — the passphrase is managed by [com.openpod.core.database.di.DatabaseModule].
 *
 * **Schema version history:**
 * - Version 1: Initial schema with profile, segments, basal programs, and pod sessions.
 * - Version 2: Add history_event table for persistent event timeline.
 * - Version 3: Add audit_event table for immutable, hash-chained audit trail.
 */
@Database(
    entities = [
        InsulinProfileEntity::class,
        IcRatioSegmentEntity::class,
        CorrectionFactorSegmentEntity::class,
        TargetGlucoseSegmentEntity::class,
        BasalProgramEntity::class,
        BasalSegmentEntity::class,
        PodSessionEntity::class,
        HistoryEventEntity::class,
        AuditEventEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(
    LocalTimeConverter::class,
    InstantConverter::class,
    InsulinTypeConverter::class,
    InfusionSiteConverter::class,
)
abstract class OpenPodDatabase : RoomDatabase() {

    /** DAO for insulin therapy profile and time-segmented settings. */
    abstract fun insulinProfileDao(): InsulinProfileDao

    /** DAO for basal insulin delivery programs and rate segments. */
    abstract fun basalProgramDao(): BasalProgramDao

    /** DAO for pod activation session records. */
    abstract fun podSessionDao(): PodSessionDao

    /** DAO for history event timeline. */
    abstract fun historyEventDao(): HistoryEventDao

    /** DAO for the immutable audit trail. */
    abstract fun auditEventDao(): AuditEventDao

    companion object {
        /** Database file name. */
        const val DATABASE_NAME = "openpod.db"

        /** Migration from version 2 to 3: add audit_event table with hash-chain columns. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `audit_event` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `category` TEXT NOT NULL,
                        `timestamp_utc` INTEGER NOT NULL,
                        `actor` TEXT NOT NULL,
                        `source` TEXT NOT NULL,
                        `clinical_context` TEXT NOT NULL,
                        `payload_json` TEXT NOT NULL,
                        `payload_hash` TEXT NOT NULL,
                        `previous_event_hash` TEXT NOT NULL,
                        `record_checksum` TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_event_timestamp_utc` ON `audit_event` (`timestamp_utc`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_event_category` ON `audit_event` (`category`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_audit_event_clinical_context` ON `audit_event` (`clinical_context`)")
            }
        }

        /** Migration from version 1 to 2: add history_event table. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `history_event` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `event_type` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `primary_value` REAL NOT NULL,
                        `secondary_value` TEXT,
                        `metadata` TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_history_event_timestamp` ON `history_event` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_history_event_event_type` ON `history_event` (`event_type`)")
            }
        }
    }
}
