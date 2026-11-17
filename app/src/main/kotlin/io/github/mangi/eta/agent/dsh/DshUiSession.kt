package io.github.mangi.eta.agent.dsh

import io.github.mangi.eta.agent.connector.SavedConnectorConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

/** Keeps the real user-tap deadline separate from streaming data and deferred receipts. */
internal class DshUiSession(private val repository: DshHostRepository, val host: SavedConnectorConfig,
    private val streamFactory: (DshHostClient) -> DshStreamClient = { DshStreamClient(it) }) : AutoCloseable {
    val client = repository.client(host)
    private val closed = AtomicBoolean(false)
    private val invoking = AtomicBoolean(false)
    @Volatile private var invocation: Job? = null
    private val mutableStream = MutableStateFlow<DshStreamClient?>(null)
    private val mutableNotices = MutableSharedFlow<DshStreamNotice>(extraBufferCapacity = 16)
    private val mutableFileHold = MutableStateFlow(false)
    private val mutableFileProgress = MutableStateFlow<String?>(null)
    private val usedFileRequests = linkedSetOf<String>()
    private val connectionLock = Mutex()
    private val catalogLock = Mutex()
    private var lastCatalogAt = 0L
    private val mutableCatalog = MutableStateFlow(runCatching { repository.cached(host) }.getOrNull())
    val stream = mutableStream.asStateFlow()
    val notices = mutableNotices.asSharedFlow()
    val fileHold = mutableFileHold.asStateFlow()
    val fileProgress = mutableFileProgress.asStateFlow()
    val catalog = mutableCatalog.asStateFlow()

    suspend fun refreshCatalog(force: Boolean = false): DshCatalog = catalogLock.withLock {
        check(!closed.get() && repository.isCurrent(host))
        if (!force && System.nanoTime() - lastCatalogAt < TimeUnit.SECONDS.toNanos(1)) mutableCatalog.value?.let { return@withLock it }
        repository.refresh(host).also { mutableCatalog.value = it; lastCatalogAt = System.nanoTime() }
    }

    suspend fun maintainConnection() = connectionLock.withLock {
        var failures = 0
        var authenticationFailures = 0
        while (currentCoroutineContext().isActive && !closed.get()) {
            if (!repository.isCurrent(host)) {
                val revision = io.github.mangi.eta.agent.connector.ConnectorConfigStore.changes.value
                if (repository.isCurrent(host)) continue
                io.github.mangi.eta.agent.connector.ConnectorConfigStore.changes.first { it != revision }
                continue
            }
            val active = streamFactory(client)
            try {
                active.connect(); mutableStream.value = active; failures = 0; authenticationFailures = 0
                coroutineScope {
                    val forwarding = launch(start = CoroutineStart.UNDISPATCHED) { active.notices.collect {
                        mutableNotices.emit(it)
                        if (it.kind == "changed") {
                            runCatching { refreshCatalog(force = true) }
                            mutableNotices.emit(DshStreamNotice("catalog-updated"))
                        }
                    } }
                    try {
                        runCatching { refreshCatalog() }
                        mutableNotices.tryEmit(DshStreamNotice("reconnected"))
                        active.connected.first { !it }
                    } finally { forwarding.cancelAndJoin() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val code = (failure as? DshException)?.code
                if (code == "DSH_AUTH_REQUIRED") authenticationFailures++
                if (code == "DSH_AUTH_FORBIDDEN") authenticationFailures = 2
                mutableNotices.tryEmit(DshStreamNotice(when {
                    code == "DSH_AUTH_FORBIDDEN" -> "auth-forbidden"
                    authenticationFailures >= 2 -> "auth-required"
                    else -> "disconnected"
                }))
                failures++
            }
            finally { if (mutableStream.value === active) mutableStream.value = null; active.close() }
            // One renewed-cookie attempt is enough. A new visible owner or edited host may retry;
            // an invalid launch token or Origin/Host rejection must not generate an endless login loop.
            if (authenticationFailures >= 2) awaitCancellation()
            delay((1000L shl failures.coerceIn(0, 5)).coerceAtMost(30000))
        }
    }

    suspend fun invokeFromTap(plugin: DshPlugin, contribution: DshContribution, scope: JSONObject = JSONObject(),
        sharedContext: JSONObject = JSONObject(), action: String? = null, value: JSONObject? = null,
        tappedAtNanos: Long = System.nanoTime(), userGesture: Boolean = true): DshEffects {
        check(!closed.get() && repository.isCurrent(host)) { "DSH 主机已停用或移除" }
        check(invoking.compareAndSet(false, true)) { "请等待当前插件操作完成" }
        invocation = currentCoroutineContext().job
        val deadline = tappedAtNanos + TimeUnit.SECONDS.toNanos(3)
        try {
            require(plugin.pkg == contribution.pkg && contribution in plugin.contributions && contribution.ready && contribution.surface in DshProtocol.supportedSurfaces)
            val remaining = if (userGesture) TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()) else 3000
            if (remaining <= 0) throw DshException("DSH_TAP_TIMEOUT", "插件点按已超时，本次未执行效果")
            val active = mutableStream.value
            check(active?.features.orEmpty().containsAll(DshProtocol.requiredFeatures(contribution))) { "插件所需能力尚未连接" }
            if (contribution.effects != null) { require(userGesture); return DshProtocol.effects(contribution.effects, static = true).copy(gestureDeadlineNanos = deadline) }
            val context = JSONObject()
            val allowed = when (contribution.surface) { "composer.actions", "composer.tools" -> setOf("draft", "clipboard"); "message.actions" -> setOf("message"); else -> emptySet() }
            (plugin.wants intersect allowed).forEach { key ->
                val text = sharedContext.optString(key).takeIf { sharedContext.opt(key) is String }
                if (text != null) { require(text.toByteArray().size <= 16 * 1024); context.put(key, text) }
            }
            val response = withTimeoutOrNull(remaining) { client.invoke(contribution, scope, context, active?.connectionId, action, value,
                features = active?.features.orEmpty() intersect plugin.capabilities, userGesture = userGesture) }
                ?: throw DshException("DSH_TAP_TIMEOUT", "插件点按已超时，本次未执行效果")
            check(!closed.get() && repository.isCurrent(host)) { "DSH 连接已停用或移除" }
            check(active == null || active === mutableStream.value && active.connected.value) { "实时连接已变化，本次效果已作废" }
            require(!userGesture || System.nanoTime() <= deadline) { "点按结果已过期，本次未执行效果" }
            if (response.pendingId == null) return response.effects.copy(gestureDeadlineNanos = deadline.takeIf { userGesture }, streamId = active?.connectionId)
            require(userGesture) { "输入变化不能请求延后操作" }
            require(response.effects.values.isEmpty()) { "延后操作不能混合即时效果" }
            check(active != null && active === mutableStream.value && active.connected.value) { "实时连接中断，延后操作未完成" }
            return active.awaitPending(response.pendingId, response.pendingTimeoutMs).also { check(!closed.get() && repository.isCurrent(host)) }
        } finally { invocation = null; invoking.set(false) }
    }

    suspend fun selectFiles(plugin: DshPlugin, effect: JSONObject, result: DshEffects,
        select: suspend (Boolean, Int) -> List<android.net.Uri>,
        read: suspend (android.net.Uri, Long) -> DshPickedFile, stillCurrent: () -> Boolean): DshEffects {
        val active = requireNotNull(mutableStream.value) { "文件选择需要实时连接" }
        val connectionId = requireNotNull(active.connectionId)
        val requestId = effect.getString("request")
        require("files" in active.features && "files" in plugin.capabilities && result.streamId == connectionId
            && !result.deferred && result.gestureDeadlineNanos != null && System.nanoTime() <= result.gestureDeadlineNanos)
        require(DshProtocol.requestToken.matches(requestId) && effect.optString("type") in setOf("pickFile", "pickImage"))
        require(effect.getInt("maxFiles") in 1..4 && effect.getLong("maxBytes") in 1..DshProtocol.FILE_BYTES
            && effect.getLong("maxTotalBytes") in 1..DshProtocol.FILE_TOTAL_BYTES)
        check(invoking.compareAndSet(false, true)) { "请等待当前插件操作完成" }
        var completionSent = false
        var admitted = false
        fun checkCurrent() {
            check(!closed.get() && repository.isCurrent(host) && active === mutableStream.value && active.connected.value && stillCurrent()) {
                "文件操作所属页面或连接已变化，本次结果已作废"
            }
        }
        invocation = currentCoroutineContext().job
        try {
            synchronized(usedFileRequests) {
                check(usedFileRequests.add(requestId)) { "文件请求已处理，不能重复执行" }
                admitted = true
                while (usedFileRequests.size > 64) usedFileRequests.remove(usedFileRequests.first())
            }
            checkCurrent()
            mutableFileHold.value = true
            mutableFileProgress.value = "选择文件"
            return withTimeout(5 * 60_000L) {
                coroutineScope {
                    val requestJob = currentCoroutineContext().job
                    val disconnected = launch { active.connected.first { !it }; requestJob.cancel(CancellationException("DSH 文件连接已断开")) }
                    try {
                        val uris = select(effect.getString("type") == "pickImage", effect.getInt("maxFiles"))
                        checkCurrent()
                        require(uris.size <= effect.getInt("maxFiles") && uris.distinct().size == uris.size && uris.all { it.scheme == "content" })
                        var bytes = 0L
                        for ((index, uri) in uris.withIndex()) {
                            checkCurrent()
                            mutableFileProgress.value = "读取文件 ${index + 1}/${uris.size}"
                            read(uri, minOf(effect.getLong("maxBytes"), effect.getLong("maxTotalBytes") - bytes)).use { file ->
                                checkCurrent(); require(file.bytes >= 0 && file.bytes <= effect.getLong("maxBytes") && bytes + file.bytes <= effect.getLong("maxTotalBytes"))
                                mutableFileProgress.value = "上传文件 ${index + 1}/${uris.size}"
                                client.uploadPickedFile(requestId, connectionId, file)
                                bytes += file.bytes
                            }
                        }
                        checkCurrent()
                        completionSent = true
                        client.completePickedFiles(requestId, connectionId, if (uris.isEmpty()) "cancelled" else "selected").also { checkCurrent() }
                    } finally { disconnected.cancelAndJoin() }
                }
            }
        } catch (failure: Exception) {
            if (admitted && !completionSent && !closed.get() && repository.isCurrent(host) && active === mutableStream.value && active.connected.value) {
                completionSent = true
                withContext(NonCancellable) { withTimeoutOrNull(1500) {
                    runCatching { client.completePickedFiles(requestId, connectionId, if (failure is CancellationException) "cancelled" else "error") }
                } }
            }
            throw failure
        } finally { mutableFileProgress.value = null; mutableFileHold.value = false; invocation = null; invoking.set(false) }
    }
    fun cancelCurrent() { invocation?.cancel() }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancelCurrent()
        mutableStream.value?.close(); mutableStream.value = null; client.close()
    }
}
