package io.github.mangi.eta.agent.terminal

import android.content.Context
import java.io.File
import java.net.URI
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class LinuxSourceEntry(val id: String, val name: String, val distribution: LinuxDistribution,
    val kind: String, val archiveUrl: String, val securityUrl: String? = null)

/** Mirrors are transport settings. Archive size and digest always remain anchored to official metadata. */
internal object LinuxSourceSettings {
    const val ROOTFS = "rootfs"
    const val APK = "apk"
    const val APT = "apt"
    const val TOOL_ARCHIVE = "tool-archive"
    const val UV = "uv-archive"
    const val NODE = "node-archive"
    const val GO = "go-archive"
    const val JDK = "jdk-archive"
    const val MAVEN = "maven-archive"
    const val GRADLE = "gradle-archive"
    const val NPM = "npm"
    const val PYPI = "pypi"
    const val PYTHON = "python-archive"
    const val RUST_DIST = "rust-dist"
    const val RUSTUP = "rustup"
    const val CARGO = "cargo"
    val kinds = listOf(ROOTFS, APK, APT, UV, NODE, GO, JDK, MAVEN, GRADLE, NPM, PYPI, PYTHON, RUST_DIST, RUSTUP, CARGO, TOOL_ARCHIVE)
    private val placeholders = setOf("version", "file", "arch", "official")
    fun kindsFor(distribution: LinuxDistribution) = kinds.filter { kind ->
        kind !in setOf(RUSTUP, CARGO) &&
            if(distribution == LinuxDistribution.ALPINE) kind !in setOf(APT, NODE, JDK) else kind != APK
    }
    fun label(kind: String) = when(kind) {
        ROOTFS -> "RootFS 系统镜像"; APK -> "APK 软件包"; APT -> "APT 软件包"; UV -> "uv 工具归档"; NODE -> "Node.js 归档"
        GO -> "Go SDK 归档"; JDK -> "JDK 归档"; NPM -> "npm 软件包"; PYPI -> "PyPI 软件包"; PYTHON -> "Python 独立归档"
        RUST_DIST -> "Rust 工具链"; RUSTUP -> "rustup 更新"; CARGO -> "Cargo crates"; else -> "通用工具归档"
    }
    private fun read(context: Context): JSONObject = runCatching { JSONObject(fileFor(context).readText()) }.getOrElse { JSONObject() }
    fun entries(context: Context): List<LinuxSourceEntry> {
        val rows = read(context).optJSONArray("sources") ?: JSONArray()
        return buildList { for(i in 0 until rows.length()) rows.optJSONObject(i)?.let(::parse)?.let(::add) }
    }
    @Synchronized fun add(context: Context, name: String, distribution: LinuxDistribution, kind: String, archiveUrl: String, securityUrl: String? = null): List<LinuxSourceEntry> {
        val entry = validate(LinuxSourceEntry("source-${UUID.randomUUID()}", name.trim(), distribution, kind, archiveUrl.trim(), securityUrl?.trim()?.takeIf(String::isNotBlank)))
        val next = entries(context) + entry
        require(next.size <= 64) { "最多可保存 64 个自定义源" }
        write(context, next); return next
    }
    @Synchronized fun delete(context: Context, id: String): List<LinuxSourceEntry> {
        val next = entries(context).filterNot { it.id == id }; write(context, next); return next
    }
    fun selectedId(context: Context, distribution: LinuxDistribution, kind: String): String =
        read(context).optJSONObject("preferred")?.optString("${distribution.name}:$kind", "china") ?: "china"
    @Synchronized fun select(context: Context, distribution: LinuxDistribution, kind: String, id: String) {
        require(id in available(context, distribution, kind).map { it.id })
        val json = read(context); val preferred = json.optJSONObject("preferred") ?: JSONObject()
        preferred.put("${distribution.name}:$kind", id); json.put("preferred", preferred); save(context, json)
    }
    fun available(context: Context, distribution: LinuxDistribution, kind: String): List<LinuxSourceEntry> =
        entries(context).filter { it.distribution == distribution && it.kind == kind } + builtins(distribution, kind)
    fun ordered(context: Context, distribution: LinuxDistribution, kind: String): List<LinuxSourceEntry> {
        val values = available(context, distribution, kind); val selected = selectedId(context, distribution, kind)
        return values.sortedBy { if(it.id == selected) 0 else if(it.id.startsWith("source-")) 1 else 2 }
    }
    fun sourceUrls(context: Context, distribution: LinuxDistribution, kind: String): List<String> = ordered(context, distribution, kind).map { it.archiveUrl }.distinct()
    fun resolveArtifact(context: Context, distribution: LinuxDistribution, artifact: VerifiedArtifact, kind: String = TOOL_ARCHIVE): VerifiedArtifact {
        val arch = artifactArchitecture(artifact)
        val values = ordered(context, distribution, kind) + entries(context).filter { it.distribution == distribution && it.kind == TOOL_ARCHIVE }
        val urls = values.map {
            if(kind == RUST_DIST && !it.archiveUrl.contains('{')) validateUrl(it.archiveUrl.trimEnd('/') + URI(artifact.url).rawPath, false)
            else expand(it.archiveUrl, artifact.version, artifact.fileName, arch, artifact.url)
        }
        return artifact.copy(preferredUrls = urls.distinct(), fallbackUrls = (artifact.preferredUrls + artifact.fallbackUrls).distinct())
    }
    internal fun artifactArchitecture(artifact: VerifiedArtifact): String {
        if(artifact.id.startsWith("maven") || artifact.id.startsWith("gradle") || artifact.fileName.endsWith(".jar")) return "all"
        val text = artifact.fileName + " " + artifact.id
        return listOf("aarch64", "x86_64", "arm64", "amd64", "x64").firstOrNull { arch ->
            Regex("(?:^|[-_. ])${Regex.escape(arch)}(?:[-_. ]|${'$'})").containsMatchIn(text)
        } ?: error("归档缺少支持的体系结构")
    }
    fun expand(raw: String, version: String, file: String, arch: String, official: String): String {
        var result = raw
        mapOf("version" to version, "file" to file, "arch" to arch, "official" to official).forEach { (key,value) -> result = result.replace("{$key}", value) }
        if(!raw.contains('{') && !result.endsWith(file) && !result.endsWith(".tar.xz") && !result.endsWith(".tar.gz") && !result.endsWith(".jar") && !result.endsWith(".zip")) result = "${result.trimEnd('/')}/$file"
        return validateUrl(result, templates = false)
    }
    fun builtins(distribution: LinuxDistribution, kind: String): List<LinuxSourceEntry> {
        val pair = when(kind) {
            ROOTFS -> if(distribution == LinuxDistribution.ALPINE) "https://mirrors.aliyun.com/alpine/v3.24/releases/{arch}/{file}" to "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/{arch}/{file}" else "https://gh-proxy.com/{official}" to "{official}"
            APK -> "https://mirrors.aliyun.com/alpine" to "https://dl-cdn.alpinelinux.org/alpine"
            APT -> "https://mirrors.tuna.tsinghua.edu.cn/debian" to "https://deb.debian.org/debian"
            NODE -> "https://cdn.npmmirror.com/binaries/node/v{version}/{file}" to "https://nodejs.org/download/release/v{version}/{file}"
            GO -> "https://mirrors.aliyun.com/golang/{file}" to "https://go.dev/dl/{file}"
            JDK -> "https://mirrors.huaweicloud.com/openjdk/{version}/{file}" to "{official}"
            MAVEN -> "https://mirrors.tuna.tsinghua.edu.cn/apache/maven/maven-3/{version}/binaries/{file}" to "https://downloads.apache.org/maven/maven-3/{version}/binaries/{file}"
            GRADLE -> "https://mirrors.cloud.tencent.com/gradle/{file}" to "https://services.gradle.org/distributions/{file}"
            NPM -> "https://registry.npmmirror.com" to "https://registry.npmjs.org"
            PYPI -> "https://pypi.tuna.tsinghua.edu.cn/simple" to "https://pypi.org/simple"
            PYTHON -> "https://gh-proxy.com/https://github.com/astral-sh/python-build-standalone/releases/download" to "https://github.com/astral-sh/python-build-standalone/releases/download"
            RUST_DIST -> "https://rsproxy.cn" to "https://static.rust-lang.org"
            RUSTUP -> "https://rsproxy.cn/rustup" to "https://static.rust-lang.org/rustup"
            CARGO -> "https://rsproxy.cn/index/" to "https://index.crates.io/"
            else -> "https://gh-proxy.com/{official}" to "{official}"
        }
        return listOf(LinuxSourceEntry("china", "中国源", distribution, kind, pair.first,
            if(kind == APT) "https://mirrors.tuna.tsinghua.edu.cn/debian-security" else null),
            LinuxSourceEntry("overseas", "海外官方源", distribution, kind, pair.second,
                if(kind == APT) "https://security.debian.org/debian-security" else null))
    }
    internal fun validateUrl(raw: String, templates: Boolean = true): String {
        require(raw.length in 1..2048 && raw.none { it.isISOControl() || it.isWhitespace() || it in "'\"`\\$;|<>()" }) { "来源地址不能包含命令、空格或控制字符" }
        // Android's ICU regex parser rejects an unescaped closing brace. Parse the
        // small placeholder grammar directly so JVM and device validation agree.
        val fields = buildList {
            var fieldStart = -1
            raw.forEachIndexed { index, character ->
                when (character) {
                    '{' -> {
                        require(fieldStart < 0)
                        fieldStart = index + 1
                    }
                    '}' -> {
                        require(fieldStart >= 0 && index > fieldStart)
                        add(raw.substring(fieldStart, index))
                        fieldStart = -1
                    }
                }
            }
            require(fieldStart < 0)
        }
        require(templates || fields.isEmpty())
        require(fields.all { it in placeholders }) { "仅支持 {version}、{file}、{arch}、{official} 占位符" }
        var test = raw
        fields.forEach { field -> test = test.replace("{$field}", if(field == "official") "https://example.org/archive.tar.gz" else "value") }
        require(!test.contains('{') && !test.contains('}'))
        val uri = runCatching { URI(test) }.getOrNull()
        require(uri != null && uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null && uri.port in setOf(-1,443)) { "Linux 源必须使用无凭据的 HTTPS 地址" }
        require(uri!!.rawPath.orEmpty().split('/').none { it == "." || it == ".." } && !Regex("(?i)%2e|%2f|%5c").containsMatchIn(uri.rawPath.orEmpty())) { "来源路径不能包含跳转或编码路径分隔符" }
        return raw
    }
    private fun validate(entry: LinuxSourceEntry): LinuxSourceEntry {
        require(entry.id.matches(Regex("[A-Za-z0-9-]{1,80}")))
        require(entry.kind in kinds && entry.kind != if(entry.distribution == LinuxDistribution.ALPINE) APT else APK)
        require(entry.name.length in 1..80 && entry.name.none(Char::isISOControl))
        return entry.copy(archiveUrl = validateUrl(entry.archiveUrl, entry.kind !in setOf(APK, APT, NPM, PYPI, PYTHON, RUSTUP, CARGO)), securityUrl = entry.securityUrl?.let { validateUrl(it, false) })
    }
    private fun parse(v: JSONObject): LinuxSourceEntry? = runCatching { validate(LinuxSourceEntry(v.getString("id"),v.getString("name"),LinuxDistribution.valueOf(v.getString("distribution")),v.getString("kind"),v.getString("archiveUrl"),v.optString("securityUrl").takeIf { it.isNotBlank() && it != "null" })) }.getOrNull()
    private fun fileFor(context: Context) = File(context.applicationContext.filesDir, "linux-environment/sources.json")
    private fun write(context: Context, entries: List<LinuxSourceEntry>) {
        val json = read(context).put("version", 2).put("sources", JSONArray().also { rows -> entries.forEach { e -> rows.put(JSONObject().put("id",e.id).put("name",e.name).put("distribution",e.distribution.name).put("kind",e.kind).put("archiveUrl",e.archiveUrl).put("securityUrl",e.securityUrl ?: JSONObject.NULL)) } })
        save(context, json)
    }
    private fun save(context: Context, json: JSONObject) {
        val f = fileFor(context); check(f.parentFile?.mkdirs() == true || f.parentFile?.isDirectory == true)
        val temp = File.createTempFile("linux-sources-", ".tmp", f.parentFile)
        try { temp.writeText(json.toString()); check(temp.renameTo(f) || runCatching { temp.copyTo(f, overwrite=true); true }.getOrDefault(false)) } finally { temp.delete() }
    }
}
