package io.github.mangi.eta.ui.screens.capability

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.mangi.eta.agent.dsh.DshException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal data class DshPickRequest(val images: Boolean, val maximum: Int)

/** Registration survives page disposal until the system chooser returns or its Activity is destroyed. */
internal class DshAndroidFilePicker(private val registry: ActivityResultRegistry, private val lifecycle: Lifecycle) {
    suspend fun pick(request: DshPickRequest): List<Uri> = suspendCancellableCoroutine { continuation ->
        check(Looper.myLooper() == Looper.getMainLooper())
        require(request.maximum in 1..4)
        val token = Any()
        if (!slot.compareAndSet(null, token)) {
            continuation.resumeWith(Result.failure(DshException("DSH_FILE_PICKER_BUSY", "另一个文件选择窗口尚未关闭")))
            return@suspendCancellableCoroutine
        }
        var launcher: ActivityResultLauncher<DshPickRequest>? = null
        lateinit var observer: LifecycleEventObserver
        fun release() {
            lifecycle.removeObserver(observer); launcher?.unregister(); slot.compareAndSet(token, null)
        }
        observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_DESTROY) {
            release()
            if (continuation.isActive) continuation.resumeWith(Result.failure(DshException("DSH_FILE_PICKER_CLOSED", "文件选择页面已关闭")))
        } }
        lifecycle.addObserver(observer)
        try {
            check(lifecycle.currentState != Lifecycle.State.DESTROYED)
            launcher = registry.register("dsh-picked-${UUID.randomUUID()}", Contract()) { uris ->
                release()
                if (continuation.isActive) continuation.resumeWith(runCatching {
                    require(uris.size <= request.maximum && uris.all { it.scheme == "content" }) { "文件选择结果超过限制" }
                    uris
                })
            }
            // Cancellation abandons this result, but cannot admit a second system chooser over it.
            continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post {
                if (lifecycle.currentState == Lifecycle.State.DESTROYED) release()
            } }
            launcher.launch(request)
        } catch (failure: Exception) { release(); if (continuation.isActive) continuation.resumeWith(Result.failure(failure)) }
    }

    internal class Contract : ActivityResultContract<DshPickRequest, List<Uri>>() {
        override fun createIntent(context: Context, input: DshPickRequest): Intent {
            if (input.images && ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context)) {
                val media = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                val intent = if (input.maximum == 1) ActivityResultContracts.PickVisualMedia().createIntent(context, media)
                    else ActivityResultContracts.PickMultipleVisualMedia(input.maximum).createIntent(context, media)
                if (intent.resolveActivity(context.packageManager) != null) return intent
            }
            return Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(if (input.images) "image/*" else "*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, input.maximum > 1).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
            if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
            if ((intent.clipData?.itemCount ?: 0) > 4) return List(5) { Uri.EMPTY }
            return buildList {
                intent.data?.let(::add)
                intent.clipData?.let { clip -> for (index in 0 until minOf(clip.itemCount, 5)) add(clip.getItemAt(index).uri) }
            }.distinct()
        }
    }

    private companion object { val slot = AtomicReference<Any?>() }
}
