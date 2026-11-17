package io.github.mangi.eta.agent.packages

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import org.apache.commons.compress.archivers.zip.ZipFile
import org.hjson.JsonValue
import org.json.JSONArray
import org.json.JSONObject

internal data class PackageTool(val name: String, val description: String, val entry: String, val inputSchema: String, val callbackKey: String? = null)
internal data class CapabilityPackage(
    val id: String, val title: String, val description: String, val version: String, val kind: String,
    val digest: String, val tools: List<PackageTool>, val enabled: Boolean = false,
    val allowedTools: Set<String> = emptySet(), val source: String = "local", val previous: String? = null,
    val memoryMb: Int = 64, val timeoutSeconds: Int = 120, val issues: List<String> = emptyList(),
    val environmentNames: List<String> = emptyList(), val apiVersion: String = "1.0.1",
    val mainEntry: String? = null, val registrations: String = "[]",
    val wasmModuleCount: Int = 0,
    val enabledExtensions: Set<String> = emptySet(),
)
internal data class PackageImportPreview(val token: String, val content: CapabilityPackage, val replacing: Boolean, val previousDigest: String? = null)

/** Immutable versions plus one atomic index. Import never executes package code. */
internal class CapabilityPackageStore(
    context: Context,
    private val environments: PackageEnvironmentStore = PackageEnvironmentStore(context.applicationContext),
) {
    private val context = context.applicationContext
    private val root = File(this.context.filesDir, "capability-packages")
    private val staging = File(this.context.cacheDir, "package-imports")
    private val index = File(root, "index.json")

    fun list(): List<CapabilityPackage> = locked { readIndex() }

    fun stage(uri: Uri): PackageImportPreview = stageInput(uri.toString()) {
        requireNotNull(context.contentResolver.openInputStream(uri))
    }

    fun builtinNames(): List<String> = context.assets.list("packages/operit").orEmpty().filter { it.endsWith(".js") }.sorted()
    fun builtinCatalog(): List<Pair<String, String>> = builtinNames().map { name ->
        val title = runCatching {
            val body = context.assets.open("packages/operit/$name").bufferedReader().use { it.readText() }
            localized(parseJson(requireNotNull(METADATA.find(body)).groupValues[1]).opt("display_name"))
        }.getOrDefault("").ifBlank { name.removeSuffix(".js") }
        name to title
    }

    fun stageBuiltin(name: String): PackageImportPreview {
        require(name.matches(Regex("[A-Za-z0-9_-]+\\.js")))
        return stageInput("builtin:operit:16cffb98:$name") { context.assets.open("packages/operit/$name") }
    }

    private fun stageInput(source: String, open: () -> InputStream): PackageImportPreview = run {
        val token = UUID.randomUUID().toString()
        val work = File(staging, token).apply { check(mkdirs()) }
        try {
            val archive = File(work, "input")
            open().use { input -> copyBounded(input, archive, MAX_ARCHIVE) }
            val destination = File(work, "content").apply { check(mkdir()) }
            val signature = archive.inputStream().use { it.readNBytes(4) }
            if (signature.size >= 2 && signature[0] == 0x50.toByte() && signature[1] == 0x4b.toByte()) {
                extract(archive, destination)
            } else {
                require(archive.length() <= MAX_SCRIPT) { "插件脚本超过 4 MiB" }
                Files.copy(archive.toPath(), File(destination, "main.js").toPath())
            }
            val contentRoot = contentRoot(destination)
            val metadata = inspect(contentRoot, source)
            val previous = list().firstOrNull { it.id == metadata.id }?.digest
            File(work, "selection.json").writeText(JSONObject().put("relative", contentRoot.relativeTo(work).invariantSeparatorsPath)
                .put("metadata", encode(metadata)).put("previous", previous ?: JSONObject.NULL).toString())
            PackageImportPreview(token, metadata, previous != null, previous)
        } catch (failure: Exception) { work.deleteRecursively(); throw failure }
    }

    fun cancel(preview: PackageImportPreview) = locked { previewRoot(preview.token).deleteRecursively(); Unit }

    fun preview(token: String): PackageImportPreview = locked {
        val selection = JSONObject(File(previewRoot(token), "selection.json").readText())
        val previous = selection.optString("previous").takeIf { selection.opt("previous") != JSONObject.NULL && it.isNotBlank() }
        PackageImportPreview(token, decode(selection.getJSONObject("metadata")), previous != null, previous)
    }

    fun install(preview: PackageImportPreview): CapabilityPackage = locked {
        val work = previewRoot(preview.token)
        val selection = JSONObject(File(work, "selection.json").readText())
        val source = File(work, safePath(selection.getString("relative"))).canonicalFile
        require(source.toPath().startsWith(work.canonicalFile.toPath()))
        val inspected = inspect(source, preview.content.source)
        require(inspected == preview.content && encode(inspected).toString() == selection.getJSONObject("metadata").toString()) { "待安装内容已变化，请重新导入" }
        val current = readIndex()
        val old = current.firstOrNull { it.id == inspected.id }
        if (old?.digest == inspected.digest) { work.deleteRecursively(); return@locked old }
        require(old?.digest == preview.previousDigest) { "已安装版本发生变化，请重新确认导入" }
        val destination = versionDirectory(inspected.id, inspected.digest)
        if (!destination.exists()) {
            destination.parentFile!!.mkdirs()
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } else require(inspect(destination, inspected.source).digest == inspected.digest)
        val installed = inspected.copy(previous = old?.digest, enabled = false)
        File(destination, VERSION_RECORD).writeText(encode(installed).toString())
        writeIndex(current.filterNot { it.id == installed.id } + installed)
        work.deleteRecursively()
        installed
    }

    fun configure(id: String, enabled: Boolean? = null, allowedTools: Set<String>? = null, memoryMb: Int? = null, timeoutSeconds: Int? = null, expectedDigest: String? = null,
        enabledExtensions: Set<String>? = null) = locked {
        val rows = readIndex()
        val old = rows.singleOrNull { it.id == id } ?: error("未找到扩展包")
        require(expectedDigest == null || old.digest == expectedDigest) { "扩展包版本已变化" }
        val modules = if (old.wasmModuleCount > 0) old.wasmModuleCount else inspect(versionDirectory(old.id, old.digest), old.source).wasmModuleCount
        val extensions = enabledExtensions ?: old.enabledExtensions
        require(extensions.all { it in PackageExtensionKinds.labels && it in PackageExtensionKinds.declared(old) }) { "扩展未声明或尚未适配" }
        require((memoryMb ?: old.memoryMb) >= if (modules > 0) 16 else 8) { "此扩展包的最低运行内存为 ${if (modules > 0) 16 else 8} MiB" }
        val updated = old.copy(enabled = enabled ?: old.enabled, allowedTools = allowedTools ?: old.allowedTools,
            memoryMb = (memoryMb ?: old.memoryMb).coerceIn(8, 256), timeoutSeconds = (timeoutSeconds ?: old.timeoutSeconds).coerceIn(1, 120), wasmModuleCount = modules,
            enabledExtensions = extensions)
        require(updated.allowedTools.all { TOOL_NAME.matches(it) } && updated.allowedTools.size <= 128)
        writeIndex(rows.map { if (it.id == id) updated else it })
    }

    fun rollback(id: String, expectedDigest: String? = null) = locked {
        val rows = readIndex()
        val current = rows.singleOrNull { it.id == id } ?: error("未找到扩展包")
        require(expectedDigest == null || current.digest == expectedDigest) { "扩展包版本已变化" }
        val digest = current.previous ?: error("没有可回退版本")
        val directory = versionDirectory(id, digest)
        val previous = decode(JSONObject(File(directory, VERSION_RECORD).readText()))
        require(inspect(directory, previous.source).digest == digest) { "历史版本校验失败" }
        writeIndex(rows.map { if (it.id == id) previous.copy(enabled = false, previous = current.digest) else it })
    }

    suspend fun delete(id: String, expectedDigest: String? = null) {
        val record = locked { readIndex().singleOrNull { it.id == id } } ?: error("未找到扩展包")
        require(expectedDigest == null || record.digest == expectedDigest) { "扩展包版本已变化" }

        // Remove capabilities that outlive the package directory before the package disappears
        // from the index. A failed cleanup leaves the package visible so the user can retry.
        PackageAiRegistry(context).removePackage(id)
        PackageDesktopWidgetService(context).removePackage(id)
        PackageEventJournal(context).use { it.revoke(id) }
        environments.clear(id)
        PackageStateStore(context).clear(id)

        locked {
            val rows = readIndex()
            val current = rows.singleOrNull { it.id == id } ?: error("扩展包已在卸载过程中被修改")
            require(expectedDigest == null || current.digest == expectedDigest) { "扩展包版本已变化" }
            writeIndex(rows.filterNot { it.id == id })
            val directory = File(root, "versions/$id").canonicalFile
            require(directory.toPath().startsWith(File(root, "versions").canonicalFile.toPath()))
            check(!directory.exists() || directory.deleteRecursively()) { "扩展包已停用，但文件清理未完成" }
        }
    }

    fun scripts(record: CapabilityPackage, requireEnabled: Boolean = true): JSONObject = source(record, requireEnabled).getJSONObject("scripts")
    fun imageAsset(record: CapabilityPackage, rawPath: String): ByteArray = locked {
        val current = readIndex().singleOrNull { it.id == record.id && it.digest == record.digest && it.enabled }
            ?: error("插件已停用或更新")
        val path = safePath(rawPath.removePrefix("asset:"))
        require(path.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif")) { "插件图片格式不支持" }
        val directory = versionDirectory(current.id, current.digest).canonicalFile
        require(inspect(directory, current.source).digest == current.digest) { "插件资源校验失败" }
        val image = File(directory, path).canonicalFile
        require(image.toPath().startsWith(directory.toPath()) && image.isFile && image.length() <= 1024 * 1024) { "插件图片不可用或过大" }
        image.inputStream().use { it.readNBytes(1024 * 1024 + 1).also { bytes -> require(bytes.size <= 1024 * 1024) } }
    }
    fun source(record: CapabilityPackage, requireEnabled: Boolean = true): JSONObject = locked {
        val current = readIndex().singleOrNull { it.id == record.id }
        require(current != null && (!requireEnabled || current.enabled) && current.digest == record.digest) { "扩展包已停用或更新" }
        val directory = versionDirectory(record.id, record.digest)
        require(inspect(directory, record.source).digest == record.digest) { "扩展包文件校验失败" }
        val scripts = JSONObject()
        var total = 0L
        directory.walkTopDown().filter { it.isFile && it.extension == "js" }.forEach { file ->
            total += file.length()
            require(total <= MAX_SCRIPTS) { "扩展包脚本合计超过 6 MiB" }
            scripts.put(file.relativeTo(directory).invariantSeparatorsPath, file.readText())
        }
        val manifest = listOf("manifest.json", "manifest.hjson").map { File(directory, it) }.firstOrNull(File::isFile)?.let { parseJson(it.readText()) }
        JSONObject().put("scripts", scripts).put("wasm_modules", PackageWasmBundle.modules(directory, manifest, includeBytes = true))
    }

    fun saveRegistration(record: CapabilityPackage, declarations: JSONArray) = locked {
        require(declarations.length() <= 64 && declarations.toString().toByteArray().size <= 64 * 1024)
        val rows = readIndex()
        val current = rows.single { it.id == record.id && it.digest == record.digest }
        for (index in 0 until declarations.length()) {
            val row = declarations.getJSONObject(index)
            if (row.optString("kind") == "AiProvider") PackageAiRegistry.validate(current, row)
            else if (row.optString("kind") in PackageExtensionKinds.labels) require(PackageExtensionKinds.callback(row) != null) { "扩展回调声明无效" }
        }
        val uiTools = (0 until declarations.length()).mapNotNull { index ->
            val row = declarations.getJSONObject(index)
            val ui = PackageUiRegistration.describe(row) ?: return@mapNotNull null
            val path = safePath(ui.screen ?: requireNotNull(current.mainEntry) { "插件界面缺少主注册入口" })
            require(path.endsWith(".js") && File(versionDirectory(current.id, current.digest), path).isFile) { "插件界面文件不存在" }
            val callback = ui.callback
            val name = "ui_" + MessageDigest.getInstance("SHA-256").digest(callback.toByteArray()).take(12).joinToString("") { "%02x".format(it.toInt() and 255) }
            PackageTool(name, ui.title, path,
                """{"type":"object","properties":{"state":{"type":"object"},"memo":{"type":"object"},"moduleId":{"type":"string"},"moduleSpec":{"type":"object"},"screenCallback":{"type":"string","maxLength":256},"actionId":{"type":"string"},"payload":{},"expectedTree":{"type":"object"},"routes":{"type":"array","maxItems":64}},"required":["state","memo","moduleId","moduleSpec"],"additionalProperties":false}""", callback)
        }
        val actions = (0 until declarations.length()).mapNotNull { i ->
            val row = declarations.getJSONObject(i)
            if (row.optString("kind") !in setOf("NavigationEntry", "ChatMessageMenuItem") + PackageExtensionKinds.automatic) return@mapNotNull null
            val callback = (row.optJSONObject("action") ?: row.optJSONObject("function"))?.optString("nexus_callback")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val automatic = row.optString("kind") in PackageExtensionKinds.automatic
            val name = (if (automatic) "event_" else "action_") + MessageDigest.getInstance("SHA-256").digest(callback.toByteArray()).take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            PackageTool(name, localized(row.opt("title")).ifBlank { row.getString("id") }, checkNotNull(current.mainEntry),
                if (automatic) """{"type":"object","properties":{"eventPayload":{"type":"object"},"eventName":{"type":"string","maxLength":96}},"required":["eventName","eventPayload"],"additionalProperties":false}"""
                else """{"type":"object","properties":{"eventPayload":{"type":"object"}},"additionalProperties":false}""", callback)
        }
        val remainingIssues = current.issues.filterNot { it in setOf("MAIN_REGISTRATION_PENDING", "EXTENSION_SURFACES_PENDING", "UI_MODULES_PENDING") }.toMutableList()
        if ((0 until declarations.length()).any { declarations.getJSONObject(it).optString("kind") !in setOf("NavigationEntry", "ChatMessageMenuItem", "DesktopWidget") + PackageExtensionKinds.labels.keys + PackageExtensionKinds.ui })
            remainingIssues.add("EXTENSION_SURFACES_PENDING")
        if ((0 until declarations.length()).any { declarations.getJSONObject(it).optString("kind") == "ChatMessageMenuItem" && declarations.getJSONObject(it).has("dialog") &&
                PackageUiRegistration.describe(declarations.getJSONObject(it)) == null })
            remainingIssues.add("UI_MODULES_PENDING")
        require(uiTools.map { it.callbackKey }.distinct().size == uiTools.size) { "插件界面编号重复" }
        PackageDesktopWidgetService.validate(declarations)
        if ((0 until declarations.length()).any { declarations.getJSONObject(it).optString("kind") in PackageExtensionKinds.ui && PackageUiRegistration.describe(declarations.getJSONObject(it)) == null })
            remainingIssues.add("UI_MODULES_PENDING")
        writeIndex(rows.map { if (it.id == record.id) current.copy(tools = current.tools.filter { tool -> tool.callbackKey == null } + actions + uiTools,
            registrations = declarations.toString(), issues = remainingIssues.distinct(), enabledExtensions = emptySet()) else it })
    }

    fun conversationActions(kind: String, sender: String = "ai"): List<Pair<CapabilityPackage, PackageTool>> = list().filter { it.enabled && it.mainEntry != null }.flatMap { record ->
        val rows = JSONArray(record.registrations)
        (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index)?.takeIf { it.optString("kind") == kind } ?: return@mapNotNull null
            val senders = row.optJSONArray("senders")
            if (senders != null && senders.length() > 0 && (0 until senders.length()).none { senders.optString(it) in setOf(sender, if (sender == "ai") "assistant" else sender) }) return@mapNotNull null
            val callback = (row.optJSONObject("action") ?: row.optJSONObject("function"))?.optString("nexus_callback") ?: return@mapNotNull null
            record.tools.firstOrNull { it.callbackKey == callback }?.let { record to it }
        }
    }.take(64)

    fun isTrustedTime(record: CapabilityPackage, tool: PackageTool): Boolean = locked {
        record.id == "time" && tool.entry == "main.js" && tool.name in setOf("get_time", "format_time") &&
            runCatching { File(versionDirectory(record.id, record.digest), "main.js").readBytes()
                .contentEquals(context.assets.open("packages/operit/time.js").use { it.readBytes() }) }.getOrDefault(false)
    }

    fun ensureBuiltinTime() = locked {
        val seeded = File(root, "builtin-time-seeded")
        if (seeded.exists()) return@locked
        if (readIndex().any { it.id == "time" }) { seeded.writeText("1"); return@locked }
        val work = File(staging, UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            context.assets.open("packages/operit/time.js").use { copyBounded(it, File(work, "main.js"), MAX_SCRIPT) }
            val record = inspect(work, "builtin:operit:16cffb98")
            val destination = versionDirectory(record.id, record.digest)
            destination.parentFile!!.mkdirs()
            if (!destination.exists()) Files.move(work.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            val installed = record.copy(enabled = true)
            File(destination, VERSION_RECORD).writeText(encode(installed).toString())
            writeIndex(readIndex() + installed)
            seeded.writeText("1")
        } finally { work.deleteRecursively() }
    }

    private fun inspect(directory: File, source: String): CapabilityPackage {
        val manifestFile = listOf("manifest.json", "manifest.hjson").map { File(directory, it) }.firstOrNull(File::isFile)
        val manifest = manifestFile?.let { parseJson(it.readText()) }
        if (manifest != null) {
            require(manifest.optInt("schema_version", 1) == 1) { "不支持的 ToolPkg 清单版本" }
            require(manifest.optString("api_version", "1.0.0") in setOf("1.0.0", "1.0.1", "2.0.0")) { "不支持的 ToolPkg API 版本" }
            require(File(directory, safePath(manifest.getString("main"))).isFile) { "缺少 ToolPkg 主入口" }
        }
        val wasmModules = PackageWasmBundle.modules(directory, manifest, includeBytes = false).length()
        val entries = if (manifest == null) listOf("main.js") else {
            val subpackages = manifest.optJSONArray("subpackages") ?: JSONArray()
            if (subpackages.length() == 0) listOf(safePath(manifest.getString("main")))
            else (0 until subpackages.length()).map { safePath(subpackages.getJSONObject(it).getString("entry")) }
        }
        require(entries.size <= 64)
        val metadata = entries.map { path ->
            val file = File(directory, path)
            require(file.isFile && file.length() <= MAX_SCRIPT) { "缺少脚本入口或脚本过大" }
            val match = METADATA.find(file.readText())
            val meta = match?.groupValues?.get(1)?.let(::parseJson) ?: JSONObject()
            path to meta
        }
        val id = (manifest?.optString("toolpkg_id") ?: metadata.first().second.optString("name")).trim()
        require(PACKAGE_ID.matches(id)) { "扩展包缺少有效的名称或 toolpkg_id" }
        val tools = metadata.flatMap { (entry, meta) ->
            val array = meta.optJSONArray("tools") ?: JSONArray()
            (0 until array.length()).map { index ->
                val tool = array.getJSONObject(index)
                val name = tool.getString("name").also { require(TOOL_NAME.matches(it)) { "扩展工具名称无效" } }
                val properties = JSONObject(); val required = JSONArray()
                val params = tool.optJSONArray("parameters") ?: JSONArray()
                for (parameterIndex in 0 until params.length()) {
                    val parameter = params.getJSONObject(parameterIndex)
                    val key = parameter.getString("name").also { require(TOOL_NAME.matches(it)) }
                    val type = parameter.optString("type", "string")
                    require(type in setOf("string", "number", "integer", "boolean", "array", "object"))
                    val schema = JSONObject().put("type", type).put("description", localized(parameter.opt("description")))
                    parameter.optJSONArray("enum")?.let { schema.put("enum", it) }
                    parameter.optJSONObject("items")?.let { schema.put("items", it) }
                    properties.put(key, schema)
                    if (parameter.optBoolean("required")) required.put(key)
                }
                PackageTool(name, localized(tool.opt("description")), entry,
                    JSONObject().put("type", "object").put("properties", properties).put("required", required).put("additionalProperties", false).toString())
            }
        }
        require(tools.size <= 128 && tools.map { it.entry to it.name }.distinct().size == tools.size)
        val primary = manifest ?: metadata.first().second
        val environment = metadata.flatMap { (_, meta) ->
            val names = meta.optJSONArray("env") ?: JSONArray()
            (0 until names.length()).mapNotNull { i -> names.optString(i).takeIf { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")) } }
        }.distinct().take(32)
        val issues = buildList {
            if (manifest != null) add("MAIN_REGISTRATION_PENDING")
            if (source.startsWith("builtin:operit:") && id != "time") add("OPERIT_SDK_PENDING")
            if (primary.has("states")) add("STATEFUL_TOOLS_PENDING")
            if (manifest?.optJSONArray("ui_modules")?.length()?.let { it > 0 } == true) add("UI_MODULES_PENDING")
        }
        return CapabilityPackage(id, localized(primary.opt("display_name")).ifBlank { id }, localized(primary.opt("description")),
            primary.optString("version", "0.0.0"), if (manifest == null) "plugin" else "sandbox", digest(directory), tools,
            source = source, issues = issues, environmentNames = environment, apiVersion = manifest?.optString("api_version", "1.0.0") ?: "1.0.1",
            mainEntry = manifest?.getString("main")?.let(::safePath), wasmModuleCount = wasmModules)
    }

    private fun extract(archive: File, destination: File) {
        var total = 0L; var count = 0
        val names = hashSetOf<String>()
        @Suppress("DEPRECATION")
        val archiveFile = ZipFile(archive)
        archiveFile.use { zip ->
            val entries = zip.entries
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                require(++count <= 2048 && !entry.isUnixSymlink && zip.canReadEntryData(entry)) { "压缩包条目无效" }
                val path = safePath(entry.name.trimEnd('/'))
                require(names.add(path.lowercase())) { "压缩包有重复路径" }
                val file = File(destination, path)
                if (entry.isDirectory) { check(file.mkdirs() || file.isDirectory); continue }
                require(entry.size in 0..MAX_FILE) { "压缩包单文件过大" }
                file.parentFile!!.mkdirs()
                zip.getInputStream(entry).use { copyBounded(it, file, MAX_FILE) }
                total += file.length()
                require(total <= MAX_EXTRACTED) { "压缩包解压后超过 128 MiB" }
            }
        }
    }

    private fun digest(directory: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0; var size = 0L
        directory.walkTopDown().onEnter { folder ->
            require(!Files.isSymbolicLink(folder.toPath()) && folder.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath())); true
        }.filter { it.isFile && it.relativeTo(directory).invariantSeparatorsPath != VERSION_RECORD }
            .sortedBy { it.relativeTo(directory).invariantSeparatorsPath }.forEach { file ->
            require(!Files.isSymbolicLink(file.toPath()) && ++count <= 2048)
            size += file.length(); require(size <= MAX_EXTRACTED)
            val path = safePath(file.relativeTo(directory).invariantSeparatorsPath)
            digest.update("$path\u0000${file.length()}\u0000".toByteArray())
            file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun contentRoot(directory: File): File {
        if (File(directory, "manifest.json").isFile || File(directory, "manifest.hjson").isFile || File(directory, "main.js").isFile) return directory
        return directory.listFiles()?.singleOrNull()?.takeIf(File::isDirectory) ?: error("压缩包缺少 manifest 或 main.js")
    }
    private fun versionDirectory(id: String, digest: String): File {
        require(PACKAGE_ID.matches(id) && digest.matches(Regex("[a-f0-9]{64}")))
        return File(root, "versions/$id/$digest")
    }
    private fun previewRoot(token: String): File { require(token.matches(Regex("[a-f0-9-]{36}"))); return File(staging, token) }
    private fun readIndex(): List<CapabilityPackage> {
        val legacyBackup = File(root, "index.json.bak")
        if (!index.exists() && legacyBackup.exists()) Files.move(legacyBackup.toPath(), index.toPath())
        if (!index.exists()) return emptyList()
        require(index.length() <= 16L * 1024 * 1024) { "扩展包索引过大" }
        val array = JSONArray(index.readText(Charsets.UTF_8))
        return (0 until array.length()).map { decode(array.getJSONObject(it)) }
    }
    private fun writeIndex(rows: List<CapabilityPackage>) {
        require(rows.size <= 256 && rows.map { it.id }.distinct().size == rows.size)
        val temporary = File(root, "index.json.new")
        try {
            val bytes = JSONArray(rows.map(::encode)).toString().toByteArray()
            require(bytes.size <= 16 * 1024 * 1024) { "扩展包索引超过大小限制" }
            FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            try { Files.move(temporary.toPath(), index.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), index.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            mutableChanges.value++
        } finally { temporary.delete() }
    }
    private fun <T> locked(block: () -> T): T = synchronized(LOCK) {
        root.mkdirs()
        FileChannel.open(File(root, ".lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel -> channel.lock().use { block() } }
    }

    companion object {
        private val mutableChanges = kotlinx.coroutines.flow.MutableStateFlow(0L)
        internal val changes: kotlinx.coroutines.flow.StateFlow<Long> get() = mutableChanges
        private val LOCK = Any()
        private const val VERSION_RECORD = ".nexus-package-version.json"
        private const val MAX_SCRIPT = 4L * 1024 * 1024
        private const val MAX_SCRIPTS = 6L * 1024 * 1024
        private const val MAX_ARCHIVE = 64L * 1024 * 1024
        private const val MAX_FILE = 32L * 1024 * 1024
        private const val MAX_EXTRACTED = 128L * 1024 * 1024
        private val PACKAGE_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")
        private val TOOL_NAME = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,95}")
        private val METADATA = Regex("/\\*\\s*METADATA\\s*(.*?)\\*/", RegexOption.DOT_MATCHES_ALL)
        private fun parseJson(raw: String): JSONObject { require(raw.length <= 256 * 1024); return JSONObject(JsonValue.readHjson(raw).toString()) }
        private fun localized(value: Any?): String = when (value) {
            is String -> value.take(4000)
            is JSONObject -> value.optString("zh").ifBlank { value.optString("en") }.ifBlank { value.keys().asSequence().map { value.optString(it) }.firstOrNull().orEmpty() }.take(4000)
            else -> ""
        }
        private fun safePath(raw: String): String {
            require(raw.isNotBlank() && raw.length <= 1024 && '\\' !in raw && ':' !in raw && !raw.startsWith('/') && '\u0000' !in raw)
            require(raw.split('/').let { it.size <= 16 && it.none { p -> p.isBlank() || p == "." || p == ".." } })
            return raw
        }
        private fun copyBounded(input: InputStream, destination: File, maximum: Long) {
            destination.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024); var total = 0L
                while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= maximum) { "文件超过大小限制" }; output.write(buffer, 0, count) }
            }
        }
        internal fun encode(value: CapabilityPackage) = JSONObject().put("id", value.id).put("title", value.title)
            .put("description", value.description).put("version", value.version).put("kind", value.kind).put("digest", value.digest)
            .put("enabled", value.enabled).put("allowed_tools", JSONArray(value.allowedTools.sorted())).put("source", value.source)
            .put("previous", value.previous ?: JSONObject.NULL).put("memory_mb", value.memoryMb).put("timeout_seconds", value.timeoutSeconds)
            .put("issues", JSONArray(value.issues)).put("environment_names", JSONArray(value.environmentNames)).put("api_version", value.apiVersion)
            .put("main_entry", value.mainEntry ?: JSONObject.NULL).put("registrations", JSONArray(value.registrations))
            .put("wasm_module_count", value.wasmModuleCount)
            .put("enabled_extensions", JSONArray(value.enabledExtensions.sorted()))
            .put("tools", JSONArray(value.tools.map { tool -> JSONObject().put("name", tool.name)
                .put("description", tool.description).put("entry", tool.entry).put("schema", tool.inputSchema).put("callback_key", tool.callbackKey ?: JSONObject.NULL) }))
        private fun decode(value: JSONObject): CapabilityPackage {
            val tools = value.getJSONArray("tools"); val allowed = value.optJSONArray("allowed_tools") ?: JSONArray()
            val issues = value.optJSONArray("issues") ?: JSONArray()
            val environment = value.optJSONArray("environment_names") ?: JSONArray()
            return CapabilityPackage(value.getString("id"), value.getString("title"), value.optString("description"), value.optString("version"),
                value.getString("kind"), value.getString("digest"), (0 until tools.length()).map { i -> tools.getJSONObject(i).let {
                    PackageTool(it.getString("name"), it.optString("description"), it.getString("entry"), it.getString("schema"),
                        it.optString("callback_key").takeIf { key -> it.opt("callback_key") != JSONObject.NULL && key.isNotBlank() }) } },
                value.optBoolean("enabled"), (0 until allowed.length()).map { allowed.getString(it) }.toSet(), value.optString("source"),
                value.optString("previous").takeIf { value.opt("previous") != JSONObject.NULL && it.isNotBlank() },
                value.optInt("memory_mb", 64), value.optInt("timeout_seconds", 120), (0 until issues.length()).map { issues.getString(it) }.filterNot { it == "WASM_MODULES_PENDING" },
                (0 until environment.length()).map { environment.getString(it) }, value.optString("api_version", "1.0.1"),
                value.optString("main_entry").takeIf { value.opt("main_entry") != JSONObject.NULL && it.isNotBlank() }, value.optJSONArray("registrations")?.toString() ?: "[]",
                value.optInt("wasm_module_count", 0).coerceIn(0, 4), value.optJSONArray("enabled_extensions")?.let { values ->
                    (0 until values.length()).map { values.optString(it) }.filter { it in PackageExtensionKinds.labels }.toSet()
                }.orEmpty())
        }
    }
}
