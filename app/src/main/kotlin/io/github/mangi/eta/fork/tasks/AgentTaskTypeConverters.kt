package io.github.mangi.eta.fork.tasks

import androidx.room.TypeConverter

internal class AgentTaskTypeConverters {
    @TypeConverter fun taskStateToString(value: AgentTaskState): String = value.wireValue
    @TypeConverter fun stringToTaskState(value: String): AgentTaskState = AgentTaskState.fromWire(value)

    @TypeConverter fun runKindToString(value: AgentTaskRunKind): String = value.name
    @TypeConverter fun stringToRunKind(value: String): AgentTaskRunKind = AgentTaskRunKind.valueOf(value)

    @TypeConverter fun scheduleKindToString(value: AgentScheduleKind): String = value.name
    @TypeConverter fun stringToScheduleKind(value: String): AgentScheduleKind = AgentScheduleKind.valueOf(value)

    @TypeConverter fun missedPolicyToString(value: MissedRunPolicy): String = value.name
    @TypeConverter fun stringToMissedPolicy(value: String): MissedRunPolicy = MissedRunPolicy.valueOf(value)

    @TypeConverter fun attentionKindToString(value: AgentTaskAttentionKind): String = value.name
    @TypeConverter fun stringToAttentionKind(value: String): AgentTaskAttentionKind =
        AgentTaskAttentionKind.valueOf(value)

    @TypeConverter fun attentionStatusToString(value: AgentTaskAttentionStatus): String = value.name
    @TypeConverter fun stringToAttentionStatus(value: String): AgentTaskAttentionStatus =
        AgentTaskAttentionStatus.valueOf(value)
}
