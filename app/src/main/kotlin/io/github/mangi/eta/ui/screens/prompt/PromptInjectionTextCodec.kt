package io.github.mangi.eta.ui.screens.prompt

import io.github.mangi.eta.agent.prompt.PromptImportCodec
import io.github.mangi.eta.agent.prompt.PromptImportResult
import io.github.mangi.eta.agent.prompt.PromptTemplate

/** UI entry points backed by the same parser used by the actual file-import path. */
internal object PromptInjectionTextCodec {
    fun exportMarkdown(template: PromptTemplate): String =
        PromptImportCodec.exportMarkdownTemplate(template)

    fun exportText(template: PromptTemplate): String =
        PromptImportCodec.exportPlainTextTemplate(template)

    fun importMarkdown(raw: String, fallbackName: String): PromptTemplate? = inspect(
        raw,
        fallbackName.ifBlank { "prompt" } + ".md",
    )

    fun importText(raw: String, fallbackName: String): PromptTemplate? = inspect(
        raw,
        fallbackName.ifBlank { "prompt" } + ".txt",
    )

    private fun inspect(raw: String, sourceName: String): PromptTemplate? =
        when (val result = PromptImportCodec.inspect(raw.toByteArray(Charsets.UTF_8), sourceName)) {
            is PromptImportResult.Success -> result.preview.templates.singleOrNull()
            is PromptImportResult.Failure -> null
        }
}
