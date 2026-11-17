package io.github.mangi.eta.fork.a2a

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        A2AAgentEntity::class,
        A2ATaskEntity::class,
        A2AEventEntity::class,
        A2AArtifactEntity::class,
        A2AInboundTaskEntity::class,
        A2AInboundContinuationEntity::class,
        A2AInboundArtifactEntity::class,
        A2AInboundApprovalEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
internal abstract class A2ADatabase : RoomDatabase() {
    abstract fun dao(): A2ADao

    companion object {
        @Volatile private var instance: A2ADatabase? = null

        fun get(context: Context): A2ADatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                A2ADatabase::class.java,
                "eta_a2a.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .enableMultiInstanceInvalidation()
                .build()
                .also { instance = it }
        }

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `a2a_inbound_tasks` (
                        `taskId` TEXT NOT NULL,
                        `requestMessageId` TEXT NOT NULL,
                        `contextId` TEXT NOT NULL,
                        `prompt` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `statusMessage` TEXT,
                        `answer` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`taskId`)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_a2a_inbound_tasks_requestMessageId` " +
                        "ON `a2a_inbound_tasks` (`requestMessageId`)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_a2a_inbound_tasks_contextId` " +
                        "ON `a2a_inbound_tasks` (`contextId`)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_a2a_inbound_tasks_updatedAt` " +
                        "ON `a2a_inbound_tasks` (`updatedAt`)",
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `a2a_inbound_artifacts` (
                        `taskId` TEXT NOT NULL,
                        `artifactId` TEXT NOT NULL,
                        `rawJson` TEXT NOT NULL,
                        `receivedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`taskId`, `artifactId`),
                        FOREIGN KEY(`taskId`) REFERENCES `a2a_inbound_tasks`(`taskId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_a2a_inbound_artifacts_taskId` " +
                        "ON `a2a_inbound_artifacts` (`taskId`)",
                )
            }
        }

        /** Adds durable INPUT_REQUIRED/AUTH_REQUIRED waits and idempotent continuation records. */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `a2a_inbound_tasks` ADD COLUMN `continuationMessageId` TEXT",
                )
                database.execSQL(
                    "ALTER TABLE `a2a_inbound_tasks` ADD COLUMN `continuationInput` TEXT",
                )
                database.execSQL(
                    "ALTER TABLE `a2a_inbound_tasks` ADD COLUMN `continuationCount` INTEGER NOT NULL DEFAULT 0",
                )
                database.execSQL(
                    "ALTER TABLE `a2a_inbound_tasks` ADD COLUMN `accessJson` TEXT",
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `a2a_inbound_continuations` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `messageId` TEXT NOT NULL,
                        `taskId` TEXT NOT NULL,
                        `contextId` TEXT NOT NULL,
                        `input` TEXT NOT NULL,
                        `outcome` TEXT NOT NULL,
                        `acceptedAt` INTEGER NOT NULL,
                        FOREIGN KEY(`taskId`) REFERENCES `a2a_inbound_tasks`(`taskId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_a2a_inbound_continuations_taskId` " +
                        "ON `a2a_inbound_continuations` (`taskId`)",
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_a2a_inbound_continuations_messageId` " +
                        "ON `a2a_inbound_continuations` (`messageId`)",
                )
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `a2a_inbound_approvals` (
                        `taskId` TEXT NOT NULL,
                        `messageId` TEXT NOT NULL,
                        `originalGrantFingerprint` TEXT NOT NULL,
                        `currentGrantFingerprint` TEXT NOT NULL,
                        `decision` TEXT NOT NULL,
                        `decidedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`taskId`)
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_a2a_inbound_approvals_taskId` ON `a2a_inbound_approvals` (`taskId`)")
            }
        }

        /**
         * Adds the bounded local resume ledger of a paused inbound turn. It stores only the exact
         * pending call identity and settled-call digests, never transcript text or arguments.
         * Old rows keep NULL, so an owner approval fails closed instead of replaying the turn.
         */
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `a2a_inbound_tasks` ADD COLUMN `approvalSummaryJson` TEXT")
                db.execSQL("ALTER TABLE `a2a_inbound_tasks` ADD COLUMN `approvalSummaryDigest` TEXT")
                db.execSQL(
                    "ALTER TABLE `a2a_inbound_tasks` ADD COLUMN `approvalSummaryChars` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        @VisibleForTesting
        internal fun closeForTests() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}
