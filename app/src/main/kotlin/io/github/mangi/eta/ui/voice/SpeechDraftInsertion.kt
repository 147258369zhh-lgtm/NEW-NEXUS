package io.github.mangi.eta.ui.voice

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange

/** A streaming transcript owns one selection, not a fresh append for every ASR preview. */
internal class SpeechDraftInsertion(private val field: TextFieldState) {
    private var original: String? = null
    private var originalSelection = TextRange.Zero
    private var expected = ""
    private var start = 0
    private var end = 0

    fun begin() {
        cancel()
        original = field.text.toString()
        originalSelection = field.selection
        expected = field.text.toString()
        start = field.selection.min
        end = field.selection.max
    }

    fun update(text: String): Boolean {
        if (original == null || text.isBlank()) return false
        // Editing or externally replacing the draft takes ownership back from speech.
        if (field.text.toString() != expected) { detach(); return false }
        if (expected.substring(start, end) == text) return false
        field.edit {
            replace(start, end, text)
            selection = TextRange(start + text.length)
        }
        end = start + text.length
        expected = field.text.toString()
        return true
    }

    fun commit(text: String): Boolean = update(text).also { detach() }

    fun cancel() {
        val before = original
        if (before != null && field.text.toString() == expected) field.edit {
            replace(0, length, before)
            selection = originalSelection
        }
        detach()
    }

    fun detach() { original = null }
}
