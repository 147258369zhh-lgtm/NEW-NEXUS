package io.github.mangi.eta.ui.components

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceSkin
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.theme.MiuixTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36, 37], application = Application::class, qualifiers = "w411dp-h896dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationMessagePresentationTest {
    private val summary = "# Heading\n\n**Unique body**\n\nFinal line"
    private val identity = ConversationMessageIdentity("Meeting Lead", ConversationAuthorRole.Host, 1, isSummary = true, previewLines = 0)

    @Test fun titleOnlySummaryExpandsIntoRealMarkdownAndCopiesTheFullOriginal() = withContent({
        ChatMessageItem(
            message = AgentMessageUi("summary", summary), identity = identity, allowMessageChanges = false,
            onSuggestionClick = {}, onRunTraceClick = {}, onOpenBrowser = {}, showBrowserShortcut = false,
        )
    }) { panel ->
        assertTrue(panel.texts().contains("Meeting Lead"))
        assertFalse(panel.texts().any { it.contains("Unique body") })
        panel.clickText(panel.activity.getString(R.string.conversation_message_expand))
        panel.await { panel.texts().any { it.contains("Unique body") && !it.contains("**") } }
        panel.capture("classic-expanded-summary")
        panel.clickDescription(panel.activity.getString(R.string.copy_answer))
        val clipboard = panel.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(summary, clipboard.primaryClip!!.getItemAt(0).text.toString())
        panel.clickText(panel.activity.getString(R.string.conversation_message_collapse))
        assertFalse(panel.texts().any { it.contains("Unique body") })
    }

    @Test fun attributedMeetingReplySuppressesRoleplayAndAllUnsupportedMutationButtons() = withContent({
        CompositionLocalProvider(LocalRoleplayChat provides RoleplayChatUi("Unrelated Character", null, "User")) {
            ChatMessageItem(
                message = AgentMessageUi("reply", "Reply", characterEditable = true, candidateCount = 2),
                identity = identity.copy(isSummary = false, previewLines = null),
                allowMessageChanges = false, showMessageActions = true, messageActionsEnabled = true,
                onSuggestionClick = {}, onRunTraceClick = {}, onOpenBrowser = {}, showBrowserShortcut = false,
                onForkMessage = {},
            )
        }
    }) { panel ->
        assertTrue(panel.texts().contains("Meeting Lead"))
        assertFalse(panel.texts().contains("Unrelated Character"))
        assertTrue(panel.descriptions().contains(panel.activity.getString(R.string.copy_answer)))
        panel.assertNoMutationButtons()
    }

    @Test fun ordinaryRoleplayReplyRetainsItsExistingIdentityAndActions() = withContent({
        CompositionLocalProvider(LocalRoleplayChat provides RoleplayChatUi("Existing Character", null, "User")) {
            ChatMessageItem(
                message = AgentMessageUi("reply", "Reply"), showMessageActions = true, messageActionsEnabled = true,
                onSuggestionClick = {}, onRunTraceClick = {}, onOpenBrowser = {}, showBrowserShortcut = false,
            )
        }
    }) { panel ->
        assertTrue(panel.texts().contains("Existing Character"))
        assertTrue(panel.descriptions().contains(panel.activity.getString(R.string.ui_regenerate_reply_84a7d9)))
        assertTrue(panel.descriptions().contains(panel.activity.getString(R.string.ui_delete_this_conversation_3f351b)))
    }

    @Test fun sharedRunningTimelineKeepsBothCompletedMembersCopyable() = withContent({
        val messages = listOf(AgentMessageUi("a", "Member A result"), AgentMessageUi("b", "Member B result"))
        AgentConversationMessages(
            visibleMessages = messages, scrollState = rememberLazyListState(), isStreaming = true,
            bottomInset = 0.dp, keepBottomAnchored = false, onBottomAnchorChanged = {},
            followIncomingMessages = false, allowMessageChanges = false, completedReplyMessageIds = setOf("a", "b"),
            messageIdentities = mapOf(
                "a" to ConversationMessageIdentity("Member A", ConversationAuthorRole.Agent, 1),
                "b" to ConversationMessageIdentity("Member B", ConversationAuthorRole.Agent, 2),
            ),
            modifier = Modifier.fillMaxSize(),
        )
    }) { panel ->
        assertEquals(2, panel.descriptions().count { it == panel.activity.getString(R.string.copy_answer) })
        assertTrue(panel.texts().containsAll(listOf("Member A", "Member B")))
        panel.assertNoMutationButtons()
    }

    @Test fun openingWithAutoFollowDisabledPositionsAtTheEndAndIncomingMessagesKeepTheReadingPosition() {
        val messages = mutableStateOf((0 until 40).map { UserMessageUi("user-$it", "Message $it") })
        lateinit var scrollState: androidx.compose.foundation.lazy.LazyListState
        withContent({
            scrollState = rememberLazyListState()
            AgentConversationMessages(
                visibleMessages = messages.value, scrollState = scrollState, isStreaming = true,
                bottomInset = 0.dp, keepBottomAnchored = true, onBottomAnchorChanged = {},
                followIncomingMessages = false, initiallyScrollToBottom = true, allowMessageChanges = false,
                modifier = Modifier.fillMaxSize(),
            )
        }) { panel ->
            assertFalse("Opening should reveal the latest messages even if auto-follow is off", scrollState.canScrollForward)
            scrollState.requestScrollToItem(2, 10)
            panel.advance()
            val before = scrollState.firstVisibleItemIndex to scrollState.firstVisibleItemScrollOffset
            messages.value = messages.value + UserMessageUi("user-40", "Incoming message")
            panel.advance()
            assertEquals(before, scrollState.firstVisibleItemIndex to scrollState.firstVisibleItemScrollOffset)
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h896dp-xhdpi")
    fun narrowOmniLargeTextWrapsAttachmentsAndKeepsActionsInsideTheViewport() {
        val image = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).also { it.eraseColor(android.graphics.Color.rgb(70, 110, 190)) }
        val bytes = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        image.recycle()
        val dataUrl = "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        withContent(content = {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ChatMessageItem(
                    message = UserMessageUi("photos", "Four pictures", images = List(4) { dataUrl }),
                    identity = ConversationMessageIdentity("User", ConversationAuthorRole.User, 1),
                    allowMessageChanges = false,
                    onSuggestionClick = {}, onRunTraceClick = {}, onOpenBrowser = {}, showBrowserShortcut = false,
                )
                ChatMessageItem(
                    message = AgentMessageUi("summary", "Readable summary preview"),
                    identity = identity.copy(name = "An exceptionally long participant display name", previewLines = 1),
                    allowMessageChanges = false,
                    onSuggestionClick = {}, onRunTraceClick = {}, onOpenBrowser = {}, showBrowserShortcut = false,
                )
            }
        }, appearance = AppearanceSettings(skin = AppearanceSkin.OMNI, themeMode = AppearanceThemeMode.DARK)) { panel ->
            val photos = panel.nodesWithDescription(panel.activity.getString(R.string.chat_image_attachment))
            assertEquals(4, photos.size)
            assertTrue("Attachments should wrap to multiple rows", photos.map { it.boundsInRoot.top }.distinct().size > 1)
            val width = panel.rootWidth()
            photos.forEach { assertTrue(it.boundsInRoot.left >= 0 && it.boundsInRoot.right <= width) }
            val expand = panel.nodesWithText(panel.activity.getString(R.string.conversation_message_expand)).single()
            assertTrue(expand.boundsInRoot.left >= 0 && expand.boundsInRoot.right <= width)
            assertTrue(expand.boundsInRoot.width > 0 && expand.boundsInRoot.height > 0)
            panel.capture("omni-dark-narrow-large-text")
        }
    }

    private fun withContent(content: @Composable () -> Unit, appearance: AppearanceSettings = AppearanceSettings(), block: (Panel) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val panel = Panel(controller.get(), appearance, content)
        try { panel.advance(); block(panel) } finally { panel.close(); controller.pause().stop().destroy() }
    }

    private class Panel(val activity: ComponentActivity, appearance: AppearanceSettings, content: @Composable () -> Unit) {
        private val frameClock = BroadcastFrameClock()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        private val recomposer = Recomposer(scope.coroutineContext)
        init {
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            val view = ComposeView(activity)
            view.setParentCompositionContext(recomposer)
            view.setContent {
                AgentAppTheme(appearance, applyInterfaceScale = false) {
                    CompositionLocalProvider(LocalSquircleEnabled provides false) {
                        Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) { content() }
                    }
                }
            }
            activity.setContentView(view)
        }
        fun close() { recomposer.cancel(); scope.cancel() }
        fun advance(frames: Int = 40) {
            repeat(frames) {
                Snapshot.sendApplyNotifications()
                shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                frameClock.sendFrame(SystemClock.uptimeMillis() * 1_000_000L)
                root().measureAndLayoutForTest()
            }
        }
        fun await(condition: () -> Boolean) {
            repeat(180) {
                if (condition()) return
                advance(1)
                Thread.sleep(1) // Let the real background Markdown parser complete its first cold load.
            }
            assertTrue("Expected rendered content; texts=${texts()}", condition())
        }
        fun capture(name: String) {
            if (android.os.Build.VERSION.SDK_INT != 36) return
            val view = root().view
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/reports/p02-shared-timeline/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        fun rootWidth(): Float = root().view.width.toFloat()
        private fun root(): ViewRootForTest = findRoot(activity.window.decorView) ?: error("Missing Compose root")
        private fun findRoot(view: View): ViewRootForTest? {
            if (view is ViewRootForTest) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) findRoot(view.getChildAt(index))?.let { return it }
            return null
        }
        private fun nodes(node: SemanticsNode = root().semanticsOwner.unmergedRootSemanticsNode): List<SemanticsNode> =
            listOf(node) + node.children.flatMap { nodes(it) }
        fun texts(): List<String> = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text } }
        fun descriptions(): List<String> = nodes().flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        fun nodesWithDescription(description: String): List<SemanticsNode> = nodes().filter { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true }
        fun nodesWithText(text: String): List<SemanticsNode> = nodes().filter { it.config.getOrNull(SemanticsProperties.Text)?.any { item -> item.text == text } == true }
        fun clickText(text: String) = click(nodes().first { it.config.getOrNull(SemanticsProperties.Text)?.any { item -> item.text == text } == true })
        fun clickDescription(description: String) = click(nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true })
        private fun click(node: SemanticsNode) {
            var actionNode: SemanticsNode? = node
            while (actionNode != null && actionNode.config.getOrNull(SemanticsActions.OnClick) == null) actionNode = actionNode.parent
            assertTrue(actionNode?.config?.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
            advance()
        }
        fun assertNoMutationButtons() {
            val descriptions = descriptions()
            listOf(R.string.chat_edit_character_reply, R.string.ui_regenerate_reply_84a7d9,
                R.string.ui_delete_this_conversation_3f351b, R.string.branch_create).forEach {
                assertFalse("Unsupported action should not be shown", descriptions.contains(activity.getString(it)))
            }
        }
    }
}
