package io.github.mangi.eta.data.model

/** Logical identity only: folders, projects and physical paths never form part of a draft key. */
internal data class ConversationSubject(val kind: Kind, val id: String) {
    enum class Kind(val value: String) { CHAT("chat"), MEETING("meeting"), NEW_CHAT("new_chat") }

    init { require(if (kind == Kind.NEW_CHAT) id.isEmpty() else id.isNotBlank()) }

    val key: String get() = "${kind.value}:${id.length}:$id"

    companion object {
        fun chat(id: String?) = if (id == null) ConversationSubject(Kind.NEW_CHAT, "") else ConversationSubject(Kind.CHAT, id)
        fun meeting(id: String) = ConversationSubject(Kind.MEETING, id)
    }
}
