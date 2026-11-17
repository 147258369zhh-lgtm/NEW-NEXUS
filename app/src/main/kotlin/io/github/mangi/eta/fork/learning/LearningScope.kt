package io.github.mangi.eta.fork.learning

/** Scope is ownership, independent of experience kind. Legacy rows stay global. */
internal object LearningScope {
    private val scoped = Regex("(project|conversation|task|meeting|character):[A-Za-z0-9_-]{1,128}")
    fun valid(scope: String): Boolean = scope == "global" || scoped.matches(scope)
}
