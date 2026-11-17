package io.github.mangi.eta.agent.dsh

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.github.mangi.eta.ui.screens.capability.DshAndroidFilePicker
import io.github.mangi.eta.ui.screens.capability.DshPickRequest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class DshAndroidFilePickerTest {
    @Test fun cancelledCallerKeepsTheGlobalChooserSlotUntilItsLateSystemResultArrives() = runBlocking {
        val owner = Owner()
        val registry = Registry()
        val picker = DshAndroidFilePicker(registry, owner.lifecycle)
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) { picker.pick(DshPickRequest(false, 2)) }
            val code = registry.lastCode
            first.cancelAndJoin()
            val blocked = runCatching { picker.pick(DshPickRequest(false, 1)) }
            assertEquals("DSH_FILE_PICKER_BUSY", (blocked.exceptionOrNull() as DshException).code)
            registry.dispatchResult(code, Activity.RESULT_OK, Intent().setData(Uri.parse("content://picked/late")))
            val second = async(start = CoroutineStart.UNDISPATCHED) { picker.pick(DshPickRequest(false, 1)) }
            registry.dispatchResult(registry.lastCode, Activity.RESULT_OK, Intent().setData(Uri.parse("content://picked/current")))
            assertEquals(listOf(Uri.parse("content://picked/current")), second.await())
        } finally { owner.lifecycle.currentState = Lifecycle.State.DESTROYED }
    }

    @Test fun contractNeverSilentlyTruncatesAnExcessiveSelection() {
        val clip = ClipData.newRawUri("first", Uri.parse("content://picked/1"))
        for (index in 2..6) clip.addItem(ClipData.Item(Uri.parse("content://picked/$index")))
        val result = DshAndroidFilePicker.Contract().parseResult(Activity.RESULT_OK, Intent().apply { clipData = clip })
        assertTrue(result.size > 4)
    }

    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
    }
    private class Registry : ActivityResultRegistry() {
        var lastCode = 0
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) { lastCode = requestCode }
    }
}
