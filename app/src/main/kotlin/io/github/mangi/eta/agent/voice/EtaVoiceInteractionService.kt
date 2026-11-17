package io.github.mangi.eta.agent.voice

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import io.github.mangi.eta.core.AssistantContextFlags
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.ModuleConfig

class EtaVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        activeService = this
        if (Build.VERSION.SDK_INT >= 37) {
            try {
                setInvocationEffectEnabled(true)
            } catch (exception: RuntimeException) {
                AndroidAgentLogger.warnThrottled("assistant_invocation_effect_failed") {
                    "Assistant invocation effect unavailable: type=${exception.javaClass.simpleName}"
                }
            }
        }
    }

    override fun onShutdown() {
        if (activeService === this) activeService = null
        super.onShutdown()
    }

    override fun onDestroy() {
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        try {
            startActivity(
                Intent(this, EtaVoiceAssistActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (exception: RuntimeException) {
            AndroidAgentLogger.warnThrottled("assistant_keyguard_launch_failed") {
                "Assistant keyguard launch rejected: type=${exception.javaClass.simpleName}"
            }
        }
    }

    private fun showEtaSession() {
        showSession(Bundle(), AssistantContextFlags.forSdk(Build.VERSION.SDK_INT))
    }

    companion object {
        @Volatile
        private var activeService: EtaVoiceInteractionService? = null

        /** Secure 设置中的默认助手才是真实注册状态；仅开启本地唤醒词配置不代表系统会分发唤醒。 */
        internal fun isDefaultAssistant(context: Context): Boolean {
            val expectedService = ComponentName(context, EtaVoiceInteractionService::class.java)
            val expectedActivity = ComponentName(context, EtaVoiceAssistActivity::class.java)
            return listOf(ModuleConfig.SECURE_VOICE_INTERACTION_SERVICE, ModuleConfig.SECURE_ASSISTANT).any { key ->
                val configured = Settings.Secure.getString(context.contentResolver, key)
                    ?: return@any false
                val component = ComponentName.unflattenFromString(configured)
                component == expectedService || (key == ModuleConfig.SECURE_ASSISTANT && component == expectedActivity)
            }
        }

        internal fun requestSession(): Boolean {
            val service = activeService
            if (service == null) {
                AndroidAgentLogger.warnThrottled("assistant_session_not_ready") {
                    "Assistant session service is not ready"
                }
                return false
            }
            return try {
                service.showEtaSession()
                true
            } catch (exception: RuntimeException) {
                AndroidAgentLogger.warnThrottled("assistant_session_request_failed") {
                    "Assistant session request rejected: type=${exception.javaClass.simpleName}"
                }
                false
            }
        }
    }
}

class EtaVoiceAssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            if (!EtaVoiceInteractionService.requestSession()) {
                showFallback()
            }
        } finally {
            finish()
        }
    }

    private fun showFallback() {
        try {
            if (Settings.canDrawOverlays(this)) {
                // The foreground ASSIST bridge starts the existing panel. No system
                // session exists on this path, so no screen context is claimed.
                EtaAssistantOverlayService.show(this, null)
            } else {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                )
            }
        } catch (exception: RuntimeException) {
            // Respect Android service/activity launch restrictions; do not replace
            // this with a background foreground-service launch or a new UI.
            AndroidAgentLogger.warnThrottled("assistant_fallback_launch_failed") {
                "Assistant fallback launch rejected: type=${exception.javaClass.simpleName}"
            }
        }
    }
}
