package io.github.mangi.eta.ui.screens.control

import android.content.Context
import androidx.core.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
internal enum class AppControlMessageRole {
    USER,
    ASSISTANT,
}

@Serializable
internal data class AppControlMessage(
    val id: String,
    val role: AppControlMessageRole,
    val text: String,
    val toolStatus: String = "",
    val isError: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

internal class AppControlHistoryStore(context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val file = AtomicFile(File(context.filesDir, "eta_app_control/history.json").apply {
        parentFile?.mkdirs()
    })

    fun load(): List<AppControlMessage> {
        if (!file.baseFile.isFile) return emptyList()
        return runCatching {
            decode(file.readFully().toString(Charsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    fun save(messages: List<AppControlMessage>) {
        val retained = messages.takeLast(MAX_MESSAGES)
        val output = file.startWrite()
        try {
            output.write(json.encodeToString(retained).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    private fun decode(raw: String): List<AppControlMessage> =
        runCatching { json.decodeFromString<List<AppControlMessage>>(raw) }
            .getOrElse {
                // Older development builds may have stored a single JSON object.
                listOf(json.decodeFromString<AppControlMessage>(raw))
            }
            .filter { it.id.isNotBlank() && it.text.length <= MAX_MESSAGE_CHARS }
            .takeLast(MAX_MESSAGES)

    private companion object {
        const val MAX_MESSAGES = 80
        const val MAX_MESSAGE_CHARS = 128_000
    }
}
