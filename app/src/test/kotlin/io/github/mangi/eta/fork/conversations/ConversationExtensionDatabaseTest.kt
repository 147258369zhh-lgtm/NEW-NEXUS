package io.github.mangi.eta.fork.conversations

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ConversationExtensionDatabaseTest {
    private lateinit var context: Context
    private lateinit var database: ConversationExtensionDatabase
    private val databaseName = "eta_fork_migration_test.db"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        context.deleteDatabase(databaseName)
        ConversationExtensionDatabase.closeForTests()
    }

    @Test
    fun migration_from_v1_preserves_rows_and_adds_archive_tables() = runBlocking {
        createVersionOneDatabase()

        database = Room.databaseBuilder(
            context,
            ConversationExtensionDatabase::class.java,
            databaseName,
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()
        val dao = database.conversationExtensionDao()

        val folder = dao.folder("folder-legacy")!!
        assertEquals("Legacy folder", folder.name)
        assertEquals(3L, folder.sortOrder)
        assertEquals(10L, folder.createdAt)
        assertEquals(20L, folder.updatedAt)
        assertNull(folder.parentId)
        val extension = dao.extension("legacy")!!
        assertEquals("Legacy conversation", "legacy", extension.conversationId)
        assertEquals("folder-legacy", extension.folderId)
        assertEquals(7L, extension.folderSortOrder)
        assertFalse(extension.autoArchiveExempt)
        assertNull(extension.archivedAt)
        assertNull(extension.contextWindowOverride)

        dao.upsertArchiveSettings(ConversationArchiveSettingsEntity(enabled = false, idleDays = 14))
        assertEquals(14, dao.archiveSettings()!!.idleDays)
        dao.insertArchiveEvent(
            ConversationArchiveEventEntity(
                conversationId = "legacy",
                type = "archived",
                reason = IDLE_ARCHIVE_REASON,
                isAutomatic = true,
                createdAt = 42L,
            ),
        )
    }

    @Test
    fun migrationFromV2PreservesArchiveDataAndNestedFoldersPersistAcrossReopen() = runBlocking {
        createVersionOneDatabase(version = 2)
        fun open() = Room.databaseBuilder(context, ConversationExtensionDatabase::class.java, databaseName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).allowMainThreadQueries().build()
        database = open()
        val dao = database.conversationExtensionDao()
        assertEquals("Legacy folder", dao.folder("folder-legacy")!!.name)
        assertNull(dao.folder("folder-legacy")!!.parentId)
        assertEquals(123L, dao.extension("legacy")!!.archivedAt)
        assertEquals(true, dao.extension("legacy")!!.autoArchiveExempt)
        assertEquals(14, dao.archiveSettings()!!.idleDays)
        dao.insertFolder(ConversationFolderEntity("child", "Child", 4L, 30L, 40L, "folder-legacy"))
        database.close()
        database = open()
        assertEquals("folder-legacy", database.conversationExtensionDao().folder("child")!!.parentId)
        assertEquals(123L, database.conversationExtensionDao().extension("legacy")!!.archivedAt)
    }

    private fun createVersionOneDatabase(version: Int = 1) {
        val file = File(context.getDatabasePath(databaseName).path)
        file.parentFile?.mkdirs()
        val sqlite = SQLiteDatabase.openOrCreateDatabase(file, null)
        sqlite.execSQL(
            "CREATE TABLE conversation_folders (" +
                "id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, sort_order INTEGER NOT NULL, " +
                "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
        )
        sqlite.execSQL("CREATE UNIQUE INDEX index_conversation_folders_name ON conversation_folders(name)")
        sqlite.execSQL("CREATE INDEX index_conversation_folders_sort_order ON conversation_folders(sort_order)")
        sqlite.execSQL(
            "CREATE TABLE conversation_extensions (" +
                "conversation_id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, folder_id TEXT, " +
                "folder_sort_order INTEGER NOT NULL, last_active_at INTEGER NOT NULL, archived_at INTEGER, " +
                "archive_reason TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
        )
        sqlite.execSQL(
            "CREATE INDEX index_conversation_extensions_folder_id_folder_sort_order " +
                "ON conversation_extensions(folder_id, folder_sort_order)",
        )
        sqlite.execSQL("CREATE INDEX index_conversation_extensions_archived_at ON conversation_extensions(archived_at)")
        sqlite.execSQL("CREATE INDEX index_conversation_extensions_last_active_at ON conversation_extensions(last_active_at)")
        sqlite.execSQL(
            "INSERT INTO conversation_extensions " +
                "(conversation_id, kind, folder_id, folder_sort_order, last_active_at, archived_at, archive_reason, created_at, updated_at) " +
                "VALUES ('legacy', 'chat', 'folder-legacy', 7, 100, NULL, NULL, 10, 20)",
        )
        sqlite.execSQL("INSERT INTO conversation_folders VALUES ('folder-legacy', 'Legacy folder', 3, 10, 20)")
        if (version == 2) {
            sqlite.execSQL("ALTER TABLE conversation_extensions ADD COLUMN auto_archive_exempt INTEGER NOT NULL DEFAULT 0")
            sqlite.execSQL("UPDATE conversation_extensions SET archived_at = 123, auto_archive_exempt = 1 WHERE conversation_id = 'legacy'")
            sqlite.execSQL("CREATE TABLE conversation_archive_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, conversation_id TEXT NOT NULL, type TEXT NOT NULL, reason TEXT NOT NULL, is_automatic INTEGER NOT NULL, created_at INTEGER NOT NULL)")
            sqlite.execSQL("CREATE INDEX index_conversation_archive_events_conversation_id_created_at ON conversation_archive_events(conversation_id, created_at)")
            sqlite.execSQL("CREATE TABLE conversation_archive_settings (id INTEGER NOT NULL PRIMARY KEY, enabled INTEGER NOT NULL, idle_days INTEGER NOT NULL, exempt_pinned INTEGER NOT NULL, exempt_meetings INTEGER NOT NULL, exempt_running INTEGER NOT NULL, last_checked_at INTEGER, last_error TEXT)")
            sqlite.execSQL("INSERT INTO conversation_archive_settings VALUES (0, 1, 14, 1, 1, 1, NULL, NULL)")
        }
        sqlite.version = version
        sqlite.close()
    }
}
