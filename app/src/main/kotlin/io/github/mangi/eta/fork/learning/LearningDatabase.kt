package io.github.mangi.eta.fork.learning

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.mangi.eta.agent.memory.MemoryEntry
import io.github.mangi.eta.agent.memory.MemoryJob
import io.github.mangi.eta.agent.memory.MemoryAutomationDao

@Database(
    entities = [
        LearningCandidateEntity::class,
        LearningEvidenceEntity::class,
        LearningRuleEntity::class,
        LearningRuleVersionEntity::class,
        LearningRuleChangeEntity::class,
        LearningFeedbackEntity::class,
        MemoryEntry::class,
        MemoryJob::class,
        io.github.mangi.eta.agent.skill.SkillExperience::class,
        io.github.mangi.eta.agent.skill.SkillRecipe::class,
        io.github.mangi.eta.agent.skill.SkillRecipeVersion::class,
    ],
    version = 5,
    exportSchema = false,
)
internal abstract class LearningDatabase : RoomDatabase() {
    abstract fun learningDao(): LearningDao
    abstract fun memoryDao(): MemoryAutomationDao
    abstract fun skillEvolutionDao(): io.github.mangi.eta.agent.skill.SkillEvolutionDao

    companion object {
        @Volatile
        private var instance: LearningDatabase? = null

        fun get(context: Context): LearningDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LearningDatabase::class.java,
                    "eta_learning.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { instance = it }
            }

        @VisibleForTesting
        internal fun closeForTests() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }

        // Never infer ownership from legacy prose/source_ref. Preserve every row and version.
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE learning_candidates ADD COLUMN scope TEXT NOT NULL DEFAULT 'global'")
                db.execSQL("ALTER TABLE learning_rules ADD COLUMN scope TEXT NOT NULL DEFAULT 'global'")
                db.execSQL("DROP INDEX IF EXISTS index_learning_candidates_content_hash")
                db.execSQL("CREATE UNIQUE INDEX index_learning_candidates_scope_content_hash ON learning_candidates(scope,content_hash)")
                db.execSQL("DROP INDEX IF EXISTS index_learning_rules_kind_title")
                db.execSQL("CREATE UNIQUE INDEX index_learning_rules_scope_kind_title ON learning_rules(scope,kind,title)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE skill_experiences (runId TEXT NOT NULL PRIMARY KEY, scope TEXT NOT NULL, digest TEXT NOT NULL, tools TEXT NOT NULL, createdAt INTEGER NOT NULL, state TEXT NOT NULL, recipeId TEXT NOT NULL, detail TEXT NOT NULL)")
                db.execSQL("CREATE TABLE skill_recipes (id TEXT NOT NULL PRIMARY KEY, scope TEXT NOT NULL, title TEXT NOT NULL, description TEXT NOT NULL, body TEXT NOT NULL, tools TEXT NOT NULL, state TEXT NOT NULL, version INTEGER NOT NULL, revision TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE skill_recipe_versions (recipeId TEXT NOT NULL, version INTEGER NOT NULL, title TEXT NOT NULL, description TEXT NOT NULL, body TEXT NOT NULL, tools TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(recipeId, version))")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS learning_rule_changes (id TEXT NOT NULL PRIMARY KEY, rule_id TEXT NOT NULL, rule_version INTEGER NOT NULL, change_type TEXT NOT NULL, source_ref TEXT NOT NULL, previous_content TEXT NOT NULL, archived_rules TEXT NOT NULL, created_at INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_learning_rule_changes_rule_id ON learning_rule_changes(rule_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_learning_rule_changes_created_at ON learning_rule_changes(created_at)")
                db.execSQL("ALTER TABLE learning_rule_versions ADD COLUMN source_ref TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS memory_entries (id TEXT NOT NULL PRIMARY KEY, scope TEXT NOT NULL, layer TEXT NOT NULL, topic TEXT NOT NULL, content TEXT NOT NULL, evidence TEXT NOT NULL, source TEXT NOT NULL, fingerprint TEXT NOT NULL, status TEXT NOT NULL, confidence REAL NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, expiresAt INTEGER, conflictIds TEXT NOT NULL, supersedes TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_entries_scope ON memory_entries(scope)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_entries_status ON memory_entries(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_entries_fingerprint ON memory_entries(fingerprint)")
                db.execSQL("CREATE TABLE IF NOT EXISTS memory_jobs (id TEXT NOT NULL PRIMARY KEY, source TEXT NOT NULL, scopes TEXT NOT NULL, text TEXT NOT NULL, createdAt INTEGER NOT NULL, status TEXT NOT NULL, attempts INTEGER NOT NULL, updatedAt INTEGER NOT NULL, detail TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_jobs_status ON memory_jobs(status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_jobs_createdAt ON memory_jobs(createdAt)")
            }
        }
    }
}
