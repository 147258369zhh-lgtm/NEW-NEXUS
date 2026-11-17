package io.github.mangi.eta.fork.tasks

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentTaskDatabaseMigrationTest {
    @Test
    fun migration3To4AddsDeadlineAndDecisionAndPreservesReviewableRows() {
        val context = RuntimeEnvironment.getApplication()
        val name = "migration-3-4-test.db"
        context.deleteDatabase(name)
        val callback = object : SupportSQLiteOpenHelper.Callback(3) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE agent_task_runs (id TEXT NOT NULL PRIMARY KEY, state TEXT NOT NULL, error_code TEXT, updated_at INTEGER NOT NULL)")
                db.execSQL("INSERT INTO agent_task_runs(id,state,error_code,updated_at) VALUES ('waiting','WAITING_REMOTE',NULL,1000)")
                db.execSQL("INSERT INTO agent_task_runs(id,state,error_code,updated_at) VALUES ('unknown','FAILED','A2A_DISPATCH_UNKNOWN',2000)")
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(callback)
                .build(),
        )
        try {
            val db = helper.writableDatabase
            AgentTaskDatabase.MIGRATION_3_4.migrate(db)
            db.query("SELECT id,state,a2a_deadline_at,a2a_review_decision FROM agent_task_runs ORDER BY id").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("unknown", cursor.getString(0))
                assertEquals("A2A_REVIEW", cursor.getString(1))
                assertEquals(86_402_000L, cursor.getLong(2))
                assertEquals("", cursor.getString(3) ?: "")
                assertTrue(cursor.moveToNext())
                assertEquals("waiting", cursor.getString(0))
                assertEquals("WAITING_REMOTE", cursor.getString(1))
                assertEquals(86_401_000L, cursor.getLong(2))
                assertTrue(cursor.isNull(3))
            }
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun migration4To5AddsEmptyWorkflowCheckpointWithoutChangingRuns() {
        val context = RuntimeEnvironment.getApplication()
        val name = "migration-4-5-test.db"
        context.deleteDatabase(name)
        val callback = object : SupportSQLiteOpenHelper.Callback(4) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE agent_task_runs (id TEXT NOT NULL PRIMARY KEY, state TEXT NOT NULL, updated_at INTEGER NOT NULL)")
                db.execSQL("INSERT INTO agent_task_runs(id,state,updated_at) VALUES ('queued','QUEUED',1000)")
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(callback)
                .build(),
        )
        try {
            val db = helper.writableDatabase
            AgentTaskDatabase.MIGRATION_4_5.migrate(db)
            db.query("SELECT id,state,workflow_checkpoint_json FROM agent_task_runs").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("queued", cursor.getString(0))
                assertEquals("QUEUED", cursor.getString(1))
                assertEquals("{}", cursor.getString(2))
            }
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }
}
