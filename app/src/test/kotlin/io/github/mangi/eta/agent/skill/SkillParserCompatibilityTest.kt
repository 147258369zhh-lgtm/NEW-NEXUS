package io.github.mangi.eta.agent.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillParserCompatibilityTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun yamlCommentsQuotedEscapesAndFlowMetadataKeepTheirValues() {
        val parsed = SkillParser.parseSimpleFrontmatter("""
            name: 'portable' # comment
            description: "Quoted \"name\", #literal and \u4E2D\nsecond line" # comment
            license: 'Author''s license'
            metadata: {author: 'A, B', version: "1.0", url: https://example.com/#anchor}
        """.trimIndent())
        assertEquals("portable", parsed["name"])
        assertEquals("Quoted \"name\", #literal and 中\nsecond line", parsed["description"])
        assertEquals("Author's license", parsed["license"])
        assertEquals(mapOf("author" to "A, B", "version" to "1.0", "url" to "https://example.com/#anchor"),
            SkillParser.parseIndentedBlock(requireNotNull(parsed["metadata"])))
        assertFalse(parsed.containsKey(SkillParser.PROBLEMS_KEY))
    }

    @Test fun foldedAndLiteralYamlUseDetectedIndentAndPreserveNestedContent() {
        val parsed = SkillParser.parseSimpleFrontmatter("name: sample\ndescription: >- # folded\n first line\n second line\nlicense: |2-\n  text\n    nested\nmetadata:\n  author: 'A # B' # note\n")
        assertEquals("first line second line", parsed["description"])
        assertEquals("text\n  nested", parsed["license"])
        assertEquals("A # B", SkillParser.parseIndentedBlock(requireNotNull(parsed["metadata"]))["author"])
    }

    @Test fun bomAndExactClosingMarkerUseTheSameBodyBoundary() {
        val file = temporaryFolder.newFile("SKILL.md")
        file.writeText("\uFEFF---\nname: sample\ndescription: |-\n  first\n  ---still description\n---\nBody\n---body stays")
        val parsed = requireNotNull(SkillParser.parseSkillFile(file))
        assertEquals("first\n---still description", parsed.frontmatter["description"])
        assertEquals("Body\n---body stays", parsed.body)
    }

    @Test fun duplicateOrUnsupportedYamlIsDiagnosedInsteadOfSilentlyDroppingIt() {
        val duplicate = SkillParser.parseSimpleFrontmatter("name: sample\nallowed-tools: Read\nallowed-tools: Bash")
        assertTrue(requireNotNull(duplicate[SkillParser.PROBLEMS_KEY]).contains("重复"))
        val unsupported = SkillParser.parseSimpleFrontmatter("name: sample\n<<: *defaults")
        assertTrue(unsupported.containsKey(SkillParser.PROBLEMS_KEY))
    }
}
