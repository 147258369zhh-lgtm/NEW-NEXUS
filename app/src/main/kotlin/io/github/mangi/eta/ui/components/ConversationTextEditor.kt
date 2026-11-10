package io.github.mangi.eta.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.then
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.ConversationSubject
import io.github.mangi.eta.data.repository.ConversationDraft
import io.github.mangi.eta.data.repository.ConversationDraftRepository
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val LocalConversationSubject = staticCompositionLocalOf<ConversationSubject?> { null }
internal enum class ConversationDraftStatus { LOADING, READY, LOAD_FAILED, SAVE_FAILED }

/** Both navigation entries use the same identity boundary for local editor/timeline/audio state. */
@Composable
internal fun ConversationStage(subject: ConversationSubject, content: @Composable () -> Unit) {
    key(subject.key) { CompositionLocalProvider(LocalConversationSubject provides subject, content = content) }
}

/** One editor's restore fence. A late disk read must never replace text already changed locally. */
@Stable
internal class ConversationEditorState(
    private val repository: ConversationDraftRepository,
    subject: ConversationSubject,
    initialText: String = "",
) {
    val field = TextFieldState(initialText = initialText)
    val status = MutableStateFlow(ConversationDraftStatus.LOADING)
    private val lease = repository.open(subject)
    private val baseline = snapshot()
    private var lastObserved = baseline
    private var changed = initialText.isNotEmpty()
    private var lastEnqueued: ConversationDraft? = null
    private val saving = AtomicLong()

    fun snapshot() = ConversationDraft(field.text.toString(), field.selection.start, field.selection.end)

    fun registerActive() = repository.registerActive(lease, acceptHandoff = !changed && snapshot() == baseline) {
        if (status.value == ConversationDraftStatus.READY || status.value == ConversationDraftStatus.SAVE_FAILED) snapshot() else null
    }

    fun observe(draft: ConversationDraft) {
        if (draft != lastObserved) {
            lastObserved = draft
            changed = true
        }
    }

    // Input events can be conflated by snapshotFlow (type, then erase in the same frame).
    // Record intent at the actual IME edit boundary, even if the final text equals the baseline.
    fun onUserEdit() { changed = true }

    fun replace(text: String, selection: TextRange = TextRange(text.length)) {
        changed = true
        field.edit {
            replace(0, length, text)
            this.selection = selection
        }
        persistNow()
    }

    suspend fun restore() {
        status.value = ConversationDraftStatus.LOADING
        try {
            val stored = repository.load(lease)
            if (!changed && snapshot() == baseline) {
                if (stored != null) {
                    lastObserved = stored
                    field.edit {
                        replace(0, length, stored.text)
                        selection = TextRange(stored.selectionStart, stored.selectionEnd)
                    }
                }
                lastEnqueued = snapshot()
            }
            status.value = ConversationDraftStatus.READY
            if (!changed) repository.retryCached(lease)?.let(::trackSaving)
            persistNow()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            status.value = ConversationDraftStatus.LOAD_FAILED
            if (changed || snapshot() != baseline) persistNow(force = true)
        }
    }

    fun persistNow(force: Boolean = false) {
        val draft = snapshot()
        observe(draft)
        // An unmodified, unreadable/unloaded draft must not be replaced with an empty field.
        if ((!changed && !(force && lastEnqueued != null)) || (!force && draft == lastEnqueued)) return
        lastEnqueued = draft
        trackSaving(repository.save(lease, draft))
    }

    /** A receipt can only clear exactly the snapshot accepted by the execution/queue channel. */
    fun clearSubmitted(submitted: ConversationDraft) {
        if (snapshot() == submitted) replace("")
    }

    private fun trackSaving(result: kotlinx.coroutines.Deferred<Unit>) {
        val sequence = saving.incrementAndGet()
        result.invokeOnCompletion { failure ->
            if (saving.get() == sequence) status.value = if (failure == null) {
                ConversationDraftStatus.READY
            } else ConversationDraftStatus.SAVE_FAILED
        }
    }
}

@Composable
internal fun rememberConversationEditor(
    subject: ConversationSubject = LocalConversationSubject.current ?: ConversationSubject.chat(null),
    initialText: String = "",
): ConversationEditorState {
    val context = LocalContext.current
    val repository = remember(context.applicationContext) { ConversationDraftRepository.get(context) }
    val editor = remember(repository, subject) { ConversationEditorState(repository, subject, initialText) }
    LaunchedEffect(editor) { editor.restore() }
    LaunchedEffect(editor) {
        snapshotFlow { editor.snapshot() }.collectLatest { draft ->
            editor.observe(draft)
            delay(300)
            editor.persistNow()
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(editor, lifecycle) {
        val active = editor.registerActive()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) editor.persistNow()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); editor.persistNow(); active.close() }
    }
    return editor
}

/** Shared chat/meeting input typography, placeholder baseline, cursor, line limits and touch area. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationTextEditor(
    state: TextFieldState,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    maxLines: Int = 6,
    maxLength: Int? = null,
    onUserEdit: () -> Unit = {},
) {
    val style = MiuixTheme.textStyles.body1.copy(lineHeight = 22.sp)
    val trackInput = InputTransformation { if (changes.changeCount > 0) onUserEdit() }
    Box(
        modifier = modifier.defaultMinSize(minHeight = 48.dp).padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        if (state.text.isEmpty()) Text(
            text = placeholder, style = style, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.clearAndSetSemantics {},
        )
        BasicTextField(
            state = state,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 36.dp)
                .semantics { contentDescription = placeholder }
                .then(if (focusRequester == null) Modifier else Modifier.focusRequester(focusRequester)),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            textStyle = style.copy(color = MiuixTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = maxLines),
            inputTransformation = maxLength?.let { InputTransformation.maxLength(it).then(trackInput) } ?: trackInput,
        )
    }
}

@Composable
internal fun ConversationDraftFeedback(editor: ConversationEditorState, modifier: Modifier = Modifier) {
    val status by editor.status.collectAsState()
    if (status == ConversationDraftStatus.READY) return
    val scope = rememberCoroutineScope()
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(when (status) {
                ConversationDraftStatus.LOADING -> R.string.conversation_draft_loading
                ConversationDraftStatus.LOAD_FAILED -> R.string.conversation_draft_load_failed
                else -> R.string.conversation_draft_save_failed
            }),
            modifier = Modifier.weight(1f),
            style = MiuixTheme.textStyles.footnote2,
            color = if (status == ConversationDraftStatus.LOADING) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.error,
        )
        if (status != ConversationDraftStatus.LOADING) TextButton(
            text = stringResource(R.string.conversation_draft_retry),
            onClick = {
                if (status == ConversationDraftStatus.LOAD_FAILED) scope.launch { editor.restore() }
                else editor.persistNow(force = true)
            },
            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
        )
    }
}
