package io.github.zhh_lgtm.nexus.application.api

import io.github.zhh_lgtm.nexus.kernel.api.EffectClass
import io.github.zhh_lgtm.nexus.kernel.api.ReplayPolicy
import io.github.zhh_lgtm.nexus.kernel.api.ToolDescriptor

/** 恢复一次结果未知的工具调用时可以采取的动作。 */
enum class ReplayDecision {
    /** 只读或已声明可安全重复：恢复后可以直接重试。 */
    RESUME_AUTOMATIC,
    /** 有可识别 verifier：先核对是否已经生效，再决定是否续跑。 */
    VERIFY_THEN_RESUME,
    /** 必须由本地用户当场确认后才能重试，不能由模型自行重放。 */
    REQUIRE_USER,
    /** 永不自动重放，只保留待核对事实。 */
    NEVER,
}

data class ReplayVerdict(
    val decision: ReplayDecision,
    val code: String,
    val detail: String,
    val verifierId: String? = null,
)

/**
 * 中断恢复的唯一策略判定。只读 descriptor 与 verifier 是否存在，不接触运行时状态，
 * 因此可以在 checkpoint 恢复、Runtime 取消和模型失败三条恢复路径上复用同一份结论。
 *
 * 声明的 [ReplayPolicy] 只允许比 effectClass 自身的下限更严格：外部工具的名字或声明
 * 不能把破坏性副作用降级成自动重放。未知 descriptor、未知策略或缺失 verifier 一律
 * fail-closed 到人工确认。
 */
object ReplayPolicyEngine {
    fun decide(descriptor: ToolDescriptor?, verifierKnown: Boolean): ReplayVerdict {
        if (descriptor == null) {
            return ReplayVerdict(
                decision = ReplayDecision.REQUIRE_USER,
                code = "REPLAY_UNKNOWN_TOOL",
                detail = "该工具没有可核对的副作用声明，执行状态未知，必须交给用户确认。",
            )
        }
        return decide(
            policy = descriptor.replayPolicy,
            effect = descriptor.effectClass,
            verifierId = descriptor.verifierId,
            verifierKnown = verifierKnown,
        )
    }

    fun decide(
        policy: ReplayPolicy?,
        effect: EffectClass?,
        verifierId: String?,
        verifierKnown: Boolean,
    ): ReplayVerdict {
        if (policy == null || effect == null) {
            return ReplayVerdict(
                decision = ReplayDecision.REQUIRE_USER,
                code = "REPLAY_UNKNOWN_DESCRIPTOR",
                detail = "缺少可识别的副作用或重放策略，执行状态未知，必须交给用户确认。",
            )
        }
        return when (stricter(policy, floorFor(effect))) {
            ReplayPolicy.AUTOMATIC -> ReplayVerdict(
                decision = ReplayDecision.RESUME_AUTOMATIC,
                code = "REPLAY_AUTOMATIC",
                detail = "只读效果可以安全地重新执行。",
            )
            ReplayPolicy.VERIFY_THEN_RESUME -> when {
                verifierId.isNullOrBlank() -> ReplayVerdict(
                    decision = ReplayDecision.REQUIRE_USER,
                    code = "REPLAY_VERIFIER_MISSING",
                    detail = "写入副作用需要先核对，但该工具没有可识别 verifier，必须交给用户确认。",
                )
                !verifierKnown -> ReplayVerdict(
                    decision = ReplayDecision.REQUIRE_USER,
                    code = "REPLAY_VERIFIER_UNKNOWN",
                    detail = "写入副作用的 verifier 不在当前工具表中，必须交给用户确认。",
                    verifierId = verifierId.trim(),
                )
                else -> ReplayVerdict(
                    decision = ReplayDecision.VERIFY_THEN_RESUME,
                    code = "REPLAY_VERIFY_THEN_RESUME",
                    detail = "重试前必须先调用 verifier 核对是否已经生效。",
                    verifierId = verifierId.trim(),
                )
            }
            ReplayPolicy.REQUIRE_USER -> ReplayVerdict(
                decision = ReplayDecision.REQUIRE_USER,
                code = "REPLAY_REQUIRE_USER",
                detail = "该副作用需要本地用户确认后才能重试。",
            )
            ReplayPolicy.NEVER -> ReplayVerdict(
                decision = ReplayDecision.NEVER,
                code = "REPLAY_NEVER",
                detail = "该副作用永不自动重放，只能把未知结果交给人核对。",
            )
        }
    }

    /** effectClass 自身允许的最宽松重放策略；声明值只能更严格。 */
    fun floorFor(effect: EffectClass): ReplayPolicy = when (effect) {
        EffectClass.READ -> ReplayPolicy.AUTOMATIC
        EffectClass.REVERSIBLE_WRITE, EffectClass.IDEMPOTENT_WRITE -> ReplayPolicy.VERIFY_THEN_RESUME
        EffectClass.DESTRUCTIVE -> ReplayPolicy.NEVER
        EffectClass.NON_IDEMPOTENT_WRITE,
        EffectClass.EXTERNAL_SIDE_EFFECT,
        EffectClass.GUI_ACTION,
        EffectClass.HIGH_RISK,
        -> ReplayPolicy.REQUIRE_USER
    }

    private fun stricter(declared: ReplayPolicy, floor: ReplayPolicy): ReplayPolicy =
        if (rank(declared) >= rank(floor)) declared else floor

    private fun rank(policy: ReplayPolicy): Int = when (policy) {
        ReplayPolicy.AUTOMATIC -> 0
        ReplayPolicy.VERIFY_THEN_RESUME -> 1
        ReplayPolicy.REQUIRE_USER -> 2
        ReplayPolicy.NEVER -> 3
    }
}
