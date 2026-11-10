package io.github.mangi.eta.agent.accessibility

import androidx.core.net.toUri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import io.github.mangi.eta.agent.phone.RootPhoneProviderAccess
import io.github.mangi.eta.agent.phone.PhoneProviderAccess
import kotlin.system.exitProcess
import org.json.JSONObject

/** 单次 Root helper，无常驻 daemon。只处理由当前应用签发的短期恢复租约。 */
internal object AccessibilityRecoveryCommandMain {
    const val MARKER = "ETA_ACCESSIBILITY_RECOVERY:"
    const val CONTROL_METHOD = "root_accessibility_recovery"

    @JvmStatic
    fun main(arguments: Array<String>) {
        val outcome = runCatching {
            require(Process.myUid() == Process.ROOT_UID)
            val bytes = System.`in`.readNBytes(4_097)
            require(bytes.size <= 4_096)
            val request = JSONObject(bytes.toString(Charsets.UTF_8))
            require(request.getInt("version") == 1)
            val action = request.optString("action", ACTION_REBIND)
            require(action == ACTION_REBIND || action == ACTION_EXEMPT)
            val token = request.getString("token")
            require(token.length == 36)
            val userId = request.getInt("user_id")
            require(userId in 0..21474)
            val access = RootPhoneProviderAccess(userId)
            fun control(phase: String): Bundle = access.call(
                AccessibilityProtectionProtocol.HEALTH_URI, CONTROL_METHOD, token,
                Bundle().apply {
                    putInt("version", 1)
                    putString("phase", phase)
                },
            ) ?: Bundle.EMPTY
            val approval = control("check")
            require(approval.getBoolean("allowed") && approval.getInt("user_id", -1) == userId)
            val component = requireNotNull(approval.getString("component"))
            val settings = RootAccessibilitySettingsAccess(access, userId)
            if (action == ACTION_EXEMPT) {
                val targetPackage = requireNotNull(android.content.ComponentName.unflattenFromString(component)).packageName
                HelperResult(
                    AccessibilityRebindOutcome.REQUESTED,
                    settings.ensureVendorRiskCloseExemption(targetPackage),
                )
            } else {
                val rebind = AccessibilityRebindTransaction(
                    settings = settings,
                    target = component,
                    allowed = { control("check").getBoolean("allowed") },
                    beginMutation = { control("begin").getBoolean("allowed") },
                    completeMutation = { control("complete").getBoolean("completed") },
                    pause = { SystemClock.sleep(500L) },
                    restoreAllowed = { control("restore").getBoolean("allowed") },
                ).execute()
                val targetPackage = requireNotNull(android.content.ComponentName.unflattenFromString(component)).packageName
                HelperResult(rebind, settings.ensureVendorRiskCloseExemption(targetPackage))
            }
        }.getOrDefault(AccessibilityRebindOutcome.FAILED)
        val payload = when (outcome) {
            is HelperResult -> JSONObject()
                .put("version", 1)
                .put("outcome", outcome.rebind.name)
                .put("exemption", outcome.exemption.name)
            else -> JSONObject().put("version", 1).put("outcome", outcome.toString())
        }
        println(MARKER + payload)
        exitProcess(0)
    }

    const val ACTION_REBIND = "rebind"
    const val ACTION_EXEMPT = "exempt"

    private data class HelperResult(
        val rebind: AccessibilityRebindOutcome,
        val exemption: VendorRiskCloseExemptionOutcome,
    )
}

internal enum class VendorRiskCloseExemptionOutcome {
    APPLIED,
    ALREADY_EXEMPT,
    UNSUPPORTED,
    FAILED,
}

/** 复用已有 Root provider lease；逐次读回校验，不持有不受管理的 provider/binder 引用。 */
internal class RootAccessibilitySettingsAccess(
    private val access: PhoneProviderAccess,
    private val userId: Int,
) : AccessibilitySettingsAccess {
    private val uri = "content://settings".toUri()

    override fun read(): AccessibilitySettingsSnapshot {
        val enabled = readValue("accessibility_enabled")
        check(enabled == "0" || enabled == "1")
        return AccessibilitySettingsSnapshot(enabled == "1", readValue("enabled_accessibility_services").orEmpty())
    }

    override fun writeServices(value: String) {
        require(value.length <= 64 * 1024)
        access.call(uri, "PUT_secure", "enabled_accessibility_services", Bundle().apply {
            putInt("_user", userId)
            putString("value", value)
        })
        check(readValue("enabled_accessibility_services").orEmpty() == value)
    }

    /**
     * ColorOS 会在解锁后关闭未列入厂商白名单的第三方无障碍服务。
     * 仅在厂商键真实存在时追加当前包；其他 ROM 返回 UNSUPPORTED，不创建无关设置。
     */
    fun ensureVendorRiskCloseExemption(packageName: String): VendorRiskCloseExemptionOutcome {
        require(packageName.isNotBlank())
        return try {
            val current = readOptionalValue(VENDOR_RISK_SKIP_PACKAGE) ?: return VendorRiskCloseExemptionOutcome.UNSUPPORTED
            val packages = current.split(':').map(String::trim).filter(String::isNotEmpty)
            if (packageName in packages) return VendorRiskCloseExemptionOutcome.ALREADY_EXEMPT
            val updated = (packages + packageName).joinToString(":")
            writeValue(VENDOR_RISK_SKIP_PACKAGE, updated)
            val verified = readOptionalValue(VENDOR_RISK_SKIP_PACKAGE)
                ?.split(':')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                .orEmpty()
            if (packageName in verified) {
                VendorRiskCloseExemptionOutcome.APPLIED
            } else {
                VendorRiskCloseExemptionOutcome.FAILED
            }
        } catch (_: Exception) {
            VendorRiskCloseExemptionOutcome.FAILED
        }
    }

    private fun readValue(key: String): String? {
        val result = access.call(uri, "GET_secure", key, Bundle().apply { putInt("_user", userId) })
        check(result != null && result.containsKey("value"))
        return result.getString("value")
    }

    private fun readOptionalValue(key: String): String? {
        val result = access.call(uri, "GET_secure", key, Bundle().apply { putInt("_user", userId) })
        check(result != null && result.containsKey("value"))
        return result.getString("value")
    }

    private fun writeValue(key: String, value: String) {
        require(key.length <= 256 && value.length <= 64 * 1024)
        access.call(uri, "PUT_secure", key, Bundle().apply {
            putInt("_user", userId)
            putString("value", value)
        })
    }

    private companion object {
        const val VENDOR_RISK_SKIP_PACKAGE = "accessibility_turn_off_skip_package"
    }
}
