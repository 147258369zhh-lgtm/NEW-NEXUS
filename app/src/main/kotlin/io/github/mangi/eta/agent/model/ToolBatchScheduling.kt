package io.github.mangi.eta.agent.model

/**
 * 单个工具调用在批次内的并发槽位，来源只能是本地 ToolDescriptor。
 *
 * [concurrencyKey] 是互斥资源键：同一键表示同一时刻只能有一个调用访问的资源，
 * 因此同键调用必须串行；[concurrencyKey] 为 null 表示工具没有声明并发安全性，
 * 调度时按串行屏障处理，避免未知共享状态被并发访问。
 */
internal data class ToolBatchSlot(
    val readOnly: Boolean,
    val concurrencyKey: String?,
) {
    val parallelRead: Boolean get() = readOnly && concurrencyKey != null

    companion object {
        val SERIAL = ToolBatchSlot(readOnly = false, concurrencyKey = null)
    }
}

/**
 * 把模型给出的工具批次切成"可同时执行的只读区间"和"必须原地串行的调用"。
 *
 * 只有连续、已发布、只读且资源键互不相同的调用才会进入同一区间；任何写入、GUI、
 * 外部副作用、未知工具、相同资源键或超过上限的调用都会截断区间，所以后续调用
 * 一定排在它前面所有调用结算之后，模型给出的调用顺序仍然是唯一的依赖顺序。
 */
internal object ToolBatchScheduler {
    const val MAX_PARALLEL_READ_TOOLS = 3

    /** 返回从 [start] 开始可同时执行的连续区间；不足两个调用时返回 null，由调用方串行执行。 */
    fun parallelReadRun(
        calls: List<AgentModelClient.ToolCall>,
        start: Int,
        isAvailable: (String) -> Boolean,
        slotOf: (String) -> ToolBatchSlot,
    ): IntRange? {
        val head = calls.getOrNull(start) ?: return null
        val headSlot = slotOf(head.name)
        if (!headSlot.parallelRead || !isAvailable(head.name)) return null
        val keys = linkedSetOf(headSlot.concurrencyKey ?: return null)
        var last = start
        while (last + 1 < calls.size && last + 1 - start < MAX_PARALLEL_READ_TOOLS) {
            val next = calls[last + 1]
            val slot = slotOf(next.name)
            if (!slot.parallelRead || !isAvailable(next.name)) break
            val key = slot.concurrencyKey ?: break
            if (!keys.add(key)) break
            last += 1
        }
        return if (last > start) start..last else null
    }
}
