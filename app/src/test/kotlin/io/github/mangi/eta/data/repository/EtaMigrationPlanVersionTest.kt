package io.github.mangi.eta.data.repository

import android.app.Application
import io.github.mangi.eta.fork.conversations.ConversationExtensionDatabase
import io.github.mangi.eta.fork.learning.LearningDatabase
import io.github.mangi.eta.fork.meeting.MeetingDatabase
import io.github.mangi.eta.fork.tasks.AgentTaskDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36, 37], application = Application::class)
class EtaMigrationPlanVersionTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @After fun close() {
        io.github.mangi.eta.data.db.EtaDatabase.closeForTests()
        ConversationExtensionDatabase.closeForTests()
        MeetingDatabase.closeForTests()
        LearningDatabase.closeForTests()
        AgentTaskDatabase.closeForTests()
        io.github.mangi.eta.fork.projects.ProjectDatabase.closeForTests()
        io.github.mangi.eta.fork.a2a.A2ADatabase.closeForTests()
        io.github.mangi.eta.agent.runtime.AgentMetricsDatabase.closeForTests()
    }

    @Test fun migrationInventoryMatchesActualRoomVersionsThatRecentlyChanged() {
        val core = io.github.mangi.eta.data.db.EtaDatabase.get(context).openHelper.readableDatabase.version
        assertEquals(core, EtaMigrationPlan.entries.single { it.name == "core" }.currentVersion)
        assertEquals(core, EtaMigrationPlan.entries.single { it.name == "mcp_skills" }.currentVersion)
        assertEquals(
            ConversationExtensionDatabase.get(context).openHelper.readableDatabase.version,
            EtaMigrationPlan.entries.single { it.databaseName == "eta_fork.db" }.currentVersion,
        )
        assertEquals(
            MeetingDatabase.get(context).openHelper.readableDatabase.version,
            EtaMigrationPlan.entries.single { it.databaseName == "eta_meeting.db" }.currentVersion,
        )
        assertEquals(
            LearningDatabase.get(context).openHelper.readableDatabase.version,
            EtaMigrationPlan.entries.single { it.databaseName == "eta_learning.db" }.currentVersion,
        )
        assertEquals(
            AgentTaskDatabase.get(context).openHelper.readableDatabase.version,
            EtaMigrationPlan.entries.single { it.databaseName == "eta_task.db" }.currentVersion,
        )
        assertEquals(
            io.github.mangi.eta.fork.projects.ProjectDatabase.get(context).openHelper.readableDatabase.version,
            EtaMigrationPlan.entries.single { it.databaseName == "eta_projects.db" }.currentVersion,
        )
        assertEquals(
            io.github.mangi.eta.fork.a2a.A2ADatabase.get(context).openHelper.readableDatabase.version,
            EtaMigrationPlan.entries.single { it.databaseName == "eta_a2a.db" }.currentVersion,
        )
        assertEquals(
            io.github.mangi.eta.agent.runtime.AgentMetricsDatabase.get(context).openHelper.readableDatabase.version,
            EtaMigrationPlan.entries.single { it.databaseName == "eta_run_metrics.db" }.currentVersion,
        )
    }
}
