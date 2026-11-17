package io.github.mangi.eta.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import io.github.mangi.eta.agent.model.AgentInvocationReferences

internal fun invocationReferenceStyle(isSkill: Boolean, dark: Boolean): SpanStyle {
    val color = if (isSkill) {
        if (dark) Color(0xFF82D9B5) else Color(0xFF127D59)
    } else {
        if (dark) Color(0xFF94BEFF) else Color(0xFF2864C9)
    }
    return SpanStyle(color = color, background = color.copy(alpha = .10f), fontWeight = FontWeight.Medium)
}

internal fun invocationReferenceText(text: String, dark: Boolean): AnnotatedString {
    val references = AgentInvocationReferences.parse(text)
    if (references.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var offset = 0
        references.forEach { ref ->
            append(text.substring(offset, ref.start))
            val start = length
            append(ref.display)
            addStyle(invocationReferenceStyle(ref.isSkill, dark), start, length)
            offset = ref.end
        }
        append(text.substring(offset))
    }
}

