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
    version = 5,
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
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

        /** Binds a durable run to the remote A2A task it started so a restart resumes that task. */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agent_task_runs ADD COLUMN a2a_agent_id TEXT")
                db.execSQL("ALTER TABLE agent_task_runs ADD COLUMN a2a_task_id TEXT")
                db.execSQL("ALTER TABLE agent_task_runs ADD COLUMN a2a_context_id TEXT")
                db.execSQL("ALTER TABLE agent_task_runs ADD COLUMN a2a_message_id TEXT")
                db.execSQL("ALTER TABLE agent_task_runs ADD COLUMN a2a_message_seq INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX index_agent_task_runs_a2a_agent_id_a2a_task_id ON agent_task_runs(a2a_agent_id, a2a_task_id)")
            }
        }

        /** Persist bounded A2A wait and the one-time user reconciliation decision. */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agent_task_runs ADD COLUMN a2a_deadline_at INTEGER")
                db.execSQL("ALTER TABLE agent_task_runs ADD COLUMN a2a_review_decision TEXT")
                db.execSQL("UPDATE agent_task_runs SET a2a_deadline_at = updated_at + 86400000 WHERE state = 'WAITING_REMOTE'")
                db.execSQL("UPDATE agent_task_runs SET a2a_deadline_at = updated_at + 86400000 WHERE error_code = 'A2A_DISPATCH_UNKNOWN'")
                db.execSQL("UPDATE agent_task_runs SET state = 'A2A_REVIEW' WHERE error_code = 'A2A_DISPATCH_UNKNOWN' AND state = 'FAILED'")
            }
        }

        /** Adds a bounded workflow cursor so settled steps survive process death. */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE agent_task_runs ADD COLUMN workflow_checkpoint_json TEXT NOT NULL DEFAULT '{}'",
                )
            }
        }
    }
}
