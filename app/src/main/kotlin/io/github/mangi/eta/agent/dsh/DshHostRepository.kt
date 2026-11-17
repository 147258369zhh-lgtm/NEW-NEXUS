package io.github.mangi.eta.agent.dsh

import android.content.Context
import io.github.mangi.eta.agent.connector.ConnectorConfigStore
import io.github.mangi.eta.agent.connector.SavedConnectorConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** DSH connections and encrypted credentials reuse the existing Connector owner. */
internal class DshHostRepository(context: Context, private val configs: ConnectorConfigStore = ConnectorConfigStore(context)) {
    private val context = context.applicationContext
    private val cache = File(this.context.filesDir, "capability-center/dsh-catalogs.json")
    fun hosts(): List<SavedConnectorConfig> = configs.load().filter { it.protocol == "dsh" }
    fun save(id: String?, name: String, endpoint: String, token: String): SavedConnectorConfig {
        val (record, secret) = prepare(id, name, endpoint, token)
        return configs.save(record, secret)
    }
    suspend fun saveFromUi(id: String?, name: String, endpoint: String, token: String): SavedConnectorConfig {
        val (record, secret) = prepare(id, name, endpoint, token)
        return io.github.mangi.eta.agent.connector.ConnectorSettingsService(context, configs).save(record, secret)
    }
    private fun prepare(id: String?, name: String, endpoint: String, token: String): Pair<SavedConnectorConfig, String?> {
        val parsed = endpoint.trim().removePrefix("dsh web: ").toHttpUrlOrNull() ?: error("DSH 主机地址无效")
        val fromUrl = parsed.queryParameter("token")
        require(parsed.query == null || parsed.queryParameterNames == setOf("token") && parsed.queryParameterValues("token").size == 1) { "主机地址包含不支持的参数" }
        val base = parsed.newBuilder().query(null).build().toString()
        DshHostClient.normalizeEndpoint(base)
        val existing = id?.let { value -> hosts().single { it.id == value } }
        val chosenToken = token.takeIf(String::isNotBlank) ?: fromUrl
        require(name.isNotBlank())
        val record = SavedConnectorConfig(existing?.id.orEmpty(), name, "dsh", endpoint = base, enabled = existing?.enabled ?: true,
            configurationRevision = existing?.configurationRevision ?: 0)
        if (existing != null && existing.endpoint != base) require(!chosenToken.isNullOrBlank()) { "更换主机地址时请重新填写令牌" }
        return record to chosenToken?.let { DshCredentials(token = it).json() }
    }
    fun setEnabled(host: SavedConnectorConfig, enabled: Boolean) { configs.save(current(host).copy(enabled = enabled)) }
    fun delete(host: SavedConnectorConfig) { configs.remove(current(host).id); synchronized(LOCK) { val root = cacheRoot(); root.remove(host.id); write(root) } }
    suspend fun setEnabledFromUi(host: SavedConnectorConfig, enabled: Boolean) {
        io.github.mangi.eta.agent.connector.ConnectorSettingsService(context, configs).save(host.copy(enabled = enabled))
    }
    suspend fun deleteFromUi(host: SavedConnectorConfig) {
        io.github.mangi.eta.agent.connector.ConnectorSettingsService(context, configs).remove(host)
        synchronized(LOCK) { val root = cacheRoot(); root.remove(host.id); write(root) }
    }
    fun client(host: SavedConnectorConfig): DshHostClient {
        current(host).also { check(it.enabled && it.configurationRevision == host.configurationRevision) { "DSH 主机已停用或配置已变化" } }
        val stored = configs.tokenOrThrow(host)
        val initial = DshCredentials.parse(stored)
        return DshHostClient(host.endpoint, initial, persistCredentials = { credential ->
            val live = current(host)
            check(live.enabled && live.configurationRevision == host.configurationRevision && live.credentialRevision == host.credentialRevision && DshCredentials.parse(configs.tokenOrThrow(live)).token == initial.token) { "DSH 连接或凭据已变化" }
            configs.updateToken(host.id, credential.json(), identityChanged = false, expectedRevision = host.credentialRevision)
        }, available = {
            runCatching { val current = current(host); current.enabled && current.configurationRevision == host.configurationRevision && current.credentialRevision == host.credentialRevision &&
                DshCredentials.parse(configs.tokenOrThrow(current)).token == initial.token }.getOrDefault(false)
        })
    }
    fun isCurrent(host: SavedConnectorConfig): Boolean = runCatching { val current = current(host); current.enabled &&
        current.configurationRevision == host.configurationRevision && current.credentialRevision == host.credentialRevision }.getOrDefault(false)
    fun conversationScope(host: SavedConnectorConfig, subjectKey: String): JSONObject = synchronized(LOCK) {
        current(host)
        val saved = cacheRoot().optJSONObject(host.id) ?: return@synchronized JSONObject()
        if (saved.optString("endpoint") != host.endpoint || saved.optLong("credential_revision", 0) != host.credentialRevision) return@synchronized JSONObject()
        val value = saved.optJSONObject("bindings")?.optString(bindingKey(subjectKey)).orEmpty()
        if (value.isBlank()) JSONObject() else DshProtocol.scope(JSONObject().put("sessionId", value))
    }
    fun bindConversation(host: SavedConnectorConfig, subjectKey: String, remoteSession: String, messageId: String? = null, remoteTurn: String = "") = synchronized(LOCK) {
        check(isCurrent(host)) { "DSH 主机已停用或更新" }
        require(subjectKey.length in 1..1024)
        val remote = remoteSession.trim()
        require(remote.isEmpty() || remote.length in 1..256 && remote.none(Char::isISOControl)) { "远端会话编号无效" }
        val turn = remoteTurn.trim()
        if (messageId != null) require(messageId.length in 1..512 && (turn.isEmpty() || turn.length <= 64 && turn.none(Char::isISOControl) && turn !in setOf("__proto__", "prototype", "constructor"))) { "远端回合编号无效" }
        val root = cacheRoot()
        val saved = root.optJSONObject(host.id)?.takeIf { it.optString("endpoint") == host.endpoint && it.optLong("credential_revision", 0) == host.credentialRevision }
            ?: JSONObject().put("endpoint", host.endpoint).put("credential_revision", host.credentialRevision)
        val bindings = saved.optJSONObject("bindings") ?: JSONObject()
        val key = bindingKey(subjectKey)
        if (bindings.optString(key) != remote) saved.optJSONObject("turn_links")?.remove(key)
        if (remote.isEmpty()) bindings.remove(key) else { require(bindings.has(key) || bindings.length() < 512) { "会话关联数量超过限制" }; bindings.put(key, remote) }
        if (messageId != null && remote.isNotEmpty()) {
            val turns = saved.optJSONObject("turn_links") ?: JSONObject()
            val linked = turns.optJSONObject(key)?.takeIf { it.optString("session") == remote } ?: JSONObject().put("session", remote)
            val rows = linked.optJSONObject("messages") ?: JSONObject()
            val message = localMessageKey(messageId)
            if (turn.isEmpty()) rows.remove(message) else { require(rows.has(message) || rows.length() < 512) { "消息关联数量超过限制" }; rows.put(message, turn) }
            linked.put("messages", rows); turns.put(key, linked); saved.put("turn_links", turns)
        }
        saved.put("bindings", bindings); root.put(host.id, saved); write(root)
        mutableScopeChanges.value++
    }
    fun messageTurns(host: SavedConnectorConfig, subjectKey: String): Map<String, String> = synchronized(LOCK) {
        val remote = conversationScope(host, subjectKey).optString("sessionId")
        if (remote.isBlank()) return@synchronized emptyMap()
        val saved = cacheRoot().optJSONObject(host.id)?.optJSONObject("turn_links")?.optJSONObject(bindingKey(subjectKey)) ?: return@synchronized emptyMap()
        if (saved.optString("session") != remote) return@synchronized emptyMap()
        val rows = saved.optJSONObject("messages") ?: return@synchronized emptyMap()
        rows.keys().asSequence().take(512).mapNotNull { key -> rows.optString(key).takeIf { it.length in 1..64 && it.none(Char::isISOControl) }?.let { key to it } }.toMap()
    }
    fun voiceAllowed(host: SavedConnectorConfig, pkg: String): Boolean = pkg in voicesAllowed(host)
    fun voicesAllowed(host: SavedConnectorConfig): Set<String> = synchronized(LOCK) {
        if (!isCurrent(host)) return@synchronized emptySet()
        val saved = cacheRoot().optJSONObject(host.id) ?: return@synchronized emptySet()
        if (saved.optString("endpoint") != host.endpoint || saved.optLong("credential_revision", 0) != host.credentialRevision) return@synchronized emptySet()
        val permissions = saved.optJSONObject("voice_permissions") ?: return@synchronized emptySet()
        permissions.keys().asSequence().filter { DshProtocol.packageId.matches(it) && permissions.optBoolean(it) }.toSet()
    }
    fun setVoiceAllowed(host: SavedConnectorConfig, pkg: String, allowed: Boolean) = synchronized(LOCK) {
        check(isCurrent(host)); require(DshProtocol.packageId.matches(pkg))
        val root = cacheRoot()
        val saved = root.optJSONObject(host.id)?.takeIf { it.optString("endpoint") == host.endpoint && it.optLong("credential_revision", 0) == host.credentialRevision }
            ?: JSONObject().put("endpoint", host.endpoint).put("credential_revision", host.credentialRevision)
        val permissions = saved.optJSONObject("voice_permissions") ?: JSONObject()
        if (allowed) permissions.put(pkg, true) else permissions.remove(pkg)
        saved.put("voice_permissions", permissions); root.put(host.id, saved); write(root); mutableScopeChanges.value++
    }
    fun bindMessageTurn(host: SavedConnectorConfig, subjectKey: String, messageId: String, remoteTurn: String) = synchronized(LOCK) {
        check(isCurrent(host)) { "DSH 主机已停用或更新" }
        val remote = conversationScope(host, subjectKey).optString("sessionId")
        check(remote.isNotBlank()) { "请先关联远端会话" }
        bindConversation(host, subjectKey, remote, messageId, remoteTurn)
    }
    suspend fun refresh(host: SavedConnectorConfig): DshCatalog = client(host).use { client ->
        val catalog = client.contributions()
        check(isCurrent(host)) { "DSH 主机已停用或移除" }
        synchronized(LOCK) {
            val root = cacheRoot()
            val saved = root.optJSONObject(host.id)?.takeIf { it.optString("endpoint") == host.endpoint && it.optLong("credential_revision", 0) == host.credentialRevision } ?: JSONObject()
            root.put(host.id, saved.put("endpoint", host.endpoint).put("credential_revision", host.credentialRevision)
                .put("saved_at", System.currentTimeMillis()).put("catalog", encode(catalog)))
            write(root)
        }
        catalog
    }
    fun cached(host: SavedConnectorConfig): DshCatalog? = synchronized(LOCK) {
        val saved = cacheRoot().optJSONObject(host.id) ?: return@synchronized null
        if (saved.optString("endpoint") != host.endpoint || saved.optLong("credential_revision", 0) != host.credentialRevision) null
        else runCatching { DshProtocol.catalog(saved.getJSONObject("catalog")) }.getOrNull()
    }
    private fun current(host: SavedConnectorConfig): SavedConnectorConfig = hosts().singleOrNull { it.id == host.id && it.endpoint == host.endpoint }
        ?: error("DSH 连接已移除或地址已变化")
    private fun cacheRoot(): JSONObject {
        if (!cache.isFile) return JSONObject()
        return DshProtocol.parse(cache.readText(), 4 * 1024 * 1024)
    }
    private fun write(root: JSONObject) {
        cache.parentFile!!.mkdirs()
        val temporary = File(cache.parentFile, "${cache.name}.new")
        val bytes = root.toString().toByteArray(); require(bytes.size <= 4 * 1024 * 1024)
        FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
        try { Files.move(temporary.toPath(), cache.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        finally { temporary.delete() }
    }
    companion object {
        private val LOCK = Any()
        private val mutableScopeChanges = kotlinx.coroutines.flow.MutableStateFlow(0L)
        internal val scopeChanges: kotlinx.coroutines.flow.StateFlow<Long> get() = mutableScopeChanges
        fun localMessageKey(messageId: String) = bindingKey(messageId)
        private fun bindingKey(subject: String) = java.security.MessageDigest.getInstance("SHA-256").digest(subject.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        private fun encode(catalog: DshCatalog) = JSONObject().put("v", 1).put("rev", catalog.revision).put("bridge", catalog.bridge)
            .put("plugins", JSONArray(catalog.plugins.map { plugin ->
                val groups = JSONObject()
                plugin.contributions.groupBy { it.surface }.forEach { (surface, items) -> groups.put(surface, JSONArray(items.map { it.raw })) }
                JSONObject().put("pkg", plugin.pkg).put("title", plugin.title).put("version", plugin.version).put("state", plugin.state)
                    .put("native", plugin.state == "ok").put("wants", JSONArray(plugin.wants.toList())).put("contributes", groups)
                    .put("capabilities", JSONArray(plugin.capabilities.toList()))
            }))
    }
}
