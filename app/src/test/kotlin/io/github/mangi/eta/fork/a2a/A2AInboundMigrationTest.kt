package io.github.mangi.eta.fork.a2a

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class A2AInboundMigrationTest {
    @Test
    fun `migration 2 to 3 keeps waiting tasks and adds continuation storage`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "a2a-inbound-migration-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val path = context.getDatabasePath(databaseName)
        path.parentFile?.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { legacy ->
                createVersion2Schema(legacy)
                legacy.execSQL(
                    "INSERT INTO `a2a_inbound_tasks` " +
                        "(`taskId`,`requestMessageId`,`contextId`,`prompt`,`state`,`statusMessage`,`answer`,`createdAt`,`updatedAt`) " +
                        "VALUES ('waiting-task','waiting-message','waiting-context','original prompt','INPUT_REQUIRED',NULL,NULL,10,10)",
                )
                legacy.execSQL("INSERT INTO `a2a_inbound_tasks` " +
                    "(`taskId`,`requestMessageId`,`contextId`,`prompt`,`state`,`statusMessage`,`answer`,`createdAt`,`updatedAt`) " +
                    "VALUES ('active-task','active-message','active-context','active prompt','SUBMITTED',NULL,NULL,20,20)")
                legacy.execSQL("INSERT INTO `a2a_inbound_tasks` " +
                    "(`taskId`,`requestMessageId`,`contextId`,`prompt`,`state`,`statusMessage`,`answer`,`createdAt`,`updatedAt`) " +
                    "VALUES ('crashed-task','crashed-message','crashed-context','crashed prompt','WORKING',NULL,NULL,25,25)")
                legacy.version = 2
            }

            val database = Room.databaseBuilder(context, A2ADatabase::class.java, databaseName)
                .addMigrations(A2ADatabase.MIGRATION_2_3)
                .addMigrations(A2ADatabase.MIGRATION_3_4)
                .addMigrations(A2ADatabase.MIGRATION_4_5)
                .allowMainThreadQueries()
                .build()
            try {
                val dao = database.dao()
                val waiting = requireNotNull(dao.inboundTask("waiting-task"))
                assertEquals(A2ATaskState.INPUT_REQUIRED.name, waiting.state)
                assertEquals("original prompt", waiting.prompt)
                assertEquals(0, waiting.continuationCount)
                assertEquals(null, waiting.continuationMessageId)
                // The v4 -> v5 ledger columns start empty, so no approval can be replayed.
                assertEquals(null, waiting.approvalSummaryJson)
                assertEquals(null, waiting.approvalSummaryDigest)
                assertEquals(0, waiting.approvalSummaryChars)
                assertEquals(A2ATaskState.SUBMITTED.name, requireNotNull(dao.inboundTask("active-task")).state)

                dao.insertInboundContinuation(
                    A2AInboundContinuationEntity(
                        messageId = "migrated-follow-up",
                        taskId = "waiting-task",
                        contextId = "waiting-context",
                        input = "supplement",
                        outcome = "ACCEPTED",
                        acceptedAt = 30,
                    ),
                )
                assertEquals("waiting-task", requireNotNull(dao.inboundContinuation("migrated-follow-up")).taskId)
                dao.putInboundApproval(
                    A2AInboundApprovalEntity(
                        taskId = "waiting-task",
                        messageId = "migrated-follow-up",
                        originalGrantFingerprint = "original",
                        currentGrantFingerprint = "current",
                        decision = "APPROVED",
                        decidedAt = 31,
                    ),
                )
                assertEquals("APPROVED", requireNotNull(dao.inboundApproval("waiting-task")).decision)

                val handler = PersistentA2AInboundTaskHandler(
                    dao = dao,
                    executor = object : A2AInboundExecutor {
                        override suspend fun execute(request: A2AInboundExecutionRequest): String =
                            if (request.taskId == "active-task") "active answer"
                            else error("a waiting or crashed task must not execute during recovery")
                    },
                )
                assertEquals(3, handler.recoverPendingTasks())
                kotlinx.coroutines.withTimeout(5_000) {
                    while (dao.inboundTask("active-task")?.state != A2ATaskState.COMPLETED.name) {
                        kotlinx.coroutines.delay(25)
                    }
                }
                assertEquals(A2ATaskState.COMPLETED.name, requireNotNull(dao.inboundTask("active-task")).state)
                assertEquals(A2ATaskState.INPUT_REQUIRED.name, requireNotNull(dao.inboundTask("waiting-task")).state)
                // A WORKING row left by a previous process has no verifiable pause identity.
                assertEquals(A2ATaskState.FAILED.name, requireNotNull(dao.inboundTask("crashed-task")).state)
                handler.close()
            } finally {
                database.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    /** Exact version-2 schema emitted by the Room schema before this migration. */
    private fun createVersion2Schema(database: SQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `a2a_agents` (`id` TEXT NOT NULL, `displayName` TEXT NOT NULL, " +
                "`cardUrl` TEXT NOT NULL, `endpointUrl` TEXT NOT NULL, `tenant` TEXT, `protocolVersion` TEXT NOT NULL, " +
                "`authSecretAlias` TEXT, `trustState` TEXT NOT NULL, `cardJson` TEXT NOT NULL, `enabled` INTEGER NOT NULL, " +
                "`discoveredAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_a2a_agents_cardUrl` ON `a2a_agents` (`cardUrl`)",
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `a2a_tasks` (`localId` TEXT NOT NULL, `agentId` TEXT NOT NULL, " +
                "`remoteTaskId` TEXT, `remoteContextId` TEXT, `state` TEXT NOT NULL, `cancelRequestedAt` INTEGER, " +
                "`cancelUnconfirmed` INTEGER NOT NULL, `lastEventKey` TEXT, `errorCode` TEXT, `errorMessage` TEXT, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`localId`), " +
                "FOREIGN KEY(`agentId`) REFERENCES `a2a_agents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_a2a_tasks_agentId` ON `a2a_tasks` (`agentId`)")
        database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_a2a_tasks_agentId_remoteTaskId` " +
                "ON `a2a_tasks` (`agentId`, `remoteTaskId`)",
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_a2a_tasks_remoteContextId` ON `a2a_tasks` (`remoteContextId`)",
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_a2a_tasks_updatedAt` ON `a2a_tasks` (`updatedAt`)")
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `a2a_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`localTaskId` TEXT NOT NULL, `eventKey` TEXT NOT NULL, `kind` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, " +
                "`receivedAt` INTEGER NOT NULL, FOREIGN KEY(`localTaskId`) REFERENCES `a2a_tasks`(`localId`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_a2a_events_localTaskId` ON `a2a_events` (`localTaskId`)")
        database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_a2a_events_localTaskId_eventKey` " +
                "ON `a2a_events` (`localTaskId`, `eventKey`)",
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS `index_a2a_events_receivedAt` ON `a2a_events` (`receivedAt`)")
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `a2a_artifacts` (`localTaskId` TEXT NOT NULL, `artifactId` TEXT NOT NULL, " +
                "`name` TEXT, `mediaTypeSummary` TEXT, `rawJson` TEXT NOT NULL, `quarantineState` TEXT NOT NULL, " +
                "`receivedAt` INTEGER NOT NULL, PRIMARY KEY(`localTaskId`, `artifactId`), " +
                "FOREIGN KEY(`localTaskId`) REFERENCES `a2a_tasks`(`localId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_a2a_artifacts_localTaskId` ON `a2a_artifacts` (`localTaskId`)",
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_a2a_artifacts_quarantineState` ON `a2a_artifacts` (`quarantineState`)",
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `a2a_inbound_tasks` (`taskId` TEXT NOT NULL, `requestMessageId` TEXT NOT NULL, " +
                "`contextId` TEXT NOT NULL, `prompt` TEXT NOT NULL, `state` TEXT NOT NULL, `statusMessage` TEXT, " +
                "`answer` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`taskId`))",
        )
        database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_a2a_inbound_tasks_requestMessageId` " +
                "ON `a2a_inbound_tasks` (`requestMessageId`)",
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_a2a_inbound_tasks_contextId` ON `a2a_inbound_tasks` (`contextId`)",
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_a2a_inbound_tasks_updatedAt` ON `a2a_inbound_tasks` (`updatedAt`)",
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `a2a_inbound_artifacts` (`taskId` TEXT NOT NULL, `artifactId` TEXT NOT NULL, " +
                "`rawJson` TEXT NOT NULL, `receivedAt` INTEGER NOT NULL, PRIMARY KEY(`taskId`, `artifactId`), " +
                "FOREIGN KEY(`taskId`) REFERENCES `a2a_inbound_tasks`(`taskId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_a2a_inbound_artifacts_taskId` ON `a2a_inbound_artifacts` (`taskId`)",
        )
    }
}
