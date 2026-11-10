package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.memory.MemoryReviewRepository
import io.github.mangi.eta.agent.memory.MemoryJob
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.fork.learning.LearningDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MemoryBackupTest {
    private val context = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        EtaDatabase.closeForTests(); context.deleteDatabase("eta.db")
        LearningDatabase.closeForTests(); context.deleteDatabase("eta_learning.db")
        SettingsDataStore.init(context); ProviderRepository.init(context); AgentMemoryRepository.init(context)
        runBlocking { SettingsDataStore.setSelection(null, null); SettingsDataStore.setOfficialModelCatalogRevision(0) }
    }
    @After fun close() { LearningDatabase.closeForTests(); EtaDatabase.closeForTests() }

    @Test fun structuredEntriesRoundTripWithScopeEvidenceExpiryAndReviewState() = runBlocking(Dispatchers.IO) {
        val db = LearningDatabase.get(context)
        val repo = MemoryReviewRepository(db) { 1000 }
        val pending = requireNotNull(repo.propose(AgentMemoryScope.project("sample"), "project", "项目框架",
            "项目使用 Kotlin 和 Compose", "项目使用 Kotlin 和 Compose", "conversation:sample"))
        val active = requireNotNull(repo.propose(AgentMemoryScope.task("sample"), "task", "验收标准",
            "本阶段只验证记忆功能", "本阶段只验证记忆功能", "task:sample", 3000))
        repo.decide(active.id, active.updatedAt, "confirm")
        val expected = db.memoryDao().entries().sortedBy { it.id }
        val output = ByteArrayOutputStream()
        EtaBackupRepository.export(context, output)
        val doc = Json.decodeFromString<EtaBackupDocument>(output.toString("UTF-8"))
        assertEquals(expected, doc.memoryEntries.sortedBy { it.id })
        db.memoryDao().clearEntries()
        db.memoryDao().insertJob(MemoryJob("old-source", "conversation:old", "global", "old private text", 1000))
        EtaBackupRepository.import(context, ByteArrayInputStream(output.toByteArray()))
        assertEquals(expected, db.memoryDao().entries().sortedBy { it.id })
        assertTrue(db.memoryDao().jobs().isEmpty())
        assertEquals("butler_approved", db.memoryDao().entry(pending.id)?.status)
        assertEquals("active", db.memoryDao().entry(active.id)?.status)
    }

    @Test fun invalidMemoryMetadataIsRejectedBeforeReplacingExistingData() = runBlocking(Dispatchers.IO) {
        val db = LearningDatabase.get(context)
        val repo = MemoryReviewRepository(db) { 1000 }
        val original = requireNotNull(repo.propose(AgentMemoryScope.GLOBAL, "core", "回复语言",
            "以后请使用中文回答", "以后请使用中文回答", "conversation:a"))
        val dao = EtaDatabase.get(context).conversationDao()
        dao.insertConversations(listOf(ConversationEntity("keep", "keep", false, createdAt = 1, updatedAt = 1)))
        val invalid = listOf(original.copy(scope = "project:../outside"), original.copy(status = "unreviewed-active"),
            original.copy(updatedAt = 0), original.copy(expiresAt = 0), original.copy(topic = " "), original.copy(fingerprint = "bad"))
        for (entry in invalid) {
            val document = EtaBackupDocument(exportedAt = 0, memoryEntries = listOf(entry))
            try {
                EtaBackupRepository.import(context, ByteArrayInputStream(Json.encodeToString(document).toByteArray()))
                fail("Invalid memory accepted: $entry")
            } catch (_: EtaBackupException) { }
            assertEquals(listOf(original), db.memoryDao().entries())
            assertEquals(listOf("keep"), dao.conversationIdentities().map { it.id })
        }
    }
}
