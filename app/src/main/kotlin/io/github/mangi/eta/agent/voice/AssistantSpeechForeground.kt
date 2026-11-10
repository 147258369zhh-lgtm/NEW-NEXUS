package io.github.mangi.eta.agent.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.R

/** Overlay 不等同于前台 Activity；只在可见浮窗采集或播放音频期间提升服务类型。 */
internal class AssistantSpeechForeground(private val service: Service) {
    private val handler = Handler(Looper.getMainLooper())
    private var recordingActive = false
    private var monitorActive = false
    private var playbackActive = false

    @Synchronized
    fun recording() {
        recordingActive = true
        promote()
    }

    /** 朗读期间为打断而开麦：与识别共用麦克风前台类型。 */
    @Synchronized
    fun monitoring() {
        monitorActive = true
        promote()
    }

    @Synchronized
    fun playback() {
        playbackActive = true
        promote()
    }

    @Synchronized
    fun recordingStopped() {
        recordingActive = false
        releaseIfUnused()
    }

    @Synchronized
    fun monitoringStopped() {
        monitorActive = false
        releaseIfUnused()
    }

    @Synchronized
    fun playbackStopped() {
        playbackActive = false
        releaseIfUnused()
    }

    @Synchronized
    fun stop() {
        handler.removeCallbacks(demote)
        recordingActive = false
        monitorActive = false
        playbackActive = false
        runCatching { service.stopForeground(Service.STOP_FOREGROUND_REMOVE) }
    }

    private fun promote() {
        val capturing = recordingActive || monitorActive
        val type = (if (capturing) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) or
            (if (playbackActive) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        val title = if (capturing && playbackActive) "Nexus 正在语音对话"
        else if (capturing) "Nexus 正在聆听" else "Nexus 正在朗读"
        start(type, title)
    }

    private fun releaseIfUnused() {
        handler.removeCallbacks(demote)
        if (!recordingActive && !monitorActive && !playbackActive) {
            // 打断的换手是连续的：监测麦克风先关、识别紧接着开，延迟一步撤销前台避免通知闪烁。
            handler.postDelayed(demote, DEMOTE_DELAY_MS)
        } else {
            promote()
        }
    }

    private val demote = Runnable {
        if (!recordingActive && !monitorActive && !playbackActive) {
            runCatching { service.stopForeground(Service.STOP_FOREGROUND_REMOVE) }
        }
    }

    private fun start(type: Int, title: String) {
        service.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "助手语音", NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(service, 0,
            Intent(service, EtaAssistantOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(service, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("仅在助手浮窗可见时运行")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .build()
        try {
            service.startForeground(NOTIFICATION_ID, notification, type)
        } catch (_: RuntimeException) {
            throw SpeechFailure(SpeechErrorCode.PERMISSION, "系统不允许当前浮窗使用音频，请检查麦克风权限和默认数字助理设置")
        }
    }

    companion object {
        const val ACTION_STOP = "io.github.mangi.eta.STOP_ASSISTANT_SPEECH"
        private const val CHANNEL = "eta_assistant_speech"
        private const val NOTIFICATION_ID = 4203
        private const val DEMOTE_DELAY_MS = 400L
    }
}
