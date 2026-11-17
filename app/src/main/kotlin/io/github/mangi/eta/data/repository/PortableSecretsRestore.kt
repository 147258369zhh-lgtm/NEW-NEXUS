package io.github.mangi.eta.data.repository

import kotlinx.coroutines.CancellationException

internal data class PortableSecretsRestoreFailure(val section: String, val error: Throwable)

/** Applies independent migration sections so one damaged category cannot block all others. */
internal object PortableSecretsRestore {
    suspend fun run(sections: List<Pair<String, suspend () -> Unit>>): List<PortableSecretsRestoreFailure> = buildList {
        sections.forEach { (name, restore) ->
            try {
                restore()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                add(PortableSecretsRestoreFailure(name, failure))
            }
        }
    }
}
