package io.github.mangi.eta.data.repository

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.RoomDatabase
import io.github.mangi.eta.agent.auth.EncryptedProviderCredentialStorage
import io.github.mangi.eta.agent.auth.ProviderCredential
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.core.safeStackTrace
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.agent.runtime.AgentMetricsDatabase
import io.github.mangi.eta.agent.runtime.AgentRunMetricsStore
import io.github.mangi.eta.fork.a2a.A2ADatabase
import io.github.mangi.eta.fork.a2a.A2AInboundConfigStore
import io.github.mangi.eta.fork.a2a.A2ASecretStore
import io.github.mangi.eta.fork.conversations.ConversationExtensionDatabase
import io.github.mangi.eta.fork.learning.LearningDatabase
import io.github.mangi.eta.fork.meeting.MeetingDatabase
import io.github.mangi.eta.fork.projects.ProjectDatabase
import io.github.mangi.eta.fork.tasks.AgentTaskDatabase
import io.github.mangi.eta.data.model.SpeechCredentials
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Versioned full backup archive.
 *
 * The logical JSON remains the compatibility contract. Complete app data is stored beside it under
 * `data/`, allowing databases, DataStore, SharedPreferences, Keystore ciphertext and workspace
 * artifacts to travel together. Import is staged and applied before Room/DataStore are opened.
 */
internal object EtaFullBackupArchive {
    const val SUFFIX = ".eta-backup"
    const val FORMAT = "eta-full-backup"
    const val SCHEMA_VERSION = 3

    private const val JSON_ENTRY = "eta-backup.json"
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val DATA_PREFIX = "data/"
    private const val CONTROL_DIR = "eta_restore_control"
    private const val MAX_ENTRIES = 200_000
    private const val MAX_ARCHIVE_BYTES = 8L * 1024L * 1024L * 1024L
    private const val MAX_MANIFEST_BYTES = 16L * 1024L * 1024L
    private val roots = listOf("databases", "shared_prefs", "files", "no_backup")
    private val excludedPaths = setOf(
        CONTROL_DIR,
        "files/eta-restore-staging",
        "files/eta-restore-rollback",
        "no_backup/eta-portable-secrets.json",
        "shared_prefs/eta_auto_backup_secret.xml",
        "no_backup/androidx.work.workdb",
        "no_backup/androidx.work.workdb-wal",
        "no_backup/androidx.work.workdb-shm",
        "no_backup/androidx.work.workdb-journal",
        "databases/androidx.work.workdb",
        "databases/androidx.work.workdb-wal",
        "databases/androidx.work.workdb-shm",
        "databases/androidx.work.workdb-journal",
    )
    private const val PORTABLE_SECRETS_ENTRY = "data/no_backup/eta-portable-secrets.json"
    private val guestLinkPath = Regex("^data/files/terminal(?:-user)?/proot/[^/]+/.+")
    private val drivePrefix = Regex("^[A-Za-z]:")

    private val json = EtaBackupJsonStreams.json

    /** Export a full archive. A non-empty password encrypts the entire ZIP container. */
    suspend fun export(
        context: Context,
        output: OutputStream,
        password: String? = null,
        onPhase: (String) -> Unit = {},
        onProgress: (EtaBackupProgress) -> Unit = {},
        includeAppFiles: Boolean = true,
        compression: EtaBackupCompression = EtaBackupCompression.COMPACT,
    ): EtaBackupSummary = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val secret = password?.takeIf(String::isNotEmpty)
        var phase = "checkpoint_databases"
        var privateBytes = 0L
        var privateFiles = 0
        var logicalRecords = 0L
        var lastUpdate = 0L
        fun progress(force: Boolean = false) {
            val now = System.nanoTime()
            if (force || now - lastUpdate >= 200_000_000L) {
                onProgress(EtaBackupProgress(phase, privateFiles, logicalRecords, privateBytes))
                lastUpdate = now
            }
        }
        try {
            onPhase(phase)
            progress(true)
            checkpointDatabases(appContext)
            val recordCounts = collectRecordCounts(appContext)
            val jsonFile = File(appContext.cacheDir, "eta-backup-${UUID.randomUUID()}.json")
            val secretsFile = File(appContext.cacheDir, "eta-secrets-${UUID.randomUUID()}.json")
            val entries = mutableListOf<EtaBackupEntryManifest>()
            val links = mutableListOf<EtaBackupLinkManifest>()
            val directories = mutableListOf<EtaBackupDirectoryManifest>()
            try {
                phase = "logical_snapshot"
                onPhase(phase)
                progress(true)
                val logical = jsonFile.outputStream().buffered().use {
                    EtaBackupRepository.export(appContext, it) { progressValue ->
                        phase = progressValue.phase
                        logicalRecords = progressValue.records
                        progress()
                    }
                }
                val sink = if (secret == null) output else EtaBackupEncryptedOutputStream(output, secret)
                // Buffer ZIP headers and deflater output; remote document streams are not byte sinks.
                val borrowed = object : OutputStream() {
                    override fun write(value: Int) = sink.write(value)
                    override fun write(buffer: ByteArray, offset: Int, length: Int) = sink.write(buffer, offset, length)
                    override fun flush() = sink.flush()
                    override fun close() = flush()
                }
                ZipOutputStream(borrowed.buffered(256 * 1024), Charsets.UTF_8).use { zip ->
                    zip.setLevel(
                        if (compression == EtaBackupCompression.FAST) Deflater.NO_COMPRESSION
                        else Deflater.BEST_SPEED,
                    )
                    phase = "logical_snapshot"
                    entries += writeFileEntry(zip, JSON_ENTRY, jsonFile, "core", logical.conversationCount)

                    phase = "private_data"
                    onPhase(phase)
                    progress(true)
                    val exportRoots = if (includeAppFiles) roots else roots - "files"
                    EtaBackupFileTree.walk(appContext.dataDir, exportRoots, excludedPaths, MAX_ENTRIES - 3) { file, attributes ->
                        currentCoroutineContext().ensureActive()
                        val relative = file.relativeTo(appContext.dataDir).invariantSeparatorsPath
                        val path = normalizeEntry(DATA_PREFIX + relative)
                        when {
                            attributes.isSymbolicLink -> {
                                links += EtaBackupLinkManifest(path, Files.readSymbolicLink(file.toPath()).toString())
                                privateFiles++
                            }
                            attributes.isDirectory -> {
                                directories += EtaBackupDirectoryManifest(path, EtaBackupFileTree.mode(file.toPath()))
                                zip.putNextEntry(ZipEntry("$path/"))
                                zip.closeEntry()
                            }
                            else -> {
                                if (attributes.size() > MAX_ARCHIVE_BYTES - privateBytes - jsonFile.length()) {
                                    throw EtaBackupException("备份内容超过 8 GiB 限制，请清理后重试")
                                }
                                entries += writeFileEntry(zip, path, file, sectionFor(relative), maxBytes = attributes.size()) { count ->
                                    privateBytes += count
                                    progress()
                                }
                                privateFiles++
                            }
                        }
                        progress()
                    }

                    phase = "portable_secrets"
                    onPhase(phase)
                    progress(true)
                    capturePortableSecrets(appContext).let { secrets ->
                        secretsFile.outputStream().buffered().use { output ->
                            output.write(json.encodeToString(secrets).toByteArray(Charsets.UTF_8))
                        }
                        val entry = writeFileEntry(
                            zip = zip,
                            archivePath = PORTABLE_SECRETS_ENTRY,
                            file = secretsFile,
                            section = "secrets",
                        )
                        entries += entry
                        privateFiles++
                        privateBytes += entry.size
                    }

                    phase = "manifest"
                    onPhase(phase)
                    progress(true)
                    val manifest = createManifest(
                        context = appContext,
                        logical = logical,
                        entries = entries,
                        recordCounts = recordCounts,
                        encrypted = secret != null,
                        links = links,
                        directories = directories,
                    )
                    validateFileMetadata(manifest)
                    val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
                    if (manifestBytes.size > MAX_MANIFEST_BYTES) throw EtaBackupException("备份清单超过大小限制")
                    if (manifest.totalBytes > MAX_ARCHIVE_BYTES - manifestBytes.size) throw EtaBackupException("备份内容超过 8 GiB 限制")
                    zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                    zip.write(manifestBytes)
                    zip.closeEntry()
                    zip.finish()
                    zip.flush()
                }
                if (sink is EtaBackupEncryptedOutputStream) sink.finish()
                output.flush()
                progress(true)
                logical.copy(
                    privateFileCount = privateFiles,
                    privateBytes = privateBytes,
                    encrypted = secret != null,
                    fullArchive = true,
                )
            } finally {
                jsonFile.delete()
                secretsFile.delete()
            }
        } catch (failure: Throwable) {
            logFailure("export", phase, failure)
            throw failure
        }
    }

    /** Re-encrypt portable credentials with this device's Android Keystore after a swap restore. */
    suspend fun restorePortableSecretsAfterStartup(context: Context) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val file = File(appContext.noBackupFilesDir, "eta-portable-secrets.json")
        if (!file.isFile) return@withContext
        if (file.length() > 4L * 1024L * 1024L) {
            throw EtaBackupException("迁移凭据条目超过大小限制")
        }
        val secrets = json.decodeFromString<EtaPortableSecrets>(file.readText(Charsets.UTF_8))
        val providerStorage = EncryptedProviderCredentialStorage(appContext)
        secrets.providerCredentials.forEach(providerStorage::write)
        val mcpStorage = McpSecretStore(appContext)
        secrets.mcpBearerTokens.forEach(mcpStorage::setBearerToken)
        ApiConnectorCredentialTransfer.restore(
            context = appContext,
            credentials = secrets.apiConnectorCredentials,
            storage = mcpStorage,
        )
        val a2aStorage = A2ASecretStore(appContext)
        secrets.a2aBearerTokens.forEach { (alias, token) ->
            if (token.isNotBlank()) a2aStorage.save(alias, token)
        }
        secrets.a2aInboundToken?.takeIf(String::isNotBlank)?.let { token ->
            a2aStorage.save("a2a-inbound-v1", token)
        }
        secrets.speechCredentials?.let { SpeechSettingsRepository.saveCredentials(appContext, it) }
        if (!file.delete()) throw IOException("迁移凭据已恢复，但临时文件清理失败")
        AndroidAgentLogger.info("Nexus portable backup credentials restored")
    }

    /**
     * Import either a legacy JSON document or a new full archive. Full archives are validated and
     * staged; they are applied on the next process start before Room and DataStore are initialized.
     */
    suspend fun import(
        context: Context,
        input: InputStream,
        password: String? = null,
        onCommitted: () -> Unit = {},
        forgetCredentials: (String) -> Unit = ProviderCredentialRepository::forget,
        onPhase: (String) -> Unit = {},
    ): EtaBackupSummary = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val probe = PushbackInputStream(BufferedInputStream(input), 64)
        val prefix = readPrefix(probe, 64)
        probe.unread(prefix)
        when {
            isJson(prefix) -> EtaBackupRepository.import(
                context = appContext,
                input = probe,
                onCommitted = onCommitted,
                forgetCredentials = forgetCredentials,
                onPhase = onPhase,
            )
            EtaBackupEncryptedInputStream.hasMagic(prefix) -> {
                val secret = password?.takeIf(String::isNotEmpty)
                    ?: throw EtaBackupException("此备份已加密，请输入备份密码")
                EtaBackupEncryptedInputStream(probe, secret).use { decrypted ->
                    val decoded = PushbackInputStream(BufferedInputStream(decrypted), 64)
                    val header = readPrefix(decoded, 64)
                    decoded.unread(header)
                    if (isJson(header)) EtaBackupRepository.import(appContext, decoded, onCommitted, forgetCredentials, onPhase).copy(encrypted = true)
                    else stage(appContext, decoded, encrypted = true, onPhase = onPhase)
                }
            }
            isZip(prefix) -> stage(appContext, probe, encrypted = false, onPhase = onPhase)
            else -> throw EtaBackupException("无法识别备份格式")
        }
    }

    suspend fun inspect(
        context: Context,
        input: InputStream,
        password: String? = null,
        onPhase: (String) -> Unit = {},
    ): EtaBackupSummary = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        onPhase("verify_file")
        val probe = PushbackInputStream(BufferedInputStream(input), 64)
        val prefix = readPrefix(probe, 64)
        probe.unread(prefix)
        when {
            isJson(prefix) -> EtaBackupRepository.inspect(appContext, probe).also {
                onPhase("inspect_complete")
            }
            EtaBackupEncryptedInputStream.hasMagic(prefix) -> {
                val secret = password?.takeIf(String::isNotEmpty)
                    ?: throw EtaBackupException("此备份已加密，请输入备份密码")
                EtaBackupEncryptedInputStream(probe, secret).use { decrypted ->
                    val decoded = PushbackInputStream(BufferedInputStream(decrypted), 64)
                    val header = readPrefix(decoded, 64)
                    decoded.unread(header)
                    if (isJson(header)) EtaBackupRepository.inspect(appContext, decoded).copy(encrypted = true).also {
                        onPhase("inspect_complete")
                    } else {
                        inspectArchive(appContext, decoded, encrypted = true, onPhase = onPhase)
                    }
                }
            }
            isZip(prefix) -> inspectArchive(appContext, probe, encrypted = false, onPhase = onPhase)
            else -> throw EtaBackupException("无法识别备份格式")
        }
    }

    fun hasPendingRestore(context: Context): Boolean =
        File(context.applicationContext.dataDir, "$CONTROL_DIR/state").takeIf { it.isFile }?.readText()?.trim() == "pending"

    fun restoreResult(context: Context): String? =
        File(context.applicationContext.dataDir, "$CONTROL_DIR/result").takeIf { it.isFile }?.readText()

    /** Must be called from Application.onCreate before any Room/DataStore consumer is initialized. */
    fun applyPendingRestore(context: Context): Boolean {
        val appContext = context.applicationContext
        val control = File(appContext.dataDir, CONTROL_DIR)
        val stateFile = File(control, "state")
        val staged = File(control, "staged")
        val rollback = File(control, "rollback")
        val state = stateFile.takeIf(File::isFile)?.readText()?.trim()
        if (state == "applying") {
            return runCatching {
                restoreRoots(appContext, rollback)
                EtaBackupFileTree.delete(staged)
                EtaBackupFileTree.delete(rollback)
                stateFile.delete()
                File(control, "result").writeText("上次恢复被中断，已回退到恢复前的数据")
                false
            }.getOrElse {
                AndroidAgentLogger.error("Interrupted full restore could not roll back: type=${it.safeLogType()}\n${it.safeStackTrace()}")
                false
            }
        }
        if (state == "committed") {
            EtaBackupFileTree.delete(staged)
            EtaBackupFileTree.delete(rollback)
            stateFile.delete()
            File(control, "result").writeText("完整备份已恢复")
            return true
        }
        if (state != "pending" || !staged.isDirectory) return false

        val stagedManifest = runCatching { readAndVerifyManifest(staged, expectedEncrypted = null) }
            .getOrElse { failure ->
                EtaBackupFileTree.delete(staged)
                stateFile.delete()
                File(control, "result").writeText("备份校验未通过，现有数据已保留")
                AndroidAgentLogger.error(
                    "Pending full restore failed validation: type=${failure.safeLogType()}",
                )
                return false
            }
        if (stagedManifest.format != FORMAT) return false

        stateFile.writeText("applying")
        return try {
            EtaBackupFileTree.delete(rollback)
            check(rollback.mkdirs()) { "无法创建恢复快照目录" }
            val selectedRoots = stagedManifest.roots.filter { it.present }.map { it.name }.ifEmpty { roots }
            selectedRoots.forEach { root ->
                val source = File(appContext.dataDir, root)
                if (source.exists()) movePath(source, File(rollback, root))
            }
            selectedRoots.forEach { root ->
                val source = File(staged, DATA_PREFIX + root)
                if (source.exists()) movePath(source, File(appContext.dataDir, root))
            }
            restoreFileMetadata(appContext, stagedManifest)
            // WorkManager jobs belong to this installation. Rebuild schedules from restored settings.
            listOf(appContext.noBackupFilesDir, File(appContext.dataDir, "databases")).forEach { parent ->
                listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
                    Files.deleteIfExists(File(parent, "androidx.work.workdb$suffix").toPath())
                }
            }
            stateFile.writeText("committed")
            // A cleanup failure after commit must never roll back the restored data.
            runCatching {
                EtaBackupFileTree.delete(staged)
                EtaBackupFileTree.delete(rollback)
                stateFile.delete()
            }
            AndroidAgentLogger.info("Nexus full backup restore applied before database initialization")
            runCatching { File(control, "result").writeText("完整备份已恢复") }
            true
        } catch (failure: Throwable) {
            val rolledBack = runCatching { restoreRoots(appContext, rollback) }.onFailure(failure::addSuppressed).isSuccess
            if (rolledBack) {
                EtaBackupFileTree.delete(staged)
                stateFile.delete()
            }
            AndroidAgentLogger.error("Nexus full backup restore failed: type=${failure.safeLogType()}\n${failure.safeStackTrace()}")
            runCatching { File(control, "result").writeText("恢复失败，已尝试回退，请检查数据或使用恢复前快照") }
            throw failure
        }
    }

    private suspend fun stage(
        context: Context,
        input: InputStream,
        encrypted: Boolean,
        onPhase: (String) -> Unit,
    ): EtaBackupSummary {
        val temp = File(context.cacheDir, "eta-full-restore-${UUID.randomUUID()}").apply {
            if (!mkdirs()) throw IOException("无法创建备份校验目录")
        }
        try {
            onPhase("extract")
            extract(input, temp)
            onPhase("verify_manifest")
            val manifest = readAndVerifyManifest(temp, encrypted)
            validateRestorableDatabases(temp)
            onPhase("validate_database")
            val jsonEntry = File(temp, JSON_ENTRY)
            if (!jsonEntry.isFile) throw EtaBackupException("完整备份缺少逻辑数据")
            onPhase("validate_logical_data")
            val logical = jsonEntry.inputStream().use { input ->
                EtaBackupJsonStreams.stage(context, input).use { EtaBackupValidation.validate(it) }
            }

            val control = File(context.dataDir, CONTROL_DIR)
            control.mkdirs()
            onPhase("safety_snapshot")
            val safety = File(control, "pre-restore${SUFFIX}")
            val safetyTemp = File(control, "pre-restore.tmp")
            safetyTemp.delete()
            val selectedRoots = manifest.roots.filter { it.present }.map { it.name }.ifEmpty { roots }
            safetyTemp.outputStream().buffered().use {
                export(
                    context = context,
                    output = it,
                    password = null,
                    includeAppFiles = "files" in selectedRoots,
                    compression = EtaBackupCompression.FAST,
                )
            }
            movePath(safetyTemp, safety)

            onPhase("stage_restore")
            currentCoroutineContext().ensureActive()
            val staged = File(control, "staged")
            val stagedNew = File(control, "staged.new")
            EtaBackupFileTree.delete(stagedNew)
            if (!stagedNew.mkdirs()) throw IOException("无法创建恢复暂存目录")
            File(temp, DATA_PREFIX.trimEnd('/')).takeIf(File::exists)?.let {
                movePath(it, File(stagedNew, DATA_PREFIX.trimEnd('/')))
            }
            File(temp, MANIFEST_ENTRY).copyTo(File(stagedNew, MANIFEST_ENTRY), overwrite = true)
            File(temp, JSON_ENTRY).copyTo(File(stagedNew, JSON_ENTRY), overwrite = true)
            EtaBackupFileTree.delete(staged)
            movePath(stagedNew, staged)
            // A previous successful restore must not be shown as the result of this staged restore.
            File(control, "result").delete()
            File(control, "state").writeText("pending")
            AndroidAgentLogger.info(
                "Nexus full backup staged: sections=${manifest.sections.size} entries=${manifest.entries.size}",
            )
            return logical.copy(
                privateFileCount = manifest.entries.count { it.path.startsWith(DATA_PREFIX) } + manifest.links.size,
                privateBytes = manifest.entries.sumOf { it.size },
                encrypted = encrypted,
                restartRequired = true,
                fullArchive = true,
            )
        } catch (failure: Throwable) {
            if (failure is CancellationException || failure is Error) throw failure
            if (failure is EtaBackupException) throw failure
            throw EtaBackupException("完整备份导入失败，现有数据未替换", failure)
        } finally {
            EtaBackupFileTree.delete(temp)
        }
    }

    private suspend fun inspectArchive(
        context: Context,
        input: InputStream,
        encrypted: Boolean,
        onPhase: (String) -> Unit,
    ): EtaBackupSummary {
        val temp = File(context.cacheDir, "eta-full-inspect-${UUID.randomUUID()}").apply {
            if (!mkdirs()) throw IOException("无法创建备份校验目录")
        }
        try {
            onPhase("extract")
            extract(input, temp)
            onPhase("verify_manifest")
            val manifest = readAndVerifyManifest(temp, encrypted)
            onPhase("validate_database")
            validateRestorableDatabases(temp)
            onPhase("validate_logical_data")
            val jsonEntry = File(temp, JSON_ENTRY)
            val summary = jsonEntry.inputStream().use { input ->
                EtaBackupJsonStreams.stage(context, input).use { EtaBackupValidation.validate(it) }
            }
            return summary.copy(
                privateFileCount = manifest.entries.count { it.path != JSON_ENTRY } + manifest.links.size,
                privateBytes = manifest.entries.sumOf { it.size },
                encrypted = encrypted,
                fullArchive = true,
            ).also { onPhase("inspect_complete") }
        } finally {
            EtaBackupFileTree.delete(temp)
        }
    }

    private fun validateRestorableDatabases(directory: File) {
        EtaMigrationPlan.entries.distinctBy { it.databaseName }.forEach { entry ->
            val file = File(directory, "data/databases/${entry.databaseName}")
            if (!file.isFile) return@forEach
            val validation = File(directory.parentFile, "db-${UUID.randomUUID().toString().take(8)}").apply { mkdirs() }
            try {
                listOf("", "-wal", "-shm").forEach { suffix ->
                    File(file.parentFile, file.name + suffix).takeIf { it.isFile }?.copyTo(File(validation, file.name + suffix), overwrite = true)
                }
                SQLiteDatabase.openDatabase(File(validation, file.name).path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { database ->
                    if (database.version > entry.currentVersion || database.version < 1) {
                        throw EtaBackupException("${entry.name} 数据库版本不兼容，请使用同版或更新版 Nexus 恢复")
                    }
                    database.rawQuery("PRAGMA quick_check", null).use { result ->
                        if (!result.moveToFirst() || result.getString(0) != "ok") throw EtaBackupException("${entry.name} 数据库完整性校验失败")
                    }
                }
            } catch (failure: Exception) {
                if (failure is EtaBackupException) throw failure
                throw EtaBackupException("${entry.name} 数据库无法读取，现有数据未替换", failure)
            } finally { EtaBackupFileTree.delete(validation) }
        }
    }

    private suspend fun checkpointDatabases(context: Context) {
        ConversationDraftRepository.get(context).flush()
        ConversationAttachmentDrafts.get(context).flush()
        AgentRunMetricsStore.flush()
        listOf(
            EtaDatabase.get(context),
            MeetingDatabase.get(context),
            ConversationExtensionDatabase.get(context),
            LearningDatabase.get(context),
            AgentTaskDatabase.get(context),
            A2ADatabase.get(context),
            ProjectDatabase.get(context),
            AgentMetricsDatabase.get(context),
        ).forEach { database ->
            currentCoroutineContext().ensureActive()
            database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { cursor ->
                if (!cursor.moveToFirst()) {
                    throw EtaBackupException("数据库快照未返回检查点状态")
                }
                if (cursor.getInt(0) != 0) {
                    throw EtaBackupException("数据库仍有活动写入，请稍后重试完整备份")
                }
            }
        }
    }

    private fun collectRecordCounts(context: Context): Map<String, Int> {
        val counts = SECTION_NAMES.associateWithTo(linkedMapOf()) { 0 }
        countTables(EtaDatabase.get(context)) { table ->
            when {
                table.startsWith("runtime_") -> "runtime"
                table == "mcp_servers" || table.startsWith("skill_") -> "mcp_skills"
                else -> "core"
            }
        }.forEach { (section, count) ->
            counts[section] = counts.getValue(section) + count
        }
        listOf<Pair<String, RoomDatabase>>(
            "meeting" to MeetingDatabase.get(context),
            "conversation_extensions" to ConversationExtensionDatabase.get(context),
            "learning" to LearningDatabase.get(context),
            "tasks" to AgentTaskDatabase.get(context),
            "a2a" to A2ADatabase.get(context),
            "projects" to ProjectDatabase.get(context),
            "runtime" to AgentMetricsDatabase.get(context),
        ).forEach { (section, database) ->
            countTables(database) { section }.forEach { (_, count) ->
                counts[section] = counts.getValue(section) + count
            }
        }
        val notificationPath = context.getDatabasePath("eta_notification_history.db")
        if (notificationPath.isFile) {
            counts["notifications"] = runCatching<Int> {
                SQLiteDatabase.openDatabase(
                    notificationPath.path,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { database ->
                    database.rawQuery("SELECT COUNT(*) FROM notification_history", null).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getInt(0) else 0
                    }
                }
            }.getOrDefault(0)
        }
        return counts
    }

    private fun countTables(
        database: RoomDatabase,
        sectionForTable: (String) -> String,
    ): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        val sqlite = database.openHelper.readableDatabase
        val tables = mutableListOf<String>()
        sqlite.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name NOT LIKE 'sqlite_%' AND name != 'room_master_table'",
        ).use { cursor ->
            while (cursor.moveToNext()) tables += cursor.getString(0)
        }
        tables.forEach { table ->
            val escaped = table.replace("\"", "\"\"")
            val count = sqlite.query("SELECT COUNT(*) FROM \"$escaped\"").use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
            val section = sectionForTable(table)
            counts[section] = counts.getOrDefault(section, 0) + count
        }
        return counts
    }

    /**
     * Android Keystore keys do not travel with app data. Capture the decrypted values in a temporary
     * migration payload so [restorePortableSecretsAfterStartup] can re-encrypt them with the key of
     * the destination device. The payload is only stored inside the backup container and is never
     * scanned from the live data roots.
     */
    private suspend fun capturePortableSecrets(context: Context): EtaPortableSecrets {
        val providerCredentials = linkedMapOf<String, ProviderCredential>()
        val providerStorage = EncryptedProviderCredentialStorage(context)
        var unreadableProviderCredentials = 0
        EtaDatabase.get(context).providerDao().providers().forEach { record ->
            val providerId = record.provider.id
            runCatching { providerStorage.read(providerId) }
                .onSuccess { credential ->
                    if (credential != null) providerCredentials[providerId] = credential
                }
                .onFailure { unreadableProviderCredentials++ }
        }

        val mcpBearerTokens = linkedMapOf<String, String>()
        val mcpStorage = McpSecretStore(context)
        var unreadableMcpTokens = 0
        EtaDatabase.get(context).mcpServerDao().servers().forEach { server ->
            try {
                mcpStorage.bearerToken(server.id)?.let { token -> mcpBearerTokens[server.id] = token }
            } catch (_: McpCredentialsUnavailable) {
                unreadableMcpTokens++
            }
        }

        val apiCredentials = ApiConnectorCredentialTransfer.capture(context, mcpStorage)
        val unreadableApiTokens = apiCredentials.unreadable

        val a2aBearerTokens = linkedMapOf<String, String>()
        val a2aStorage = A2ASecretStore(context)
        var unreadableA2ATokens = 0
        A2ADatabase.get(context).openHelper.readableDatabase.query(
            "SELECT authSecretAlias FROM a2a_agents WHERE authSecretAlias IS NOT NULL",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val alias = cursor.getString(0)
                runCatching { a2aStorage.load(alias) }
                    .onSuccess { token ->
                        if (!token.isNullOrBlank()) a2aBearerTokens[alias] = token
                    }
                    .onFailure { unreadableA2ATokens++ }
            }
        }

        val speechCredentials = runCatching { SpeechSettingsRepository.credentials(context) }
            .onFailure {
                AndroidAgentLogger.warn(
                    "Nexus backup skipped unreadable speech credentials: type=${it.safeLogType()}",
                )
            }
            .getOrNull()

        if (unreadableProviderCredentials > 0 || unreadableA2ATokens > 0 ||
            unreadableMcpTokens > 0 || unreadableApiTokens > 0
        ) {
            AndroidAgentLogger.warn(
                "Nexus backup skipped unreadable portable credentials: " +
                    "providers=$unreadableProviderCredentials a2a=$unreadableA2ATokens " +
                    "mcp=$unreadableMcpTokens api=$unreadableApiTokens",
            )
        }
        return EtaPortableSecrets(
            providerCredentials = providerCredentials,
            mcpBearerTokens = mcpBearerTokens,
            apiConnectorCredentials = apiCredentials.credentials,
            a2aBearerTokens = a2aBearerTokens,
            a2aInboundToken = A2AInboundConfigStore(context).loadToken(),
            speechCredentials = speechCredentials,
        )
    }

    private suspend fun writeFileEntry(
        zip: ZipOutputStream,
        archivePath: String,
        file: File,
        section: String,
        recordCount: Int = 0,
        maxBytes: Long = MAX_ARCHIVE_BYTES,
        onBytes: (Int) -> Unit = {},
    ): EtaBackupEntryManifest {
        zip.putNextEntry(ZipEntry(archivePath).apply { time = file.lastModified() })
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > maxBytes - size) throw EtaBackupException("备份期间文件大小发生变化，请稍后重试")
                zip.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                size += count
                onBytes(count)
            }
        }
        zip.closeEntry()
        return EtaBackupEntryManifest(
            path = archivePath,
            section = section,
            size = size,
            sha256 = digest.digest().hex(),
            recordCount = recordCount,
            mode = EtaBackupFileTree.mode(file.toPath()),
        )
    }

    private fun createManifest(
        context: Context,
        logical: EtaBackupSummary,
        entries: List<EtaBackupEntryManifest>,
        recordCounts: Map<String, Int>,
        encrypted: Boolean,
        links: List<EtaBackupLinkManifest>,
        directories: List<EtaBackupDirectoryManifest>,
    ): EtaFullBackupManifest {
        val sectionNames = SECTION_NAMES
        val sections = sectionNames.map { name ->
            val sectionEntries = entries.filter { it.section == name }
            val digest = MessageDigest.getInstance("SHA-256")
            sectionEntries.sortedBy { it.path }.forEach { entry ->
                digest.update(entry.path.toByteArray(Charsets.UTF_8))
                digest.update(0)
                digest.update(entry.sha256.toByteArray(Charsets.US_ASCII))
                digest.update('\n'.code.toByte())
            }
            EtaBackupSectionManifest(
                name = name,
                present = sectionEntries.isNotEmpty() || (recordCounts[name] ?: 0) > 0,
                entryCount = sectionEntries.size,
                files = sectionEntries.size,
                bytes = sectionEntries.sumOf { it.size },
                sha256 = digest.digest().hex(),
                recordCount = recordCounts[name] ?: 0,
            )
        }
        val archivedPaths = entries.mapTo(mutableSetOf()) { normalizeEntry(it.path) }
            .apply {
                addAll(directories.map { normalizeEntry(it.path) })
                addAll(links.map { normalizeEntry(it.path) })
            }
        return EtaFullBackupManifest(
            format = FORMAT,
            schemaVersion = SCHEMA_VERSION,
            exportedAt = System.currentTimeMillis(),
            appVersion = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            }.getOrElse { "" },
            encrypted = encrypted,
            roots = roots.map { name ->
                EtaBackupRootManifest(name, DATA_PREFIX + name in archivedPaths)
            },
            sections = sections,
            entries = entries,
            links = links,
            directories = directories,
            totalEntries = entries.size,
            totalBytes = entries.sumOf { it.size },
            providerCount = logical.providerCount,
            modelCount = logical.modelCount,
            conversationCount = logical.conversationCount,
            messageCount = logical.messageCount,
        )
    }

    private suspend fun extract(input: InputStream, target: File) {
        val seen = mutableSetOf<String>()
        var total = 0L
        var count = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextEntry ?: break
                count++
                if (count > MAX_ENTRIES) throw EtaBackupException("备份条目过多")
                if (entry.isDirectory) {
                    val path = normalizeEntry(entry.name.trimEnd('/'))
                    if (!seen.add(path)) throw EtaBackupException("备份包含重复条目")
                    if (path.startsWith(DATA_PREFIX) &&
                        path.removePrefix(DATA_PREFIX).substringBefore('/') in roots
                    ) {
                        File(target, path).mkdirs()
                    }
                    zip.closeEntry()
                    continue
                }
                val path = normalizeEntry(entry.name)
                if (path != JSON_ENTRY && path != MANIFEST_ENTRY && !path.startsWith(DATA_PREFIX)) {
                    throw EtaBackupException("备份包含不支持的条目")
                }
                if (!seen.add(path)) throw EtaBackupException("备份包含重复条目")
                val output = File(target, path)
                check(output.canonicalFile.toPath().startsWith(target.canonicalFile.toPath())) {
                    "备份条目路径无效"
                }
                output.parentFile?.mkdirs()
                val limit = if (path == MANIFEST_ENTRY) MAX_MANIFEST_BYTES else MAX_ARCHIVE_BYTES
                output.outputStream().use { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                    var entryBytes = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = zip.read(buffer)
                        if (read < 0) break
                        entryBytes += read
                        total += read
                        if (entryBytes > limit || total > MAX_ARCHIVE_BYTES) {
                            throw EtaBackupException("备份解压后超过大小限制")
                        }
                        out.write(buffer, 0, read)
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun readAndVerifyManifest(
        directory: File,
        expectedEncrypted: Boolean?,
    ): EtaFullBackupManifest {
        val file = File(directory, MANIFEST_ENTRY)
        if (!file.isFile || file.length() > MAX_MANIFEST_BYTES) {
            throw EtaBackupException("完整备份缺少有效清单")
        }
        val manifest = runCatching {
            json.decodeFromString<EtaFullBackupManifest>(file.readText(Charsets.UTF_8))
        }.getOrElse { throw EtaBackupException("完整备份清单格式无效", it) }
        if (manifest.format != FORMAT || manifest.schemaVersion !in 1..SCHEMA_VERSION) {
            throw EtaBackupException("不支持的完整备份版本")
        }
        validateFileMetadata(manifest)
        if (expectedEncrypted != null && manifest.encrypted != expectedEncrypted) {
            throw EtaBackupException("备份加密状态与清单不一致")
        }
        if (manifest.entries.map { normalizeEntry(it.path) }.toSet().size != manifest.entries.size) {
            throw EtaBackupException("备份清单包含重复条目")
        }
        if (manifest.sections.map { it.name }.toSet() != SECTION_NAMES.toSet() ||
            manifest.sections.size != SECTION_NAMES.size
        ) {
            throw EtaBackupException("备份清单缺少必要分区")
        }
        if (manifest.roots.map { it.name }.toSet() != roots.toSet() ||
            manifest.roots.size != roots.size
        ) {
            throw EtaBackupException("备份清单缺少必要数据根目录")
        }
        manifest.roots.forEach { root ->
            val present = Files.exists(
                File(directory, DATA_PREFIX + root.name).toPath(),
                NOFOLLOW_LINKS,
            )
            if (root.present != present) {
                throw EtaBackupException("备份数据根目录状态不一致：${root.name}")
            }
        }
        if (manifest.entries.any { it.section !in SECTION_NAMES || it.recordCount < 0 }) {
            throw EtaBackupException("备份清单包含无效分区")
        }
        if (manifest.schemaVersion >= 2 &&
            (manifest.totalEntries != manifest.entries.size ||
                manifest.totalBytes != manifest.entries.sumOf { it.size })
        ) {
            throw EtaBackupException("备份清单总计数校验失败")
        }
        manifest.entries.forEach { entry ->
            val path = normalizeEntry(entry.path)
            if (path != JSON_ENTRY && !path.startsWith(DATA_PREFIX)) {
                throw EtaBackupException("备份清单包含不支持的文件")
            }
            val target = File(directory, path)
            if (!Files.isRegularFile(target.toPath(), NOFOLLOW_LINKS)) throw EtaBackupException("备份文件缺少 ${entry.path}")
            if (target.length() != entry.size) throw EtaBackupException("备份文件大小校验失败")
            if (sha256(target) != entry.sha256) throw EtaBackupException("备份文件哈希校验失败")
        }
        val listed = manifest.entries.mapTo(mutableSetOf()) { normalizeEntry(it.path) }
        Files.walkFileTree(directory.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                val relative = directory.toPath().relativize(file).toString().replace(File.separatorChar, '/')
                if (!attrs.isRegularFile || (relative != MANIFEST_ENTRY && relative !in listed)) {
                    throw EtaBackupException("备份包含清单外文件或链接")
                }
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
        manifest.directories.forEach {
            if (!Files.isDirectory(File(directory, it.path).toPath(), NOFOLLOW_LINKS)) throw EtaBackupException("备份缺少目录")
        }
        manifest.links.forEach {
            if (Files.exists(File(directory, it.path).toPath(), NOFOLLOW_LINKS)) throw EtaBackupException("备份链接与已有文件冲突")
            if (!Files.isDirectory(File(directory, it.path).parentFile!!.toPath(), NOFOLLOW_LINKS)) throw EtaBackupException("备份链接缺少父目录")
        }
        validateSections(directory, manifest)
        return manifest
    }

    private fun validateSections(directory: File, manifest: EtaFullBackupManifest) {
        manifest.sections.forEach { section ->
            val entries = manifest.entries.filter { it.section == section.name }
            if (section.present != (entries.isNotEmpty() || section.recordCount > 0)) {
                throw EtaBackupException("备份分区存在状态无效")
            }
            if (section.files != entries.size || section.bytes != entries.sumOf { it.size }) {
                throw EtaBackupException("备份分区计数校验失败")
            }
            if (section.recordCount < 0 || section.entryCount != entries.size) {
                throw EtaBackupException("备份分区记录计数无效")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            entries.sortedBy { it.path }.forEach { entry ->
                digest.update(entry.path.toByteArray(Charsets.UTF_8))
                digest.update(0)
                digest.update(entry.sha256.toByteArray(Charsets.US_ASCII))
                digest.update('\n'.code.toByte())
            }
            if (digest.digest().hex() != section.sha256) throw EtaBackupException("备份分区哈希校验失败")
        }
    }

    private fun validateFileMetadata(manifest: EtaFullBackupManifest) {
        if (manifest.schemaVersion < 3 && (manifest.links.isNotEmpty() || manifest.directories.isNotEmpty())) {
            throw EtaBackupException("旧版本清单不能包含链接元数据")
        }
        val paths = hashSetOf<String>()
        val linkPaths = manifest.links.mapTo(hashSetOf()) { it.path }
        val filePaths = manifest.entries.mapTo(hashSetOf()) { it.path }
        fun validatePath(path: String, directory: Boolean = false) {
            normalizeEntry(path)
            if (!paths.add(path)) throw EtaBackupException("备份文件、目录与链接路径冲突")
            if (path != JSON_ENTRY) {
                val relative = path.removePrefix(DATA_PREFIX)
                if (!path.startsWith(DATA_PREFIX) || relative.substringBefore('/') !in roots ||
                    (!directory && '/' !in relative)
                ) throw EtaBackupException("备份元数据路径无效")
            }
            var parent = path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                if (parent in linkPaths || parent in filePaths) throw EtaBackupException("备份条目不能位于文件或链接之下")
                parent = parent.substringBeforeLast('/', "")
            }
        }
        manifest.entries.forEach {
            validatePath(it.path)
            if (it.size < 0 || (it.mode != null && it.mode !in 0..511)) throw EtaBackupException("备份文件元数据无效")
        }
        manifest.directories.forEach {
            validatePath(it.path, directory = true)
            if (it.path == JSON_ENTRY || (it.mode != null && it.mode !in 0..511)) throw EtaBackupException("备份目录元数据无效")
        }
        manifest.links.forEach { link ->
            validatePath(link.path)
            if (!link.path.startsWith("data/files/") || link.target.isEmpty() || link.target.length > 4096 || '\u0000' in link.target) {
                throw EtaBackupException("备份链接元数据无效")
            }
            val guestLink = guestLinkPath.matches(link.path)
            if (!guestLink) {
                val target = File(link.target).toPath()
                val resolved = File(link.path).toPath().parent.resolve(target).normalize()
                if (target.isAbsolute || '\\' in link.target || drivePrefix.containsMatchIn(link.target) ||
                    !resolved.startsWith(File("data/files").toPath())
                ) throw EtaBackupException("工作区链接指向备份范围之外")
            }
        }
        if (paths.size.toLong() + 1 > MAX_ENTRIES) throw EtaBackupException("备份条目过多")
    }

    /** Link creation is deferred until validation is complete and all regular files have moved. */
    private fun restoreFileMetadata(context: Context, manifest: EtaFullBackupManifest) {
        fun live(path: String) = File(context.dataDir, path.removePrefix(DATA_PREFIX)).toPath()
        manifest.links.forEach { link ->
            val destination = live(link.path)
            Files.createSymbolicLink(destination, File(link.target).toPath())
        }
        manifest.entries.filter { it.path.startsWith(DATA_PREFIX) }.forEach {
            EtaBackupFileTree.setMode(live(it.path), it.mode)
        }
        manifest.directories.sortedByDescending { it.path.count { char -> char == '/' } }.forEach {
            EtaBackupFileTree.setMode(live(it.path), it.mode)
        }
    }

    private fun restoreRoots(context: Context, rollback: File) {
        roots.forEach { root ->
            val target = File(context.dataDir, root)
            val previous = File(rollback, root)
            // A crash may happen before a root has been moved. Leave that original root intact.
            if (previous.exists()) {
                EtaBackupFileTree.delete(target)
                movePath(previous, target)
            }
        }
    }

    private fun movePath(source: File, target: File) {
        target.parentFile?.mkdirs()
        EtaBackupFileTree.delete(target)
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sectionFor(relative: String): String = when {
        relative.startsWith("databases/eta_meeting.db") -> "meeting"
        relative.startsWith("databases/eta_fork.db") -> "conversation_extensions"
        relative.startsWith("databases/eta_learning.db") -> "learning"
        relative.startsWith("databases/eta_task.db") -> "tasks"
        relative.startsWith("databases/eta_a2a.db") -> "a2a"
        relative.startsWith("databases/eta_projects.db") -> "projects"
        relative.startsWith("databases/eta.db") -> "core"
        relative.startsWith("databases/eta_notification_history.db") -> "notifications"
        relative.startsWith("databases/") -> "runtime"
        relative.startsWith("shared_prefs/eta_mcp") || relative.startsWith("shared_prefs/eta_a2a") ||
            relative.startsWith("shared_prefs/eta_speech_secrets") || relative.startsWith("no_backup/provider_credentials") -> "secrets"
        relative.startsWith("files/datastore/") || relative.startsWith("shared_prefs/") -> "settings"
        relative.startsWith("files/meeting") -> "meeting_artifacts"
        relative.startsWith("files/learning/") -> "learning"
        relative.startsWith("files/capability-center/") ||
            relative.contains("skill", ignoreCase = true) || relative.contains("mcp", ignoreCase = true) -> "mcp_skills"
        else -> "workspace"
    }

    private fun normalizeEntry(raw: String): String {
        val path = raw
        if (path.isBlank() || path.startsWith('/') || '\\' in path || path.contains('\u0000') ||
            drivePrefix.containsMatchIn(path) || path.split('/').any { it.isEmpty() || it == ".." || it == "." }
        ) {
            throw EtaBackupException("备份条目路径无效")
        }
        return path
    }

    private fun readPrefix(input: InputStream, limit: Int): ByteArray {
        val bytes = ByteArray(limit)
        var count = 0
        while (count < limit) {
            val read = input.read(bytes, count, limit - count)
            if (read < 0) break
            count += read
        }
        return bytes.copyOf(count)
    }

    private fun isJson(prefix: ByteArray): Boolean = prefix
        .dropWhile { it == ' '.code.toByte() || it == '\n'.code.toByte() || it == '\r'.code.toByte() || it == '\t'.code.toByte() }
        .firstOrNull() == '{'.code.toByte()

    private fun isZip(prefix: ByteArray): Boolean = prefix.size >= 4 &&
        prefix[0] == 'P'.code.toByte() && prefix[1] == 'K'.code.toByte()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().hex()
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun logFailure(operation: String, phase: String, failure: Throwable) {
        if (failure is CancellationException) return
        AndroidAgentLogger.error(
            "Nexus full backup failed: operation=$operation phase=$phase type=${failure.safeLogType()}\n" +
                failure.safeStackTrace(),
        )
    }

    private val SECTION_NAMES = listOf(
        "core",
        "runtime",
        "meeting",
        "meeting_artifacts",
        "conversation_extensions",
        "learning",
        "tasks",
        "a2a",
        "projects",
        "notifications",
        "mcp_skills",
        "settings",
        "secrets",
        "workspace",
    )
}

@Serializable
internal data class EtaFullBackupManifest(
    val format: String,
    val schemaVersion: Int,
    val exportedAt: Long,
    val appVersion: String,
    val encrypted: Boolean,
    val roots: List<EtaBackupRootManifest>,
    val sections: List<EtaBackupSectionManifest>,
    val entries: List<EtaBackupEntryManifest>,
    val links: List<EtaBackupLinkManifest> = emptyList(),
    val directories: List<EtaBackupDirectoryManifest> = emptyList(),
    val totalEntries: Int = 0,
    val totalBytes: Long = 0,
    val providerCount: Int,
    val modelCount: Int,
    val conversationCount: Int,
    val messageCount: Int,
)

@Serializable
internal data class EtaBackupRootManifest(val name: String, val present: Boolean)

@Serializable
internal data class EtaBackupSectionManifest(
    val name: String,
    val present: Boolean,
    val entryCount: Int,
    val files: Int,
    val bytes: Long,
    val sha256: String,
    val recordCount: Int = 0,
)

@Serializable
internal data class EtaBackupEntryManifest(
    val path: String,
    val section: String,
    val size: Long,
    val sha256: String,
    val recordCount: Int = 0,
    val mode: Int? = null,
)

/** Decrypted credentials are held only in memory, the temporary export file, or the backup ZIP. */
@Serializable
internal data class EtaPortableSecrets(
    val providerCredentials: Map<String, ProviderCredential> = emptyMap(),
    val mcpBearerTokens: Map<String, String> = emptyMap(),
    val apiConnectorCredentials: Map<String, String> = emptyMap(),
    val a2aBearerTokens: Map<String, String> = emptyMap(),
    val a2aInboundToken: String? = null,
    val speechCredentials: SpeechCredentials? = null,
)

/** Streaming AES-256-GCM container. Each frame is independently authenticated. */
internal class EtaBackupEncryptedOutputStream(
    output: OutputStream,
    password: String,
) : FilterOutputStream(output) {
    private val random = SecureRandom()
    private val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
    private val noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also(random::nextBytes)
    private val key: ByteArray
    private var frame = 0
    private var finished = false
    private val pending = ByteArray(FRAME_BYTES)
    private var pendingBytes = 0

    init {
        val header = ByteBuffer.allocate(MAGIC.size + 4 + 4 + 1 + salt.size + noncePrefix.size)
            .order(ByteOrder.BIG_ENDIAN)
        header.put(MAGIC)
        header.putInt(VERSION)
        header.putInt(ITERATIONS)
        header.put(salt.size.toByte())
        header.put(salt)
        header.put(noncePrefix)
        out.write(header.array())
        key = deriveKey(password, salt, ITERATIONS)
    }

    override fun write(value: Int) {
        check(!finished) { "Encrypted backup stream is finished" }
        pending[pendingBytes++] = value.toByte()
        if (pendingBytes == pending.size) flushFrame()
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        check(!finished) { "Encrypted backup stream is finished" }
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
        var consumed = 0
        while (consumed < length) {
            val count = minOf(pending.size - pendingBytes, length - consumed)
            buffer.copyInto(pending, pendingBytes, offset + consumed, offset + consumed + count)
            pendingBytes += count
            consumed += count
            if (pendingBytes == pending.size) flushFrame()
        }
    }

    private fun flushFrame() {
        if (pendingBytes == 0) return
        writeFrame(pending.copyOf(pendingBytes), final = false)
        pending.fill(0, 0, pendingBytes)
        pendingBytes = 0
    }

    override fun flush() {
        if (!finished) flushFrame()
        out.flush()
    }

    fun finish() {
        if (finished) return
        flushFrame()
        writeFrame(ByteArray(0), final = true)
        out.writeInt(END_MARKER)
        out.flush()
        key.fill(0)
        finished = true
    }

    override fun close() {
        finish()
        // Deliberately do not close the caller-owned output stream.
    }

    private fun writeFrame(plain: ByteArray, final: Boolean) {
        val iv = frameIv(frame++)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, 0, KEY_BYTES, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val framed = ByteArray(plain.size + 1).also {
            it[0] = if (final) 1 else 0
            plain.copyInto(it, destinationOffset = 1)
        }
        val encrypted = cipher.doFinal(framed)
        out.writeInt(encrypted.size)
        out.write(encrypted)
        framed.fill(0)
        plain.fill(0)
    }

    private fun frameIv(frame: Int): ByteArray = ByteBuffer.allocate(IV_BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .put(noncePrefix)
        .putInt(frame)
        .array()

    private companion object {
        val MAGIC = byteArrayOf('E'.code.toByte(), 'T'.code.toByte(), 'A'.code.toByte(), 'B'.code.toByte(), 'A'.code.toByte(), 'K'.code.toByte(), '1'.code.toByte(), 0)
        const val VERSION = 2
        const val ITERATIONS = 180_000
        const val SALT_BYTES = 16
        const val NONCE_PREFIX_BYTES = 8
        const val IV_BYTES = 12
        const val KEY_BYTES = 32
        const val TAG_BITS = 128
        const val FRAME_BYTES = 1024 * 1024
        const val END_MARKER = -1
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

internal class EtaBackupEncryptedInputStream(
    input: InputStream,
    password: String,
) : InputStream() {
    private val source = input
    private val key: ByteArray
    private val noncePrefix: ByteArray
    private var frame = 0
    private var current = ByteArray(0)
    private var currentOffset = 0
    private var finalFrameSeen = false
    private var ended = false

    init {
        val magic = source.readExact(MAGIC.size)
        if (!magic.contentEquals(MAGIC)) throw EtaBackupException("加密备份文件头无效")
        val version = source.readInt()
        val iterations: Int
        val salt: ByteArray
        when (version) {
            LEGACY_VERSION -> {
                // v1 wrote the low 16 bits of 180_000, then salt before its length. Its key always
                // used the real iteration count, so recover archived v1 files with that value.
                source.readUnsignedShort()
                salt = source.readExact(LEGACY_SALT_BYTES)
                if (source.readUnsignedByte() != LEGACY_SALT_BYTES) {
                    throw EtaBackupException("加密备份盐值无效")
                }
                iterations = LEGACY_ITERATIONS
            }
            VERSION -> {
                iterations = source.readInt()
                if (iterations !in 1_000..1_000_000) throw EtaBackupException("加密备份参数无效")
                val saltLength = source.readUnsignedByte()
                if (saltLength !in 8..64) throw EtaBackupException("加密备份盐值无效")
                salt = source.readExact(saltLength)
            }
            else -> throw EtaBackupException("不支持的加密备份版本")
        }
        noncePrefix = source.readExact(NONCE_PREFIX_BYTES)
        key = deriveKey(password, salt, iterations)
    }

    override fun read(): Int {
        val buffer = ByteArray(1)
        return if (read(buffer, 0, 1) > 0) buffer[0].toInt() and 0xff else -1
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (currentOffset >= current.size) {
            if (ended) return -1
            loadFrame()
            if (ended) return -1
        }
        val count = minOf(length, current.size - currentOffset)
        current.copyInto(buffer, offset, currentOffset, currentOffset + count)
        currentOffset += count
        return count
    }

    private fun loadFrame() {
        val encryptedSize = try {
            source.readInt()
        } catch (failure: IOException) {
            throw EtaBackupException("加密备份不完整", failure)
        }
        if (encryptedSize == END_MARKER) {
            if (!finalFrameSeen) throw EtaBackupException("加密备份缺少结束帧")
            ended = true
            return
        }
        if (finalFrameSeen) throw EtaBackupException("加密备份结束帧后存在额外数据")
        if (encryptedSize <= TAG_BITS / 8 || encryptedSize > FRAME_BYTES + TAG_BITS / 8 + 1) {
            throw EtaBackupException("加密备份帧长度无效")
        }
        val encrypted = source.readExact(encryptedSize)
        val iv = ByteBuffer.allocate(IV_BYTES).order(ByteOrder.BIG_ENDIAN).put(noncePrefix).putInt(frame++).array()
        val final = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, 0, KEY_BYTES, "AES"), GCMParameterSpec(TAG_BITS, iv))
            val plain = cipher.doFinal(encrypted)
            val final = plain.firstOrNull() == 1.toByte()
            if (final && plain.size != 1) throw EtaBackupException("加密备份结束帧无效")
            current = if (plain.isEmpty()) ByteArray(0) else plain.copyOfRange(1, plain.size)
            currentOffset = 0
            final
        } catch (failure: Throwable) {
            throw EtaBackupException("备份密码错误或文件已损坏", failure)
        }
        finalFrameSeen = final
    }

    override fun close() {
        key.fill(0)
        source.close()
    }

    companion object {
        private val MAGIC = byteArrayOf('E'.code.toByte(), 'T'.code.toByte(), 'A'.code.toByte(), 'B'.code.toByte(), 'A'.code.toByte(), 'K'.code.toByte(), '1'.code.toByte(), 0)
        private const val LEGACY_VERSION = 1
        private const val VERSION = 2
        private const val LEGACY_ITERATIONS = 180_000
        private const val LEGACY_SALT_BYTES = 16
        private const val NONCE_PREFIX_BYTES = 8
        private const val IV_BYTES = 12
        private const val KEY_BYTES = 32
        private const val TAG_BITS = 128
        private const val FRAME_BYTES = 1024 * 1024
        private const val END_MARKER = -1
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        fun hasMagic(prefix: ByteArray): Boolean = prefix.size >= MAGIC.size &&
            MAGIC.indices.all { prefix[it] == MAGIC[it] }
    }
}

private fun deriveKey(password: String, salt: ByteArray, iterations: Int): ByteArray {
    val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
    return try {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}

private fun InputStream.readExact(size: Int): ByteArray {
    if (size < 0 || size > 16 * 1024 * 1024) throw EtaBackupException("加密备份长度无效")
    val output = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val count = read(output, offset, size - offset)
        if (count < 0) throw EtaBackupException("加密备份不完整")
        offset += count
    }
    return output
}

private fun InputStream.readInt(): Int = ByteBuffer.wrap(readExact(4)).order(ByteOrder.BIG_ENDIAN).int
private fun InputStream.readUnsignedShort(): Int = ByteBuffer.wrap(readExact(2)).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
private fun InputStream.readUnsignedByte(): Int = readExact(1)[0].toInt() and 0xff
private fun OutputStream.writeInt(value: Int) = write(
    ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array(),
)

