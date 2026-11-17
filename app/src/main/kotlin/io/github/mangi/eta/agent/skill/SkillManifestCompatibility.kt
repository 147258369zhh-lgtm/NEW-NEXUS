package io.github.mangi.eta.agent.skill

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import kotlin.text.Charsets

/** Converts common instruction-file conventions into the same inert SKILL.md package.
 *
 * Imported text is never executed. The original manifest body remains Markdown and is stored in
 * the private staging directory first, so vendor compatibility does not bypass archive, quota,
 * conflict, permission, or installation checks.
 */
internal object SkillManifestCompatibility {
    private const val STANDARD_FILE = "SKILL.md"
    private const val MAX_VENDOR_MANIFESTS = 64
    private const val MAX_DESCRIPTION = 1_024
    private val IMPORT_ROOT_NAME = ".nexus-imported"
    private val idPattern = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")

    fun discover(scanRoot: File): List<File> {
        val standard = scanRoot.walkTopDown()
            .onEnter { !Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && it.name == STANDARD_FILE }
            .toList()
        if (standard.isNotEmpty()) return standard

        val manifests = scanRoot.walkTopDown()
            .onEnter { !Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && isVendorManifest(scanRoot, it) }
            .take(MAX_VENDOR_MANIFESTS + 1)
            .toList()
        require(manifests.size <= MAX_VENDOR_MANIFESTS) { "VENDOR_MANIFEST_LIMIT" }
        val importRoot = File(scanRoot, IMPORT_ROOT_NAME)
        val used = linkedSetOf<String>()
        return manifests.map { manifest ->
            val parsed = SkillParser.parseSkillFile(manifest)
                ?: throw IllegalArgumentException("VENDOR_MANIFEST_UNREADABLE")
            val relative = manifest.relativeTo(scanRoot).invariantSeparatorsPath
            val heading = parsed.body.lineSequence()
                .map(String::trim)
                .firstOrNull { it.startsWith("# ") }
                ?.removePrefix("# ")
                ?.trim()
            val rawName = parsed.frontmatter["name"]?.takeIf(String::isNotBlank)
                ?: heading?.takeIf(String::isNotBlank)
                ?: manifest.nameWithoutExtension
            val base = SkillParser.sanitizeSkillId(manifest.nameWithoutExtension, rawName)
                .takeIf(idPattern::matches)
                ?: "imported-${shortHash(relative)}"
            val id = uniqueId(base, relative, used)
            val description = parsed.frontmatter["description"]
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: firstParagraph(parsed.body)
                ?: "Imported from ${manifestFormat(relative)}"
            val directory = File(importRoot, id)
            require(directory.mkdirs() || directory.isDirectory) { "VENDOR_IMPORT_STORAGE_UNAVAILABLE" }
            val normalized = buildString {
                appendLine("---")
                appendLine("name: $id")
                appendLine("description: ${description.replace('\n', ' ').take(MAX_DESCRIPTION)}")
                appendLine("nexus_import_format: ${manifestFormat(relative)}")
                appendLine("nexus_source_manifest: ${manifest.name}")
                appendLine("---")
                appendLine()
                append(parsed.body.trim())
            }
            File(directory, STANDARD_FILE).apply {
                writeText(normalized, Charsets.UTF_8)
            }
        }
    }

    private fun isVendorManifest(root: File, file: File): Boolean {
        val relative = file.relativeTo(root).invariantSeparatorsPath.lowercase()
        val name = file.name.lowercase()
        if (name in setOf(
                "agents.md", "claude.md", "gemini.md", "codebuddy.md", "qwen.md",
                "copilot-instructions.md", ".windsurfrules", ".clinerules",
            )) return true
        return (relative.startsWith(".cursor/rules/") && name.endsWith(".mdc")) ||
            (relative.startsWith(".roo/rules/") && name.endsWith(".md")) ||
            (relative.startsWith(".continue/rules/") && name.endsWith(".md")) ||
            (relative.startsWith(".clinerules/") && name.endsWith(".md"))
    }

    private fun manifestFormat(relative: String): String = when {
        relative.startsWith(".cursor/") || relative.contains("/.cursor/") -> "cursor-rule"
        relative.startsWith(".roo/") || relative.contains("/.roo/") -> "roo-rule"
        relative.startsWith(".continue/") || relative.contains("/.continue/") -> "continue-rule"
        relative.startsWith(".clinerules/") || relative.contains("/.clinerules/") -> "cline-rule"
        relative.endsWith(".windsurfrules", ignoreCase = true) -> "windsurf-rules"
        relative.endsWith(".clinerules", ignoreCase = true) -> "cline-rules"
        else -> relative.substringAfterLast('/').substringBeforeLast('.').lowercase() + "-instructions"
    }

    private fun uniqueId(base: String, relative: String, used: MutableSet<String>): String {
        if (used.add(base)) return base.take(64)
        val suffixed = "${base.take(55)}-${shortHash(relative)}"
        used += suffixed
        return suffixed
    }

    private fun firstParagraph(body: String): String? = body.lineSequence()
        .map(String::trim)
        .map { it.removePrefix("# ").removePrefix("## ").removePrefix("> ").removePrefix("- ").removePrefix("* ") }
        .firstOrNull { it.isNotBlank() && !it.startsWith("```") }
        ?.replace(Regex("\\s+"), " ")
        ?.take(MAX_DESCRIPTION)

    private fun shortHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(5)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
