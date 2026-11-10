package io.github.mangi.eta.ui.markdown

/**
 * Converts the LaTeX subset commonly emitted by chat models into readable Unicode.
 * Block formulas retain their source for copying; this formatter is for display and for
 * inline formulas that must remain in the same paragraph as surrounding text.
 */
internal object LatexToUnicodeFormatter {
    private val commands = mapOf(
        "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ",
        "\\epsilon" to "ε", "\\varepsilon" to "ε", "\\zeta" to "ζ", "\\eta" to "η",
        "\\theta" to "θ", "\\vartheta" to "ϑ", "\\iota" to "ι", "\\kappa" to "κ",
        "\\lambda" to "λ", "\\mu" to "μ", "\\nu" to "ν", "\\xi" to "ξ",
        "\\pi" to "π", "\\rho" to "ρ", "\\sigma" to "σ", "\\tau" to "τ",
        "\\upsilon" to "υ", "\\phi" to "φ", "\\varphi" to "φ", "\\chi" to "χ",
        "\\psi" to "ψ", "\\omega" to "ω", "\\Gamma" to "Γ", "\\Delta" to "Δ",
        "\\Theta" to "Θ", "\\Lambda" to "Λ", "\\Xi" to "Ξ", "\\Pi" to "Π",
        "\\Sigma" to "Σ", "\\Phi" to "Φ", "\\Psi" to "Ψ", "\\Omega" to "Ω",
        "\\sum" to "Σ", "\\prod" to "∏", "\\int" to "∫", "\\iint" to "∬",
        "\\iiint" to "∭", "\\oint" to "∮", "\\partial" to "∂", "\\nabla" to "∇",
        "\\infty" to "∞", "\\pm" to "±", "\\mp" to "∓", "\\times" to "×",
        "\\div" to "÷", "\\cdot" to "·", "\\ast" to "∗", "\\approx" to "≈",
        "\\neq" to "≠", "\\ne" to "≠", "\\equiv" to "≡", "\\leq" to "≤",
        "\\le" to "≤", "\\geq" to "≥", "\\ge" to "≥", "\\ll" to "≪",
        "\\gg" to "≫", "\\in" to "∈", "\\notin" to "∉", "\\subset" to "⊂",
        "\\supset" to "⊃", "\\subseteq" to "⊆", "\\supseteq" to "⊇",
        "\\cup" to "∪", "\\cap" to "∩", "\\forall" to "∀", "\\exists" to "∃",
        "\\to" to "→", "\\rightarrow" to "→", "\\leftarrow" to "←",
        "\\leftrightarrow" to "↔", "\\Rightarrow" to "⇒", "\\Leftarrow" to "⇐",
        "\\Leftrightarrow" to "⇔", "\\perp" to "⊥", "\\parallel" to "∥",
        "\\angle" to "∠", "\\triangle" to "△", "\\degree" to "°",
        "\\ldots" to "…", "\\cdots" to "⋯", "\\vdots" to "⋮", "\\ddots" to "⋱",
        "\\quad" to "  ", "\\qquad" to "    ", "\\," to " ", "\\:" to " ",
        "\\;" to " ", "\\!" to "", "\\left" to "", "\\right" to "",
    ).entries.sortedByDescending { it.key.length }

    fun format(source: String): String {
        var value = source.trim()
        value = replaceGroupCommand(value, "\\frac") { numerator, denominator ->
            "(${format(numerator)})/(${format(denominator)})"
        }
        value = replaceGroupCommand(value, "\\dfrac") { numerator, denominator ->
            "(${format(numerator)})/(${format(denominator)})"
        }
        value = replaceGroupCommand(value, "\\tfrac") { numerator, denominator ->
            "${format(numerator)}/${format(denominator)}"
        }
        value = replaceGroupCommand(value, "\\sqrt") { radicand, _ -> "√(${format(radicand)})" }
        value = replaceGroupCommand(value, "\\text") { text, _ -> format(text) }
        value = replaceGroupCommand(value, "\\mathrm") { text, _ -> format(text) }
        value = replaceGroupCommand(value, "\\mathbf") { text, _ -> format(text) }
        commands.forEach { (command, display) -> value = value.replace(command, display) }
        value = stripEnvironmentWrappers(value)
        value = value.replace("\\\\", "\n").replace("\\{", "{").replace("\\}", "}")
        return applyScripts(value.replace(Regex("""\\[A-Za-z]+\s*"""), "").replace(Regex("""[{}]"""), ""))
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun replaceGroupCommand(
        input: String,
        command: String,
        transform: (String, String) -> String,
    ): String {
        var value = input
        var start = value.indexOf(command)
        while (start >= 0) {
            val firstOpen = value.indexOf('{', start + command.length)
            if (firstOpen < 0) break
            val firstClose = matchingBrace(value, firstOpen) ?: break
            val first = value.substring(firstOpen + 1, firstClose)
            var replacement = if (command == "\\sqrt") transform(first, "") else first
            if (command != "\\sqrt") {
                val secondOpen = value.indexOf('{', firstClose + 1)
                if (secondOpen < 0) break
                val secondClose = matchingBrace(value, secondOpen) ?: break
                val second = value.substring(secondOpen + 1, secondClose)
                replacement = transform(first, second)
                value = value.replaceRange(start, secondClose + 1, replacement)
            } else {
                value = value.replaceRange(start, firstClose + 1, replacement)
            }
            start = value.indexOf(command, start + replacement.length)
        }
        return value
    }

    private fun matchingBrace(value: String, open: Int): Int? {
        var depth = 0
        for (index in open until value.length) {
            when (value[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    /**
     * Android's ICU regex parser rejects unmatched braces in several otherwise valid Java
     * patterns. Parse environment wrappers directly so a model-authored matrix or malformed
     * LaTeX fragment can never crash rendering while the response is being displayed.
     */
    private fun stripEnvironmentWrappers(input: String): String {
        var value = input
        var searchFrom = 0
        while (searchFrom < value.length) {
            val begin = value.indexOf("\\begin{", searchFrom)
            if (begin < 0) break
            val beginNameEnd = value.indexOf('}', begin + "\\begin{".length)
            if (beginNameEnd < 0) break
            val environment = value.substring(begin + "\\begin{".length, beginNameEnd)
            val endMarker = "\\end{$environment}"
            val end = value.indexOf(endMarker, beginNameEnd + 1)
            if (end < 0) {
                searchFrom = beginNameEnd + 1
                continue
            }
            val body = value.substring(beginNameEnd + 1, end).replace("\\\\", "\n")
            value = value.replaceRange(begin, end + endMarker.length, body)
            searchFrom = begin + body.length
        }
        return value
    }

    private fun applyScripts(value: String): String {
        var result = value
        result = Regex("""\^\{?([0-9A-Za-z+\-=()]+)\}?""").replace(result) { match ->
            match.groupValues[1].map(::superscript).joinToString("")
        }
        result = Regex("""_\{?([0-9A-Za-z+\-=()]+)\}?""").replace(result) { match ->
            match.groupValues[1].map(::subscript).joinToString("")
        }
        return result
    }

    private fun superscript(char: Char): String = when (char) {
        '0' -> "⁰"; '1' -> "¹"; '2' -> "²"; '3' -> "³"; '4' -> "⁴"
        '5' -> "⁵"; '6' -> "⁶"; '7' -> "⁷"; '8' -> "⁸"; '9' -> "⁹"
        '+' -> "⁺"; '-' -> "⁻"; '=' -> "⁼"; '(' -> "⁽"; ')' -> "⁾"
        'i' -> "ⁱ"; 'n' -> "ⁿ"; else -> char.toString()
    }

    private fun subscript(char: Char): String = when (char) {
        '0' -> "₀"; '1' -> "₁"; '2' -> "₂"; '3' -> "₃"; '4' -> "₄"
        '5' -> "₅"; '6' -> "₆"; '7' -> "₇"; '8' -> "₈"; '9' -> "₉"
        '+' -> "₊"; '-' -> "₋"; '=' -> "₌"; '(' -> "₍"; ')' -> "₎"
        'a' -> "ₐ"; 'e' -> "ₑ"; 'h' -> "ₕ"; 'i' -> "ᵢ"; 'j' -> "ⱼ"; 'k' -> "ₖ"
        'l' -> "ₗ"; 'm' -> "ₘ"; 'n' -> "ₙ"; 'o' -> "ₒ"; 'p' -> "ₚ"; 'r' -> "ᵣ"
        's' -> "ₛ"; 't' -> "ₜ"; 'u' -> "ᵤ"; 'v' -> "ᵥ"; 'x' -> "ₓ"; else -> char.toString()
    }
}
