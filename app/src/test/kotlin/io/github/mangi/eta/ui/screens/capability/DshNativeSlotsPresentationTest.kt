package io.github.mangi.eta.ui.screens.capability

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.dsh.*
import io.github.mangi.eta.data.model.ConversationSubject
import io.github.mangi.eta.ui.screens.memory.MemoryLearningPresentationTest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.Text

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w320dp-h720dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DshNativeSlotsPresentationTest {
    @Test fun actualNarrowChatSlotsDisplayRemoteStatusAndLinkedNotesAndDispatchTheirOwnTarget() {
        NativeSlotsFixture().use { fixture ->
            val subject = ConversationSubject.chat("local-conversation")
            fixture.repository.bindConversation(fixture.host, subject.key, "remote-session", "reply", "remote-turn-5")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val owner = DshConversationFeeds(fixture.context, subject.key, fixture.repository) { fixture.lease(it, scope) }
            val targets = mutableListOf<DshNativeTarget>()
            val task = scope.launch { owner.maintain() }
            try {
                MemoryLearningPresentationTest.Panel(dark = true, fontScale = 1.3f) {
                    CompositionLocalProvider(LocalDshFeeds provides owner,
                        LocalDshConversationActions provides DshConversationActions({}, { _, _ -> }, { targets += it })) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("当前回复")
                            DshReplyNotes("reply")
                            DshComposerPlugins(generating = false)
                        }
                    }
                }.use { panel ->
                    panel.await { panel.texts().containsAll(listOf("复制摘要", "状态 remote-session", "已确认")) }
                    panel.capture("dsh-native-slots-narrow-dark")
                    panel.click("复制摘要"); assertEquals("copy", targets.last().id)
                    panel.click("状态 remote-session"); assertEquals("status", targets.last().id)
                }
            } finally { task.cancel(); scope.cancel() }
        }
    }
}
