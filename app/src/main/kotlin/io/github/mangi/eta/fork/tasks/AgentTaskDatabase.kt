package io.github.mangi.eta.fork.tasks

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AgentTaskDefinitionEntity::class,
        AgentTaskScheduleEntity::class,
        AgentTaskRunEntity::class,
        AgentTaskLeaseEntity::class,
        AgentTaskEventEntity::class,
        AgentTaskAttentionEntity::class,
        AutomationTriggerEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
internal abstract class AgentTaskDatabase : RoomDatabase() {
    abstract fun agentTaskDao(): AgentTaskDao
    abstract fun triggerDao(): AutomationTriggerDao

    companion object {
        @Volatile
        private var instance: AgentTaskDatabase? = null

        fun get(context: Context): AgentTaskDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AgentTaskDatabase::class.java,
                    "eta_task.db",
                )
                    .addMigrations(MIGRATION_1_2).build()
                    .also { instance = it }
            }

        @VisibleForTesting
        internal fun closeForTests() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }

        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agent_task_schedules ADD COLUMN month_day INTEGER")
                db.execSQL("CREATE TABLE automation_triggers (id TEXT NOT NULL PRIMARY KEY, definitionId TEXT NOT NULL, type TEXT NOT NULL, source TEXT NOT NULL, extension TEXT NOT NULL, cooldownSeconds INTEGER NOT NULL, enabled INTEGER NOT NULL, cursor TEXT NOT NULL, lastFiredAt INTEGER NOT NULL, detail TEXT NOT NULL, FOREIGN KEY(definitionId) REFERENCES agent_task_definitions(id) ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX index_automation_triggers_definitionId ON automation_triggers(definitionId)")
            }
        }
    }
}
