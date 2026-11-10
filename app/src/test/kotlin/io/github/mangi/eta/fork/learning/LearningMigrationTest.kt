package io.github.mangi.eta.fork.learning

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import io.github.mangi.eta.agent.memory.MemoryJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36, 37], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LearningMigrationTest {
    private val context = RuntimeEnvironment.getApplication()
    @Before fun reset() { LearningDatabase.closeForTests(); context.deleteDatabase("eta_learning.db") }
    @After fun close() { LearningDatabase.closeForTests() }

    @Test fun versionOneRetainsRulesEvidenceAndVersionsWhenMemoryTablesAreAdded() = runBlocking(Dispatchers.IO) {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context).name("eta_learning.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE learning_candidates (id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL, content_hash TEXT NOT NULL, status TEXT NOT NULL, confidence REAL NOT NULL, source_ref TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, promoted_rule_id TEXT, confirmed_at INTEGER)")
                    db.execSQL("CREATE UNIQUE INDEX index_learning_candidates_content_hash ON learning_candidates(content_hash)")
                    db.execSQL("CREATE INDEX index_learning_candidates_status ON learning_candidates(status)")
                    db.execSQL("CREATE INDEX index_learning_candidates_updated_at ON learning_candidates(updated_at)")
                    db.execSQL("CREATE TABLE learning_evidence (id TEXT NOT NULL PRIMARY KEY, candidate_id TEXT NOT NULL, source_ref TEXT NOT NULL, excerpt_hash TEXT NOT NULL, excerpt TEXT NOT NULL, observed_at INTEGER NOT NULL, FOREIGN KEY(candidate_id) REFERENCES learning_candidates(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                    db.execSQL("CREATE INDEX index_learning_evidence_candidate_id ON learning_evidence(candidate_id)")
                    db.execSQL("CREATE INDEX index_learning_evidence_observed_at ON learning_evidence(observed_at)")
                    db.execSQL("CREATE TABLE learning_rules (id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL, current_version INTEGER NOT NULL, status TEXT NOT NULL, pinned INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
                    db.execSQL("CREATE UNIQUE INDEX index_learning_rules_kind_title ON learning_rules(kind,title)")
                    db.execSQL("CREATE INDEX index_learning_rules_status ON learning_rules(status)")
                    db.execSQL("CREATE TABLE learning_rule_versions (id TEXT NOT NULL PRIMARY KEY, rule_id TEXT NOT NULL, version INTEGER NOT NULL, content TEXT NOT NULL, candidate_id TEXT, reason TEXT NOT NULL, created_at INTEGER NOT NULL, FOREIGN KEY(rule_id) REFERENCES learning_rules(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                    db.execSQL("CREATE INDEX index_learning_rule_versions_rule_id ON learning_rule_versions(rule_id)")
                    db.execSQL("CREATE UNIQUE INDEX index_learning_rule_versions_rule_id_version ON learning_rule_versions(rule_id,version)")
                    db.execSQL("CREATE TABLE learning_feedback (id TEXT NOT NULL PRIMARY KEY, candidate_id TEXT NOT NULL, action TEXT NOT NULL, note TEXT NOT NULL, created_at INTEGER NOT NULL, FOREIGN KEY(candidate_id) REFERENCES learning_candidates(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                    db.execSQL("CREATE INDEX index_learning_feedback_candidate_id ON learning_feedback(candidate_id)")
                    db.execSQL("CREATE INDEX index_learning_feedback_created_at ON learning_feedback(created_at)")
                    db.execSQL("INSERT INTO learning_candidates VALUES ('candidate','procedure','发布检查','发布前运行全部测试','legacy-hash','promoted',0.7,'conversation:a',1,2,'rule',2)")
                    db.execSQL("INSERT INTO learning_evidence VALUES ('evidence','candidate','conversation:a','evidence-hash','发布前运行全部测试',1)")
                    db.execSQL("INSERT INTO learning_rules VALUES ('rule','procedure','发布检查','发布前运行全部测试',1,'active',1,1,2)")
                    db.execSQL("INSERT INTO learning_rule_versions VALUES ('version','rule',1,'发布前运行全部测试','candidate','confirmed-promotion',2)")
                    db.execSQL("INSERT INTO learning_feedback VALUES ('feedback','candidate','confirm','user-confirmed',2)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { it.writableDatabase }
        val db = LearningDatabase.get(context)
        val dao = db.learningDao()
        assertEquals("发布前运行全部测试", dao.activeRules().single().content)
        assertTrue(dao.activeRules().single().pinned)
        assertEquals("conversation:a", dao.evidence("candidate").single().sourceRef)
        assertEquals(1, dao.ruleVersions("rule").size)
        assertEquals(1, dao.feedback("candidate").size)
        assertEquals(3, db.openHelper.readableDatabase.version)
        assertTrue(db.skillEvolutionDao().recipes().isEmpty())
        db.memoryDao().insertJob(MemoryJob("job", "conversation:a", "global", "text", 3))
        assertEquals("job", db.memoryDao().nextJob()?.id)
        assertTrue(db.memoryDao().entries().isEmpty())
    }
}
