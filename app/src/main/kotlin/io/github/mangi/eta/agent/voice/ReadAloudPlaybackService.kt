package io.github.mangi.eta.agent.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.core.NexusUiContract
import java.io.Closeable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 聊天朗读会话的前台播放服务，让 App 退到后台或锁屏后能继续播放。
 * 播放本身仍由聊天页的控制器持有；通知上的“停止”按所属会话交回控制器。
 */
internal open class ReadAloudPlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            active?.takeIf { it.id == intent.getLongExtra(EXTRA_SESSION, -1) }?.let { request ->
                release(this, request)
                request.onStop()
            }
            if (active == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        try {
            // 已取消请求的 Intent 也可能迟到；仍先满足 startForegroundService 的系统时限。
            // 它不能确认或结束一个后来建立的新会话。
            promotePlayback()
        } catch (failure: RuntimeException) {
            AndroidAgentLogger.warn("Read-aloud foreground failed: type=${failure.safeLogType()}")
            active?.let { request ->
                active = null
                request.ready.completeExceptionally(foregroundFailure(this))
                if (request.service != null) request.onStop()
            }
            stopSelf()
            return START_NOT_STICKY
        }
        val request = active
        if (request == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        } else if (request.id == intent?.getLongExtra(EXTRA_SESSION, -1)) {
            request.service = this
            request.ready.complete(Unit)
        }
        return START_NOT_STICKY
    }

    internal open fun promotePlayback() {
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    override fun onDestroy() {
        active?.takeIf { it.service === this }?.let { request ->
            active = null
            request.ready.cancel()
            request.onStop()
        }
        super.onDestroy()
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.read_aloud_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0,
            NexusUiContract.launchIntent(this).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, active?.id?.toInt() ?: 0,
            Intent(this, ReadAloudPlaybackService::class.java).setAction(ACTION_STOP)
                .putExtra(EXTRA_SESSION, active?.id ?: -1L),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.read_aloud_notification_title))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(Notification.Action.Builder(null, getString(R.string.speech_read_stop), stop).build())
            .build()
    }

    companion object {
        private const val ACTION_STOP = "io.github.mangi.eta.action.STOP_READ_ALOUD"
        private const val CHANNEL = "eta_read_aloud"
        private const val NOTIFICATION_ID = 4204
        private const val EXTRA_SESSION = "read_aloud_session"
        private const val START_TIMEOUT_MS = 5_000L

        // 启动、服务回调、控制器和句柄关闭都在主线程；旧句柄不能关闭新会话。
        private class Request(val id: Long, val onStop: () -> Unit) {
            val ready = CompletableDeferred<Unit>()
            var service: ReadAloudPlaybackService? = null
        }
        private var nextSession = 0L
        private var active: Request? = null

        /** 只在服务确认 startForeground 成功后返回；调用者随后才能申请音频焦点。 */
        suspend fun start(context: Context, onStop: () -> Unit): Closeable = withContext(Dispatchers.Main.immediate) {
            active?.let { previous ->
                active = null
                previous.ready.cancel()
                previous.onStop()
            }
            val request = Request(++nextSession, onStop)
            active = request
            try {
                try {
                    context.startForegroundService(Intent(context, ReadAloudPlaybackService::class.java)
                        .putExtra(EXTRA_SESSION, request.id))
                } catch (failure: RuntimeException) {
                    AndroidAgentLogger.warn("Read-aloud service start rejected: type=${failure.safeLogType()}")
                    throw foregroundFailure(context)
                }
                withTimeoutOrNull(START_TIMEOUT_MS) { request.ready.await() }
                    ?: throw SpeechFailure(SpeechErrorCode.TIMEOUT, context.getString(R.string.read_aloud_start_timeout))
                Closeable { release(context, request) }
            } catch (failure: Throwable) {
                release(context, request)
                throw failure
            }
        }

        private fun release(context: Context, request: Request) {
            if (active !== request) return
            active = null
            request.ready.cancel()
            request.service?.stopForeground(STOP_FOREGROUND_REMOVE)
            context.stopService(Intent(context, ReadAloudPlaybackService::class.java))
        }

        private fun foregroundFailure(context: Context) = SpeechFailure(
            SpeechErrorCode.PERMISSION, context.getString(R.string.read_aloud_start_denied),
        )
    }
}
