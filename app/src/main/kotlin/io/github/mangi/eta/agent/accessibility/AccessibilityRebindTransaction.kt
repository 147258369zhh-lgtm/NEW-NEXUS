package io.github.mangi.eta.agent.accessibility

internal data class AccessibilitySettingsSnapshot(val enabled: Boolean, val services: String)

internal interface AccessibilitySettingsAccess {
    fun read(): AccessibilitySettingsSnapshot
    fun writeServices(value: String)
}

internal enum class AccessibilityRebindOutcome { REQUESTED, NOT_CONFIGURED, MANUAL_REQUIRED, CANCELLED, FAILED, UNCONFIRMED }

/**
 * 只重绑已经由用户启用的目标，绝不切换总开关。恢复阶段重新读取列表，保留期间新增的服务。
 * SettingsProvider 不提供比较交换，因此不声称能与其他设置写入者构成全局原子事务。
 */
internal class AccessibilityRebindTransaction(
    private val settings: AccessibilitySettingsAccess,
    private val target: String,
    private val allowed: () -> Boolean,
    private val beginMutation: () -> Boolean,
    private val completeMutation: () -> Boolean,
    private val pause: () -> Unit,
    private val restoreAllowed: () -> Boolean = allowed,
) {
    fun execute(): AccessibilityRebindOutcome {
        var removed = false
        var journaled = false
        var restored = false
        var journalCleared = true
        return try {
            if (!allowed()) return AccessibilityRebindOutcome.CANCELLED
            val before = settings.read()
            if (!before.enabled || !contains(before.services)) return AccessibilityRebindOutcome.NOT_CONFIGURED
            // AOSP 会在最后一个服务被移除后派生关闭总开关，无法与用户主动关闭可靠地区分。
            if (!hasOtherService(before.services)) return AccessibilityRebindOutcome.MANUAL_REQUIRED
            if (!beginMutation()) return AccessibilityRebindOutcome.CANCELLED
            journaled = true
            journalCleared = false
            // 记录恢复标记之后再次读取，不能用早先缓存覆盖新配置。
            val latest = settings.read()
            if (!allowed()) return AccessibilityRebindOutcome.CANCELLED
            if (!latest.enabled || !contains(latest.services)) return AccessibilityRebindOutcome.NOT_CONFIGURED
            if (!hasOtherService(latest.services)) return AccessibilityRebindOutcome.MANUAL_REQUIRED
            removed = true // 即使写入抛错也可能已经生效，finally 必须核对实际状态。
            settings.writeServices(entries(latest.services).filterNot(::matches).joinToString(":"))
            pause()
            restored = restore()
            if (restored) AccessibilityRebindOutcome.REQUESTED else AccessibilityRebindOutcome.CANCELLED
        } catch (_: Exception) {
            AccessibilityRebindOutcome.FAILED
        } finally {
            // 业务取消不代表可以把服务遗留在临时关闭状态；只在授权仍有效时合并恢复。
            if (journaled) {
                val settled = runCatching {
                    if (!removed || restored) true else restore()
                }.getOrDefault(false)
                if (settled) journalCleared = runCatching { completeMutation() }.getOrDefault(false)
            }
        }.let { result ->
            if (!journalCleared || (removed && runCatching(restoreAllowed).getOrDefault(false) &&
                !runCatching { contains(settings.read().services) }.getOrDefault(false)
            )) {
                AccessibilityRebindOutcome.UNCONFIRMED
            } else result
        }
    }

    private fun restore(): Boolean {
        if (!restoreAllowed()) return false
        val current = settings.read()
        // 总开关被用户关闭时，不擅自打开，也不再添加 Eta。
        if (!current.enabled || !restoreAllowed()) return false
        if (!contains(current.services)) {
            settings.writeServices((entries(current.services) + target).joinToString(":"))
        }
        val verified = settings.read()
        return verified.enabled && contains(verified.services)
    }

    private fun contains(value: String) = entries(value).any(::matches)
    private fun hasOtherService(value: String) = entries(value).any { !matches(it) }
    private fun matches(value: String) = componentIdentity(value) == componentIdentity(target)

    private fun entries(value: String) = value.split(':').map(String::trim).filter(String::isNotEmpty)

    private fun componentIdentity(value: String): String {
        val packageName = value.substringBefore('/')
        val className = value.substringAfter('/', "")
        return packageName + "/" + if (className.startsWith('.')) packageName + className else className
    }
}

/** 最多三次，只有持续连接一分钟后才重置，避免快速连接/崩溃导致无限重启。 */
internal class AccessibilityRecoveryBudget {
    private var attempts = 0
    private var lastAttemptAt: Long? = null
    private var lastStableConnectionAt: Long? = null

    @Synchronized
    fun recordConnection(connectedAt: Long?, disconnectedAt: Long?, now: Long, connected: Boolean) {
        val since = connectedAt ?: return
        val until = if (connected) now else disconnectedAt ?: return
        if (since != lastStableConnectionAt && until - since >= STABLE_MS) {
            reset()
            lastStableConnectionAt = since
        }
    }

    @Synchronized
    fun acquire(now: Long): Long? {
        if (attempts >= 3) return null
        val wait = lastAttemptAt?.let { (DELAYS[attempts] - (now - it)).coerceAtLeast(0L) } ?: 0L
        if (wait > 0L) return wait
        attempts++
        lastAttemptAt = now
        return 0L
    }

    @Synchronized
    fun reset() { attempts = 0; lastAttemptAt = null }

    companion object {
        const val STABLE_MS = 60_000L
        private val DELAYS = longArrayOf(0L, 5_000L, 20_000L)
    }
}
