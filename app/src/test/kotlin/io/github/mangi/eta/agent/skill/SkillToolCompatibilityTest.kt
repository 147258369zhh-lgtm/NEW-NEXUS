package io.github.mangi.eta.agent.skill

import io.github.mangi.eta.agent.runtime.AgentToolPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillToolCompatibilityTest {
    @Test fun standardSpacesCodeBuddyCommasAndYamlSequencesMapWithoutAddingPermissions() {
        listOf("Read Write Bash", "Read, Write, Bash", "[Read, 'Write', Bash]", "  - Read\n  - Write # comment\n  - Bash").forEach { raw ->
            val declaration = SkillDeclarationParser.parse(mapOf("allowed-tools" to raw), emptyMap())
            assertTrue(declaration.compatibilityProblems.toString(), declaration.compatibilityProblems.isEmpty())
            assertEquals(listOf("read_file", "write_file", "terminal", "skills_prepare_runtime"), declaration.allowedTools)
            val policy = requireNotNull(SkillCoordination.toolPolicy(skill(declaration)))
                .intersect(AgentToolPolicy(listOf("read_file", "web_search")))
            assertTrue(policy.isAllowed("read_file"))
            assertFalse(policy.isAllowed("write_file"))
            assertFalse(policy.isAllowed("terminal"))
            assertFalse(policy.isAllowed("web_search"))
            assertFalse(policy.isAllowed("skills_prepare_runtime"))
        }
    }

    @Test fun commandScopedAndMcpDeclarationsRemainUnsupportedInsteadOfBecomingBroadGrants() {
        listOf("Read Bash(git:*)", "Bash(git status:*)", "Bash(git:*) Read", "mcp__server__*", "mcp:server/tool", "[Read, Bash(git:*)]").forEach { raw ->
            val declaration = SkillDeclarationParser.parse(mapOf("allowed-tools" to raw), emptyMap())
            val report = SkillCompatibilityChecker.evaluate(skill(declaration))
            assertFalse(raw, report.available)
            assertTrue(raw, !report.reason.isNullOrBlank())
            assertFalse(raw, "terminal" in declaration.allowedTools)
            assertFalse(raw, "*" in declaration.allowedTools)
        }
    }

    @Test fun emptyMalformedUnknownAndOverlongRestrictionsNeverBecomeUnrestricted() {
        listOf("", "[]", "#", "[Read", "Bash(git:*", "UnknownVendorTool", "Read " + "x".repeat(121)).forEach { raw ->
            val declaration = SkillDeclarationParser.parse(mapOf("allowed-tools" to raw), emptyMap())
            assertFalse(raw, SkillCompatibilityChecker.evaluate(skill(declaration)).available)
        }
        val absent = SkillDeclarationParser.parse(emptyMap(), emptyMap())
        assertTrue(absent.compatibilityProblems.isEmpty())
        assertTrue(absent.allowedTools.isEmpty())
    }

    @Test fun nativePatternsAndAliasesRemainCompatibleAndLiteralHashesAreNotComments() {
        val result = SkillToolCompatibility.parse("read_file, terminal*, WebFetch WebSearch")
        assertEquals(listOf("read_file", "terminal*", "fetch_url", "web_search"), result.tools)
        assertTrue(result.problems.isEmpty())
        assertTrue(SkillToolCompatibility.parse("Read #literal Write").problems.isNotEmpty())
    }

    @Test fun unsupportedForkHooksAndManualOnlySkillsDoNotSilentlyUseTheMainConversation() {
        listOf(
            mapOf("context" to "fork", "agent" to "Explore"),
            mapOf("hooks" to "PreToolUse:\n  - matcher: Bash"),
            mapOf("disable-model-invocation" to "true"),
        ).forEach { frontmatter ->
            val declaration = SkillDeclarationParser.parse(frontmatter, emptyMap())
            assertFalse(SkillCompatibilityChecker.evaluate(skill(declaration)).available)
        }
    }

    @Test fun onlyFileWritingOrTerminalSkillsCanPrepareRuntimeFiles() {
        listOf("Read", "read_file glob_files", "Grep WebSearch").forEach {
            assertFalse("skills_prepare_runtime" in SkillDeclarationParser.parse(mapOf("allowed-tools" to it), emptyMap()).allowedTools)
        }
        listOf("Write", "Bash", "terminal", "write_file").forEach {
            assertTrue("skills_prepare_runtime" in SkillDeclarationParser.parse(mapOf("allowed-tools" to it), emptyMap()).allowedTools)
        }
    }

    private fun skill(declaration: SkillDeclaration) = SkillIndexEntry(
        id = "sample", name = "sample", description = "Portable skill", declaration = declaration,
        rootPath = "/skills/sample", skillFilePath = "/skills/sample/SKILL.md",
        hasScripts = false, hasReferences = false, hasAssets = false, hasEvals = false,
    )
}
