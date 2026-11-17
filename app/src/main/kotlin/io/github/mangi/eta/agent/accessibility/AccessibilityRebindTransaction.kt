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

/** Persisted fields deliberately contain wall time and boot identity, never process-local elapsed time. */
internal data class AccessibilityRecoveryBudgetRecord(
    val attempts: Int = 0,
    val lastAttemptAtEpochMs: Long? = null,
    val lastAttemptBootCount: Int? = null,
)

internal interface AccessibilityRecoveryBudgetStore {
    fun load(): AccessibilityRecoveryBudgetRecord
    fun save(record: AccessibilityRecoveryBudgetRecord): Boolean
}

/** Pure persistence adapter so durability and corrupt-value behavior can be tested without Android. */
internal class MapAccessibilityRecoveryBudgetStore(
    var values: MutableMap<String, Any?> = mutableMapOf(),
    var writesSucceed: Boolean = true,
) : AccessibilityRecoveryBudgetStore {
    override fun load() = AccessibilityRecoveryBudgetRecord(
        attempts = (values[KEY_ATTEMPTS] as? Number)?.toLong()?.let { stored ->
            if (stored !in 0L..DELAYS_COUNT.toLong()) DELAYS_COUNT else stored.toInt()
        } ?: if (values.containsKey(KEY_ATTEMPTS)) DELAYS_COUNT else 0,
        lastAttemptAtEpochMs = (values[KEY_ATTEMPT_AT_EPOCH_MS] as? Number)?.toLong(),
        lastAttemptBootCount = (values[KEY_ATTEMPT_BOOT_COUNT] as? Number)?.toInt()?.takeIf { it >= 0 },
    )

    override fun save(record: AccessibilityRecoveryBudgetRecord): Boolean {
        if (!writesSucceed) return false
        values = mutableMapOf<String, Any?>().apply {
            put(KEY_ATTEMPTS, record.attempts)
            record.lastAttemptAtEpochMs?.let { put(KEY_ATTEMPT_AT_EPOCH_MS, it) }
            record.lastAttemptBootCount?.let { put(KEY_ATTEMPT_BOOT_COUNT, it) }
        }
        return true
    }

    companion object {
        private const val DELAYS_COUNT = 3
        const val KEY_ATTEMPTS = "recovery_attempts"
        const val KEY_ATTEMPT_AT_EPOCH_MS = "recovery_attempt_at_epoch_ms"
        const val KEY_ATTEMPT_BOOT_COUNT = "recovery_attempt_boot_count"
    }
}

private class MemoryAccessibilityRecoveryBudgetStore(
    private var record: AccessibilityRecoveryBudgetRecord = AccessibilityRecoveryBudgetRecord(),
) : AccessibilityRecoveryBudgetStore {
    override fun load() = record
    override fun save(record: AccessibilityRecoveryBudgetRecord): Boolean {
        this.record = record
        return true
    }
}

/** 最多三次，只有持续连接一分钟后才重置，避免快速连接/崩溃导致无限重启。 */
internal class AccessibilityRecoveryBudget(
    private val store: AccessibilityRecoveryBudgetStore = MemoryAccessibilityRecoveryBudgetStore(),
) {
    private var record = sanitize(store.load())
    private var lastStableConnectionAt: Long? = null
    private var lastStableBootCount: Int? = null

    @Synchronized
    fun recordConnection(
        connectedAt: Long?,
        disconnectedAt: Long?,
        now: Long,
        connected: Boolean,
        bootCount: Int? = 0,
    ) {
        val since = connectedAt ?: return
        val until = if (connected) now else disconnectedAt ?: return
        if ((since != lastStableConnectionAt || bootCount != lastStableBootCount) && until - since >= STABLE_MS) {
            if (reset()) {
                lastStableConnectionAt = since
                lastStableBootCount = bootCount
            }
        }
    }

    @Synchronized
    fun acquire(nowEpochMs: Long, bootCount: Int? = 0): Long? {
        if (record.attempts >= DELAYS.size) return null
        if (record.attempts > 0 && record.lastAttemptAtEpochMs == null) return null
        val wait = cooldownRemaining(nowEpochMs, bootCount, record.attempts)
        if (wait > 0L) return wait
        val next = record.copy(
            attempts = record.attempts + 1,
            lastAttemptAtEpochMs = nowEpochMs,
            lastAttemptBootCount = bootCount.takeIf { nowEpochMs > 0L },
        )
        // Fail closed if the attempt cannot be durably recorded; a process restart must not reopen the
        // budget merely because persistence failed after a Root request was issued.
        if (!store.save(next)) return null
        record = next
        return 0L
    }

    @Synchronized
    fun reset(): Boolean {
        lastStableConnectionAt = null
        lastStableBootCount = null
        return resetRecord()
    }

    private fun resetRecord(): Boolean {
        val cleared = AccessibilityRecoveryBudgetRecord()
        if (!store.save(cleared)) return false
        record = cleared
        return true
    }

    private fun cooldownRemaining(nowEpochMs: Long, bootCount: Int?, attempts: Int): Long {
        val attemptedAt = record.lastAttemptAtEpochMs
            ?: return 0L
        val attemptedBoot = record.lastAttemptBootCount
        val delay = DELAYS[attempts]
        // Wall time is meaningful only within one known boot. A reboot, a backwards clock, or a boot
        // identity that changed after persistence is ambiguous, so start a fresh conservative delay
        // from the first observed timestamp in the new time domain. Persisting it prevents every
        // retry in the same boot from charging the full delay forever.
        if (nowEpochMs > 0L && bootCount != null && (attemptedBoot == null || attemptedBoot != bootCount || nowEpochMs < attemptedAt)) {
            val rebased = record.copy(lastAttemptAtEpochMs = nowEpochMs, lastAttemptBootCount = bootCount)
            if (store.save(rebased)) record = rebased
            return delay
        }
        if (nowEpochMs <= 0L || nowEpochMs < attemptedAt) return delay
        if (attemptedBoot != null && bootCount == null) return delay
        return (delay - (nowEpochMs - attemptedAt)).coerceAtLeast(0L)
    }

    private fun sanitize(value: AccessibilityRecoveryBudgetRecord): AccessibilityRecoveryBudgetRecord {
        // A negative value cannot be a valid persisted budget. Treat it as exhausted so
        // corrupted/overflowed storage never resets the automatic recovery allowance.
        val attempts = if (value.attempts < 0) DELAYS.size else value.attempts.coerceAtMost(DELAYS.size)
        val hasTimestamp = (value.lastAttemptAtEpochMs ?: 0L) > 0L && (value.lastAttemptBootCount ?: -1) >= 0
        if (!hasTimestamp) return AccessibilityRecoveryBudgetRecord(attempts = attempts)
        if (attempts == 0) return AccessibilityRecoveryBudgetRecord()
        return AccessibilityRecoveryBudgetRecord(
            attempts = attempts,
            lastAttemptAtEpochMs = value.lastAttemptAtEpochMs.takeIf { hasTimestamp },
            lastAttemptBootCount = value.lastAttemptBootCount.takeIf { hasTimestamp },
        )
    }

    companion object {
        const val STABLE_MS = 60_000L
        private val DELAYS = longArrayOf(0L, 5_000L, 20_000L)
    }
}
