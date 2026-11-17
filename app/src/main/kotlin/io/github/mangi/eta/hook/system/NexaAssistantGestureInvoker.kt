package io.github.mangi.eta.hook.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.core.safeLogType

/** Opens the existing assistant; a gesture never submits a prompt or changes the default. */
internal object NexaAssistantGestureInvoker {
    private const val ACCESS_VOICE_INTERACTION_SERVICE =
        "android.permission.ACCESS_VOICE_INTERACTION_SERVICE"

    fun trigger(context: Context, logger: ModuleLogger, source: String): Boolean =
        launch(
            context = context,
            requestSession = {
                // SystemUI can normally call the privileged manager API. ColorDirect
                // may not hold ACCESS_VOICE_INTERACTION_SERVICE; rejection must use
                // the existing ASSIST bridge instead of weakening its permissions.
                context.checkSelfPermission(ACCESS_VOICE_INTERACTION_SERVICE) ==
                    PackageManager.PERMISSION_GRANTED &&
                    AssistantManager.showAssistantSession(
                        context = context,
                        target = PowerAssistantTarget.ETA,
                        logger = logger,
                        source = source,
                        logFailures = false,
                    )
            },
            onFailure = { throwable ->
                logger.warnThrottled("${source}_nexa_assistant_launch_failed") {
                    "$source: NEXA 助手入口调用失败，type=${throwable.safeLogType()}"
                }
            },
        )

    /**
     * The direct session retains the original foreground app for assist capture.
     * The bridge asks NEXA's own bound VoiceInteractionService to show its session;
     * if that service is not ready, the existing panel opens without screen context.
     * No screen data or automatic submission instructions cross the Activity entry.
     */
    internal fun launch(
        context: Context,
        requestSession: () -> Boolean,
        onFailure: (Throwable) -> Unit = {},
    ): Boolean {
        val sessionStarted = try {
            requestSession()
        } catch (exception: Exception) {
            // A ROM can reject this caller even though the session API exists.
            onFailure(exception)
            false
        } catch (error: LinkageError) {
            onFailure(error)
            false
        }
        if (sessionStarted) return true

        return try {
            // An explicit start works even when package visibility prevents this
            // OEM process from querying NEXA. Android still checks installation,
            // component state, export rules and background activity restrictions.
            // Use the invoking Context's user; do not redirect another user to 0.
            context.startActivity(
                Intent(Intent.ACTION_ASSIST)
                    .setComponent(
                        ComponentName(
                            ModuleConfig.ETA_PACKAGE,
                            "io.github.mangi.eta.agent.voice.EtaVoiceAssistActivity",
                        ),
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (exception: Exception) {
            onFailure(exception)
            false
        } catch (error: LinkageError) {
            onFailure(error)
            false
        }
    }
}
