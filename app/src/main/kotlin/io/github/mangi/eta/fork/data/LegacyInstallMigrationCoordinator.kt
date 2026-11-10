package io.github.mangi.eta.fork.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.annotation.VisibleForTesting
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Bridges an in-place upgrade from the original fork package to the current upstream database layout.
 *
 * The legacy files are recovery sources and are never deleted. The database is copied through a
 * staging file, integrity-checked, synced, and then atomically published. Room runs the explicit
 * v8 -> v24 migrations after this coordinator has finished.
 */
internal object LegacyInstallMigrationCoordinator {
    @VisibleForTesting
    internal const val LEGACY_DATABASE_NAME = "fuck_andes.db"
    @VisibleForTesting
    internal const val DATABASE_NAME = "eta.db"
    @VisibleForTesting
    internal const val LEGACY_SETTINGS_NAME = "fuck_andes_settings.preferences_pb"
    @VisibleForTesting
    internal const val SETTINGS_NAME = "eta_settings.preferences_pb"

    private val migrationLock = Any()

    fun migrateIfNeeded(context: Context) {
        synchronized(migrationLock) {
            val appContext = context.applicationContext
            migrateDatabase(appContext)
            migrateSettings(appContext)
        }
    }

    private fun migrateDatabase(context: Context) {
        val legacy = context.getDatabasePath(LEGACY_DATABASE_NAME)
        val target = context.getDatabasePath(DATABASE_NAME)
        if (!legacy.isFile || target.exists()) return

        target.parentFile?.mkdirs()
        val staging = File(target.parentFile, "$DATABASE_NAME.migrating")
        Files.deleteIfExists(staging.toPath())

        checkpointAndVerify(legacy)
        Files.copy(legacy.toPath(), staging.toPath(), StandardCopyOption.REPLACE_EXISTING)
        syncFile(staging)
        verifyStagedDatabase(staging)
        publish(staging, target)
    }

    private fun migrateSettings(context: Context) {
        val directory = File(context.filesDir, "datastore")
        val legacy = File(directory, LEGACY_SETTINGS_NAME)
        val target = File(directory, SETTINGS_NAME)
        if (!legacy.isFile || legacy.length() == 0L || target.exists()) return

        directory.mkdirs()
        val staging = File(directory, "$SETTINGS_NAME.migrating")
        Files.deleteIfExists(staging.toPath())
        Files.copy(legacy.toPath(), staging.toPath(), StandardCopyOption.REPLACE_EXISTING)
        syncFile(staging)
        publish(staging, target)
    }

    private fun checkpointAndVerify(databaseFile: File) {
        SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        ).use { database ->
            database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                if (cursor.moveToFirst()) {
                    val busy = cursor.getInt(0)
                    check(busy == 0) { "Legacy database checkpoint is busy" }
                }
            }
            requireIntegrity(database)
        }
    }

    private fun verifyStagedDatabase(databaseFile: File) {
        SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        ).use(::requireIntegrity)
    }

    private fun requireIntegrity(database: SQLiteDatabase) {
        database.rawQuery("PRAGMA integrity_check", null).use { cursor ->
            check(cursor.moveToFirst()) { "Legacy database integrity check returned no result" }
            val result = cursor.getString(0)
            check(result == "ok") { "Legacy database integrity check failed: $result" }
        }
    }

    private fun syncFile(file: File) {
        RandomAccessFile(file, "rw").use { it.fd.sync() }
    }

    private fun publish(staging: File, target: File) {
        Files.move(
            staging.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
        )
    }
}
