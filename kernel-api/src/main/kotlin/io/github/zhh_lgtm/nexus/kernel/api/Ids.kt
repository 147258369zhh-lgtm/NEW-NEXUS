package io.github.zhh_lgtm.nexus.kernel.api

private val stableIdPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,255}$")

internal fun requireStableId(kind: String, value: String): String {
    require(value.matches(stableIdPattern)) { "$kind is not a valid stable id" }
    return value
}

data class SessionId(val value: String) {
    init { requireStableId("SessionId", value) }
}

data class RunId(val value: String) {
    init { requireStableId("RunId", value) }
}

data class TurnId(val value: String) {
    init { requireStableId("TurnId", value) }
}

data class ItemId(val value: String) {
    init { requireStableId("ItemId", value) }
}

data class TaskId(val value: String) {
    init { requireStableId("TaskId", value) }
}

data class GoalId(val value: String) {
    init { requireStableId("GoalId", value) }
}

data class PlanId(val value: String) {
    init { requireStableId("PlanId", value) }
}

data class ArtifactId(val value: String) {
    init { requireStableId("ArtifactId", value) }
}

data class HostId(val value: String) {
    init { requireStableId("HostId", value) }
}

data class PeerId(val value: String) {
    init { requireStableId("PeerId", value) }
}

data class PluginId(val value: String) {
    init { requireStableId("PluginId", value) }
}
