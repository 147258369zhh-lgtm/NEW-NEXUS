package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.runtime.Immutable
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.ConsoleSessionController
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.TerminalKey
import io.github.mangi.eta.agent.terminal.TerminalKeyEncoder
import io.github.mangi.eta.agent.terminal.TerminalModifiers
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.agent.terminal.TerminalScreenBuffer
import io.github.mangi.eta.agent.terminal.isLinux
import io.github.mangi.eta.agent.terminal.ptySupported
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentSettingsRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 控制台一帧画面：行快照不可变，渲染层按 id 复用条目、按 version 判断是否重绘。 */
@Immutable
internal data class ConsoleFrame(
    val lines: List<TerminalScreenBuffer.RenderLine> = emptyList(),
    val screenRows: Int = 0,
    val cursorRow: Int = 0,
    val cursorCol: Int = 0,
    val cursorVisible: Boolean = true,
    val sessionId: String? = null,
)

@Immutable
internal data class ConsoleSessionUi(
    val id: String,
    val environment: TerminalEnvironment,
    val exited: Boolean = false,
)

@Immutable
internal data class ConsoleUiState(
    val sessions: List<ConsoleSessionUi> = emptyList(),
    val activeSessionId: String? = null,
    /** 环境 tab 的选择；与当前会话环境一致，无会话时表示新建会话的目标环境。 */
    val environment: TerminalEnvironment = TerminalEnvironment.DEBIAN,
    val linuxEnvironment: TerminalEnvironment = TerminalEnvironment.DEBIAN,
    val connected: Boolean = false,
    val exited: Boolean = false,
    /** null = 探测中；false 时入口回退到块式终端。 */
    val ptySupported: Boolean? = null,
    val failMessage: String? = null,
    /** 当前会话的画面；每个会话的屏幕缓冲区独立保存。 */
    val frame: ConsoleFrame = ConsoleFrame(),
)

/**
 * 控制台页面的 App 级状态所有者：持有多个 PTY 会话与各自独立的屏幕缓冲区，
 * 字节流在 IO 线程喂入 [TerminalScreenBuffer]，按节流节奏向 UI 发布当前会话的帧。
 * 离开页面会话仍存活；普通会话由前台执行服务持有，用户停止后回收。
 */
internal class ConsoleStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    private companion object {
        const val FLUSH_INTERVAL_MS = 50L
        /** 同步输出期间暂缓发布帧的上限；程序漏发结束标记时不至于让画面一直停住。 */
        const val SYNCHRONIZED_OUTPUT_TIMEOUT_MS = 200L
        const val SCROLLBACK_LINES = 1000
    }

    /** 按键与查询应答必须按产生顺序写入 stdin；单并发调度保证 FIFO，又不占用主线程。 */
    private val inputDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val appContext = context.applicationContext
    private val sessionLeases = ConcurrentHashMap<String, TerminalSessionLease>()
    private val controller = ConsoleSessionController(
        logger = AndroidAgentLogger,
        linuxRootfsPathProvider = { environment ->
            environment.linuxDistribution?.let { distribution ->
                LinuxEnvironmentPaths.rootfsDir(appContext, distribution).absolutePath
            }
        },
        linuxSharedMountsProvider = { SharedFolderMounts.current() },
    )
    private val bufferLock = Any()
    private val initialLinuxEnvironment =
        LinuxEnvironmentSettingsRepository.current(appContext).terminalEnvironment

    /** Each session's mutable buffer is accessed under this lock; frame projection runs off main. */
    private val buffers = mutableMapOf<String, TerminalScreenBuffer>()

    /** 界面当前的默认前景/背景色，供 TUI 查询深浅主题；随系统深浅色切换更新到所有会话。 */
    private var themeColors = 0xFF000000L to 0xFFFFFFFFL
    private val sessionSizes = ConcurrentHashMap<String, Pair<Int, Int>>()

    private val _uiState = MutableStateFlow(
        ConsoleUiState(
            environment = if (isReady(initialLinuxEnvironment)) {
                initialLinuxEnvironment
            } else {
                TerminalEnvironment.ANDROID
            },
            linuxEnvironment = initialLinuxEnvironment,
        )
    )
    val uiState: StateFlow<ConsoleUiState> = _uiState.asStateFlow()

    // PTY 读线程与主线程都会触发发布帧。
    /**
     * 会话启动期间（Root 授权与 chroot 准备可能耗时数秒）网格尺寸仍会随软键盘变化；
     * 这期间只记录最新尺寸，就绪后再同步，避免每次尺寸变化都另起一个会话。只在主线程读写。
     */
    private var creatingSession = false
    private var selectionVersion = 0L
    private var pendingEnvironment: TerminalEnvironment? = null

    @Volatile private var lastFlushMs = 0L
    @Volatile private var renderingEnabled = false
    private val frameGeneration = AtomicLong()
    private data class FrameRequest(val sessionId: String, val delayMs: Long, val generation: Long)
    private val frameRequests = Channel<Long>(Channel.CONFLATED)
    private var lastCols = 0
    private var lastRows = 0

    init {
        // One worker bounds allocations under high PTY output. Hidden terminals keep processing
        // bytes/protocol replies but never project 1,000 scrollback lines for another screen.
        scope.launch(Dispatchers.Default) {
            for (delayMs in frameRequests) {
                val remaining = (FLUSH_INTERVAL_MS - (android.os.SystemClock.elapsedRealtime() - lastFlushMs)).coerceAtLeast(0L)
                val waitMillis = maxOf(delayMs, remaining)
                if (waitMillis > 0L) delay(waitMillis)
                // The signal carries no session identity: a late old PTY callback must not
                // overwrite the only redraw request for a newly selected, quiet session.
                val sessionId = _uiState.value.activeSessionId ?: continue
                val request = FrameRequest(sessionId, 0L, frameGeneration.get())
                if (!canRender(request)) continue
                val frame = synchronized(bufferLock) {
                    buffers[request.sessionId]?.let { current ->
                        ConsoleFrame(current.renderLines(), current.rows, current.cursorRow,
                            current.cursorCol, current.cursorVisible, sessionId)
                    }
                } ?: continue
                lastFlushMs = android.os.SystemClock.elapsedRealtime()
                _uiState.update { state ->
                    if (canRender(request) && state.activeSessionId == request.sessionId) state.copy(frame = frame) else state
                }
            }
        }
    }

    fun setRenderingEnabled(enabled: Boolean) {
        if (renderingEnabled == enabled) return
        renderingEnabled = enabled
        frameGeneration.incrementAndGet()
        if (enabled) _uiState.value.activeSessionId?.let(::flushFrame)
    }

    private fun canRender(request: FrameRequest): Boolean =
        renderingEnabled && frameGeneration.get() == request.generation &&
            _uiState.value.activeSessionId == request.sessionId

    /** 探测 PTY 前提；结果缓存进状态，不支持时入口回退到块式终端。 */
    fun probePtySupport() {
        scope.launch {
            val supported = withContext(Dispatchers.IO) { ptySupported() }
            _uiState.update { it.copy(ptySupported = supported) }
        }
    }

    fun refreshLinuxEnvironment() {
        val selected = LinuxEnvironmentSettingsRepository.current(appContext).terminalEnvironment
        val changeSelection = _uiState.value.environment.isLinux && _uiState.value.environment != selected
        _uiState.update { it.copy(linuxEnvironment = selected) }
        if (changeSelection) switchEnvironment(selected)
    }

    /**
     * 确保当前有存活会话；网格尺寸变化重入时只同步尺寸，不重建会话。
     * [environment] 仅用于没有可复用会话时的新建。
     */
    fun open(environment: TerminalEnvironment, cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        lastCols = cols
        lastRows = rows
        if (creatingSession) return
        val state = _uiState.value
        val activeId = state.activeSessionId
        if (activeId != null && controller.sessionAlive(activeId)) {
            resizeSession(activeId, cols, rows)
            return
        }
        if (activeId == null) {
            val reusable = state.sessions.lastOrNull { !it.exited && it.environment == environment }
            if (reusable != null && controller.sessionAlive(reusable.id)) {
                switchSession(reusable.id)
                return
            }
        }
        createSession(environment, cols, rows)
    }

    /** 以当前环境与最近一次网格尺寸新建会话。 */
    fun newSession() {
        if (lastCols <= 0 || lastRows <= 0 || creatingSession) return
        selectionVersion++
        pendingEnvironment = null
        _uiState.update { it.copy(activeSessionId = null, connected = false, exited = false,
            failMessage = null, frame = ConsoleFrame()) }
        createSession(_uiState.value.environment, lastCols, lastRows)
    }

    fun switchSession(sessionId: String) {
        if (_uiState.value.sessions.none { it.id == sessionId }) return
        frameGeneration.incrementAndGet()
        selectionVersion++
        pendingEnvironment = null
        _uiState.update { state ->
            val session = state.sessions.find { it.id == sessionId }
            if (session == null) state else state.copy(
                activeSessionId = sessionId,
                environment = session.environment,
                connected = !session.exited,
                exited = session.exited,
                failMessage = null,
            )
        }
        // 会话在后台期间网格可能已变化（横竖屏、软键盘），切回时按当前网格同步。
        if (lastCols > 0 && lastRows > 0) resizeSession(sessionId, lastCols, lastRows)
        flushFrame(sessionId)
    }

    /** 关闭指定会话；关闭当前会话时切换到剩余最近的会话，没有则回到空态。 */
    fun closeSession(sessionId: String) {
        if (_uiState.value.activeSessionId == sessionId) pendingEnvironment = null
        if (_uiState.value.activeSessionId == sessionId) selectionVersion++
        scope.launch(Dispatchers.IO) {
            controller.closeSession(sessionId)
            sessionLeases.remove(sessionId)?.release()
            synchronized(bufferLock) { buffers.remove(sessionId) }
        }
        sessionSizes.remove(sessionId)
        _uiState.update { state ->
            val sessions = state.sessions.filterNot { it.id == sessionId }
            if (state.activeSessionId != sessionId) {
                state.copy(sessions = sessions)
            } else {
                val next = sessions.lastOrNull()
                state.copy(
                    sessions = sessions,
                    activeSessionId = next?.id,
                    environment = next?.environment ?: state.environment,
                    connected = next != null && !next.exited,
                    exited = next?.exited == true,
                    failMessage = null,
                    frame = ConsoleFrame(),
                )
            }
        }
        _uiState.value.activeSessionId?.let { flushFrame(it) }
    }

    /** 重启指定会话：终止后按原环境与原网格尺寸重开。 */
    fun restartSession(sessionId: String) {
        if (creatingSession) return
        val session = _uiState.value.sessions.find { it.id == sessionId } ?: return
        val size = sessionSizes[sessionId] ?: (lastCols to lastRows)
        if (size.first <= 0 || size.second <= 0) return
        selectionVersion++
        pendingEnvironment = null
        scope.launch(Dispatchers.IO) {
            controller.closeSession(sessionId)
            sessionLeases.remove(sessionId)?.release()
            synchronized(bufferLock) { buffers.remove(sessionId) }
        }
        sessionSizes.remove(sessionId)
        _uiState.update { state ->
            state.copy(sessions = state.sessions.filterNot { it.id == sessionId },
                activeSessionId = null, environment = session.environment, connected = false,
                exited = false, failMessage = null, frame = ConsoleFrame())
        }
        createSession(session.environment, size.first, size.second)
    }

    /** 切换环境 tab：复用存活会话，或按最近的网格尺寸新建。 */
    fun switchEnvironment(environment: TerminalEnvironment) {
        val state = _uiState.value
        if (state.environment == environment && state.activeSessionId != null) return
        val reusable = state.sessions.lastOrNull { !it.exited && it.environment == environment }
        if (reusable != null) {
            switchSession(reusable.id)
        } else {
            selectionVersion++
            pendingEnvironment = environment
            _uiState.update {
                it.copy(
                    environment = environment,
                    activeSessionId = null,
                    connected = false,
                    exited = false,
                    failMessage = null,
                    frame = ConsoleFrame(),
                )
            }
            open(environment, lastCols, lastRows)
        }
    }

    /** 当前会话断开后以同一环境与网格尺寸重连。 */
    fun reconnect() {
        val activeId = _uiState.value.activeSessionId
        if (activeId != null) restartSession(activeId) else newSession()
    }

    fun write(text: String) {
        if (text.isEmpty()) return
        val sessionId = _uiState.value.activeSessionId ?: return
        scope.launch(inputDispatcher) { controller.write(sessionId, text) }
    }

    /** 功能键按当前会话的光标键模式编码，程序切换 DECCKM 后无需界面感知。 */
    fun sendKey(key: TerminalKey, modifiers: TerminalModifiers = TerminalModifiers.NONE) {
        val sessionId = _uiState.value.activeSessionId ?: return
        scope.launch(inputDispatcher) {
            val applicationCursorKeys = synchronized(bufferLock) { buffers[sessionId]?.applicationCursorKeys } ?: return@launch
            controller.write(sessionId, TerminalKeyEncoder.key(key, modifiers, applicationCursorKeys))
        }
    }

    fun sendText(text: String, modifiers: TerminalModifiers = TerminalModifiers.NONE) {
        write(TerminalKeyEncoder.text(text, modifiers))
    }

    fun paste(text: String) {
        val sessionId = _uiState.value.activeSessionId ?: return
        scope.launch(inputDispatcher) {
            val bracketed = synchronized(bufferLock) { buffers[sessionId]?.bracketedPaste } ?: return@launch
            controller.write(sessionId, TerminalKeyEncoder.paste(text, bracketed))
        }
    }

    fun setThemeColors(foreground: Long, background: Long) {
        scope.launch(inputDispatcher) {
        synchronized(bufferLock) {
            themeColors = foreground to background
            buffers.values.forEach { it.applyTheme() }
        }
        _uiState.value.activeSessionId?.let(::flushFrame)
        }
    }

    private fun TerminalScreenBuffer.applyTheme() {
        defaultForeground = themeColors.first
        defaultBackground = themeColors.second
    }

    /** 屏幕缓冲区与 PTY 同步改尺寸；内核随后通知程序重绘，缓冲区先按新尺寸承接旧画面。 */
    private fun resizeSession(sessionId: String, cols: Int, rows: Int) {
        if (sessionSizes[sessionId] == (cols to rows)) return
        sessionSizes[sessionId] = cols to rows
        scope.launch(inputDispatcher) {
            synchronized(bufferLock) { buffers[sessionId]?.resize(cols, rows) }
            controller.resize(sessionId, cols, rows)
            flushFrame(sessionId)
        }
    }

    fun close() {
        setRenderingEnabled(false)
        frameRequests.close()
        controller.close()
        sessionLeases.values.forEach { it.release() }
        sessionLeases.clear()
    }

    private fun createSession(environment: TerminalEnvironment, cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0 || creatingSession) return
        lastCols = cols
        lastRows = rows
        creatingSession = true
        pendingEnvironment = null
        val requestVersion = selectionVersion
        _uiState.update { it.copy(connected = false, exited = false, failMessage = null) }
        scope.launch(Dispatchers.IO) {
            val result = try {
                startSession(environment, cols, rows)
            } catch (failure: Exception) {
                ConsoleStartResult.Failed(failure.message ?: "无法启动控制台会话")
            }
            withContext(NonCancellable + Dispatchers.Main) {
                creatingSession = false
                if (selectionVersion == requestVersion) {
                    when (result) {
                        is ConsoleStartResult.Ready -> {
                            _uiState.update { state ->
                                val session = state.sessions.find { it.id == result.sessionId }
                                if (session != null) {
                                    state.copy(activeSessionId = session.id, environment = session.environment,
                                        connected = !session.exited, exited = session.exited,
                                        failMessage = null, frame = ConsoleFrame())
                                } else state.copy(connected = false, exited = false,
                                    failMessage = appContext.getString(R.string.terminal_session_closed))
                            }
                            if (_uiState.value.activeSessionId == result.sessionId) {
                                resizeSession(result.sessionId, lastCols, lastRows)
                                flushFrame(result.sessionId)
                            }
                        }
                        is ConsoleStartResult.Failed -> _uiState.update {
                            it.copy(connected = false, failMessage = result.message)
                        }
                    }
                } else {
                    val activeId = _uiState.value.activeSessionId
                    val pending = pendingEnvironment
                    pendingEnvironment = null
                    if (activeId == null && pending != null && _uiState.value.environment == pending) {
                        open(pending, lastCols, lastRows)
                    }
                    else if (activeId != null) resizeSession(activeId, lastCols, lastRows)
                }
            }
        }
    }

    private sealed interface ConsoleStartResult {
        data class Ready(val sessionId: String) : ConsoleStartResult
        data class Failed(val message: String) : ConsoleStartResult
    }

    private fun startSession(environment: TerminalEnvironment, cols: Int, rows: Int): ConsoleStartResult {
        val rootfs = environment.linuxDistribution?.let { LinuxEnvironmentPaths.rootfsDir(appContext, it).absolutePath }
        val identity = TerminalRuntime.defaultIdentity(environment, rootfs)
        val lease = if (identity == "user") {
            TerminalSessionLease.acquire(appContext) { sessionId -> scope.launch { closeSession(sessionId) } } ?: run {
                return ConsoleStartResult.Failed(appContext.getString(R.string.capability_background_failed))
            }
        } else null
        val result = try {
            controller.open(
                environment = environment,
                identity = identity,
                cols = cols,
                rows = rows,
                onOutput = ::onOutput,
                onExit = { sessionId ->
                    lease?.release()
                    onExit(sessionId)
                },
                onSessionCreated = { sessionId ->
                    try {
                        synchronized(bufferLock) {
                            buffers[sessionId] = TerminalScreenBuffer(cols, rows, SCROLLBACK_LINES).apply { applyTheme() }
                        }
                        sessionSizes[sessionId] = cols to rows
                    } catch (failure: Throwable) {
                        synchronized(bufferLock) { buffers.remove(sessionId) }
                        sessionSizes.remove(sessionId)
                        throw failure
                    }
                },
            )
        } catch (failure: Throwable) {
            lease?.release()
            throw failure
        }
        when (result) {
            is ConsoleSessionController.OpenResult.Ready -> {
                if (lease != null) {
                    sessionLeases[result.sessionId] = lease
                    if (!lease.attach(result.sessionId) || !controller.sessionAlive(result.sessionId)) {
                        controller.closeSession(result.sessionId)
                        sessionLeases.remove(result.sessionId)?.release()
                        synchronized(bufferLock) { buffers.remove(result.sessionId) }
                        sessionSizes.remove(result.sessionId)
                        return ConsoleStartResult.Failed(appContext.getString(R.string.terminal_session_closed))
                    }
                }
                _uiState.update { state -> state.copy(sessions = state.sessions + ConsoleSessionUi(result.sessionId, environment)) }
                if (lease?.isStopped() == true || _uiState.value.sessions.none { it.id == result.sessionId }) {
                    controller.closeSession(result.sessionId)
                    sessionLeases.remove(result.sessionId)?.release()
                    synchronized(bufferLock) { buffers.remove(result.sessionId) }
                    sessionSizes.remove(result.sessionId)
                    _uiState.update { state ->
                        val sessions = state.sessions.filterNot { it.id == result.sessionId }
                        if (state.activeSessionId == result.sessionId) state.copy(sessions = sessions,
                            activeSessionId = null, connected = false, exited = false,
                            failMessage = appContext.getString(R.string.terminal_session_closed), frame = ConsoleFrame())
                        else state.copy(sessions = sessions)
                    }
                    return ConsoleStartResult.Failed(appContext.getString(R.string.terminal_session_closed))
                }
                if (!controller.sessionAlive(result.sessionId)) onExit(result.sessionId)
                return ConsoleStartResult.Ready(result.sessionId)
            }
            is ConsoleSessionController.OpenResult.Failed -> {
                lease?.release()
                return ConsoleStartResult.Failed(result.message)
            }
        }
    }

    private fun onOutput(sessionId: String, chunk: ByteArray) {
        var responses: String? = null
        var synchronizing = false
        synchronized(bufferLock) {
            val buffer = buffers[sessionId] ?: return
            buffer.feed(chunk)
            responses = buffer.drainResponses()
            synchronizing = buffer.synchronizedOutput
        }
        // 查询应答不经过界面，后台会话也要及时回复，否则程序会阻塞在等待上。
        responses?.let { reply -> scope.launch(inputDispatcher) { controller.write(sessionId, reply) } }
        if (!renderingEnabled || sessionId != _uiState.value.activeSessionId) return
        val now = android.os.SystemClock.elapsedRealtime()
        when {
            synchronizing -> scheduleFlush(sessionId, SYNCHRONIZED_OUTPUT_TIMEOUT_MS)
            now - lastFlushMs >= FLUSH_INTERVAL_MS -> flushFrame(sessionId)
            else -> scheduleFlush(sessionId, (FLUSH_INTERVAL_MS - (now - lastFlushMs)).coerceAtLeast(0L))
        }
    }

    private fun scheduleFlush(sessionId: String, delayMs: Long) {
        if (renderingEnabled && sessionId == _uiState.value.activeSessionId) {
            frameRequests.trySend(delayMs)
        }
    }

    private fun flushFrame(sessionId: String) {
        scheduleFlush(sessionId, 0L)
    }

    private fun onExit(sessionId: String) {
        sessionLeases.remove(sessionId)?.release()
        _uiState.update { state ->
            val sessions = state.sessions.map {
                if (it.id == sessionId) it.copy(exited = true) else it
            }
            if (state.activeSessionId == sessionId) {
                state.copy(sessions = sessions, connected = false, exited = true)
            } else {
                state.copy(sessions = sessions)
            }
        }
        flushFrame(sessionId)
    }

    private fun isReady(environment: TerminalEnvironment): Boolean =
        environment.linuxDistribution?.let { distribution ->
            LinuxEnvironmentPaths.rootfsReady(
                LinuxEnvironmentPaths.rootfsDir(appContext, distribution).absolutePath,
            )
        } ?: false
}
