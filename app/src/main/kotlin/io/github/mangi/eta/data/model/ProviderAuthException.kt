package io.github.mangi.eta.data.model

internal class ProviderAuthException(val code: String, message: String) : RuntimeException(message)
