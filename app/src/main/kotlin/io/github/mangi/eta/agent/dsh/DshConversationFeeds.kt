package io.github.mangi.eta.agent.dsh

import android.content.Context
import io.github.mangi.eta.agent.connector.SavedConnectorConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

internal data class DshNativeTarget(val host: SavedConnectorConfig, val pkg: String, val id: String, val surface: String,
    val version: String, val declaration: String)
internal data class DshNativeAction(val target: DshNativeTarget, val title: String, val icon: String, val ready: Boolean)
internal data class DshNativeStrip(val target: DshNativeTarget, val author: String, val text: String, val tone: String, val icon: String, val tappable: Boolean)
internal data class DshNativeNote(val target: DshNativeTarget, val author: String, val text: String, val tone: String)
internal data class DshNativeSnapshot(val actions: List<DshNativeAction> = emptyList(), val strips: List<DshNativeStrip> = emptyList(),
    val notes: Map<String, List<DshNativeNote>> = emptyMap(), val online: Set<String> = emptySet(), val available: Boolean = false)

/** Native slots subscribe to real remote scopes; local reply identities only index explicit links. */
internal class DshConversationFeeds(context: Context, private val subjectKey: String,
    private val repository: DshHostRepository = DshHostRepository(context),
    private val acquire: suspend (SavedConnectorConfig) -> DshSharedSessions.Lease = { DshSharedSessions.acquireAsync(context, repository, it) }) {
    private data class Config(val host: SavedConnectorConfig, val scope: JSONObject, val turns: Map<String, String>)
    private data class Worker(val config: MutableStateFlow<Config>, val job: Job)
    private val context = context.applicationContext
    private val updates = MutableStateFlow(0L)
    private val mutableSnapshot = MutableStateFlow(DshNativeSnapshot())
    private val mutableConfigured = MutableStateFlow(false)
    private val hosts = linkedMapOf<String, DshNativeSnapshot>()
    val snapshot = mutableSnapshot.asStateFlow()
    val configured = mutableConfigured.asStateFlow()
    fun reload() { updates.value++ }

    suspend fun maintain(): Unit = coroutineScope {
        val workers = linkedMapOf<String, Worker>()
        try {
            combine(updates, io.github.mangi.eta.agent.connector.ConnectorConfigStore.changes, DshHostRepository.scopeChanges) { a, b, c -> Triple(a, b, c) }.collect {
                val loaded = withContext(Dispatchers.IO) { repository.hosts().filter { it.enabled }.take(16).map { host ->
                    Config(host, repository.conversationScope(host, subjectKey), repository.messageTurns(host, subjectKey))
                } }
                mutableConfigured.value = loaded.isNotEmpty()
                val ids = loaded.map { it.host.id }.toSet()
                workers.keys.filter { it !in ids }.forEach { id -> workers.remove(id)?.job?.cancelAndJoin(); publish(id, DshNativeSnapshot()) }
                for (config in loaded) {
                    val id = config.host.id; val old = workers[id]
                    if (old != null && old.config.value.host.endpoint == config.host.endpoint &&
                        old.config.value.host.credentialRevision == config.host.credentialRevision &&
                        old.config.value.host.configurationRevision == config.host.configurationRevision) {
                        if (old.config.value.host != config.host || old.config.value.scope.toString() != config.scope.toString() || old.config.value.turns != config.turns) old.config.value = config
                    } else {
                        old?.job?.cancelAndJoin()
                        val state = MutableStateFlow(config)
                        workers[id] = Worker(state, launch { host(state) })
                    }
                }
            }
        } finally {
            workers.values.forEach { it.job.cancel() }
            withContext(NonCancellable) { workers.values.forEach { it.job.join() }; hosts.clear(); mutableSnapshot.value = DshNativeSnapshot() }
        }
    }
    private suspend fun host(config: StateFlow<Config>) {
        val first = config.value
        var lease: DshSharedSessions.Lease? = null
        try {
            lease = acquire(first.host)
            lease.visible(true)
            val session = lease.session
            combine(session.stream, session.catalog, config) { stream, catalog, current -> Triple(stream, catalog, current) }
              .distinctUntilChangedBy { (stream, catalog, current) -> listOf(stream, catalog?.let(::signature), current.host, current.scope.toString(), current.turns) }
              .collectLatest { (stream, catalog, current) ->
                if (!repository.isCurrent(current.host)) { publish(first.host.id, DshNativeSnapshot()); return@collectLatest }
                if (catalog == null) { publish(first.host.id, DshNativeSnapshot()); return@collectLatest }
                val strips = catalog.plugins.flatMap { plugin -> plugin.contributions.filter { it.surface == "composer.strip" && it.ready }.map { plugin to it } }.take(8)
                val notes = if (current.scope.optString("sessionId").isBlank()) emptyList() else catalog.plugins.flatMap { plugin ->
                    plugin.contributions.filter { it.surface == "turn.note" && it.ready }.map { plugin to it }
                }.take(8)
                val subscriptions = linkedMapOf<String, Pair<DshPlugin, DshContribution>>()
                try {
                    if (stream != null && stream.connected.value) {
                        (strips.filter { it.second.live } + notes).forEach { pair ->
                            subscriptions[stream.subscribe(pair.first.pkg, pair.second.id, current.scope)] = pair
                        }
                        stream.feeds.collect { feeds ->
                            if (!repository.isCurrent(current.host) || session.stream.value !== stream) publish(first.host.id, DshNativeSnapshot())
                            else publish(first.host.id, build(current, catalog, strips, subscriptions, feeds, stream.features, true))
                        }
                    } else publish(first.host.id, build(current, catalog, strips, emptyMap(), emptyMap(), emptySet(), false))
                } finally { if (stream != null) subscriptions.keys.forEach(stream::unsubscribe) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { publish(first.host.id, DshNativeSnapshot()) }
        finally { lease?.close(); publish(first.host.id, DshNativeSnapshot()) }
    }
    private fun publish(id: String, value: DshNativeSnapshot) {
        hosts[id] = value
        mutableSnapshot.value = DshNativeSnapshot(hosts.values.flatMap { it.actions }.take(8), hosts.values.flatMap { it.strips }.take(8),
            hosts.values.flatMap { it.notes.entries }.groupBy({ it.key }, { it.value }).mapValues { (_, values) -> values.flatten().take(8) },
            hosts.values.flatMap { it.online }.toSet(), hosts.values.any { it.available })
    }
    private fun build(config: Config, catalog: DshCatalog, strips: List<Pair<DshPlugin, DshContribution>>,
        subscriptions: Map<String, Pair<DshPlugin, DshContribution>>, feeds: Map<String, DshFeed>, features: Set<String>, connected: Boolean): DshNativeSnapshot {
        fun target(plugin: DshPlugin, item: DshContribution) = DshNativeTarget(config.host, plugin.pkg, item.id, item.surface, plugin.version, item.raw.toString())
        val actions = catalog.plugins.flatMap { plugin -> plugin.contributions.filter { it.surface == "composer.actions" && it.ready }.map { item ->
            DshNativeAction(target(plugin, item), item.title, item.icon, connected && features.containsAll(DshProtocol.requiredFeatures(item)))
        } }.take(8)
        val rows = strips.mapNotNull { (plugin, item) ->
            val sub = subscriptions.entries.firstOrNull { it.value.first.pkg == plugin.pkg && it.value.second.id == item.id }?.key
            val feed = sub?.let(feeds::get)
            val data = feed?.data as? JSONObject
            val raw = if (data != null) (data.opt("text") as? String).orEmpty() else (item.raw.opt("text") as? String).orEmpty()
            val text = singleLine(raw, 24)
            if (text.isBlank() || item.live && (!connected || feed?.active != true)) null else DshNativeStrip(target(plugin, item), plugin.title, text,
                data?.optString("tone").orEmpty(), data?.optString("icon").takeUnless { it.isNullOrBlank() } ?: item.icon, connected && item.raw.optBoolean("tappable"))
        }
        val messages = linkedMapOf<String, MutableList<DshNativeNote>>()
        subscriptions.forEach { (sub, pair) ->
            if (pair.second.surface != "turn.note" || !connected) return@forEach
            val feed = feeds[sub]?.takeIf { it.active } ?: return@forEach
            val data = feed.data as? JSONObject ?: return@forEach
            val allowedTurns = data.keys().asSequence().toList().takeLast(64).toSet()
            config.turns.forEach { (message, turn) ->
                if (turn !in allowedTurns) return@forEach
                val note = data.optJSONObject(turn) ?: return@forEach
                val text = singleLine((note.opt("text") as? String).orEmpty(), 16)
                if (text.isNotBlank()) messages.getOrPut(message) { mutableListOf() }.add(DshNativeNote(target(pair.first, pair.second), pair.first.title, text, note.optString("tone")))
            }
        }
        return DshNativeSnapshot(actions, rows, messages, if (connected) setOf(config.host.id) else emptySet(),
            catalog.plugins.any { plugin -> plugin.state == "ok" && plugin.contributions.any { it.ready && it.surface in DshProtocol.supportedSurfaces } })
    }
    companion object {
        private fun signature(catalog: DshCatalog) = catalog.revision + catalog.plugins.joinToString { plugin ->
            listOf(plugin.pkg, plugin.title, plugin.version, plugin.state, plugin.capabilities.sorted(), plugin.wants.sorted(), plugin.contributions.map { it.raw.toString() }).toString()
        }
        internal fun singleLine(text: String, maximum: Int): String {
            val clean = text.map { if (it.isISOControl() || it == '\u2028' || it == '\u2029') ' ' else it }.joinToString("").replace(Regex("\\s+"), " ").trim()
            return clean.substring(0, clean.offsetByCodePoints(0, clean.codePointCount(0, clean.length).coerceAtMost(maximum)))
        }
    }
}
