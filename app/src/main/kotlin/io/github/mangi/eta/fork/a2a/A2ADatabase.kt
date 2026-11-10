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
        A2AInboundArtifactEntity::class,
    ],
    version = 2,
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
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
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

        @VisibleForTesting
        internal fun closeForTests() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}
