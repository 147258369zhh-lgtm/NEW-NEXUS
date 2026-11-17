package io.github.mangi.eta.fork.a2a

/** Peers may return their answer in an artifact, status message, or conversation history. */
internal fun A2ATask.resultText(): String = buildList {
    artifacts.flatMap { it.parts }.mapNotNull(A2APart::text).let(::addAll)
    status.message?.takeIf { it.role == A2ARole.AGENT }?.parts?.mapNotNull(A2APart::text)?.let(::addAll)
    history.lastOrNull { it.role == A2ARole.AGENT }?.parts?.mapNotNull(A2APart::text)?.let(::addAll)
}.filter(String::isNotBlank).distinct().joinToString("\n")
