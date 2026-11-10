package io.github.mangi.eta.agent.capability

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillCatalogParserTest {
    private val source = SkillSourceDefinition(
        id = "community",
        name = "社区目录",
        type = SkillSourceType.HTTP_CATALOG,
        location = "https://skills.example.com/catalog.json",
        trust = SkillSourceTrust.COMMUNITY,
    )

    @Test
    fun parsesBoundedCatalogAndSecurityMetadata() {
        val checksum = "a".repeat(64)
        val root = JSONObject()
            .put("schema", "eta-skill-catalog/v1")
            .put(
                "skills",
                JSONArray().put(
                    JSONObject()
                        .put("id", "pdf-tools")
                        .put("name", "PDF 工具")
                        .put("description", "读取、生成和校验 PDF")
                        .put("version", "1.2.0")
                        .put("publisher", "Example")
                        .put("archiveUrl", "https://skills.example.com/pdf-tools.zip")
                        .put("archiveSha256", checksum)
                        .put("tags", JSONArray().put("pdf").put("document"))
                        .put("dependencies", JSONArray().put("Python 3"))
                        .put("permissions", JSONArray().put("文件读取")),
                ),
            )

        val item = SkillCatalogParser.parse(root, source).single()

        assertEquals("pdf-tools", item.skillId)
        assertEquals("PDF 工具", item.displayName)
        assertEquals("1.2.0", item.version)
        assertEquals(checksum, item.archiveSha256)
        assertTrue(item.integrityVerified)
        assertEquals(listOf("Python 3"), item.dependencies)
        assertEquals(listOf("文件读取"), item.permissions)
    }

    @Test
    fun allowsUnverifiedCommunityPackageButMarksIt() {
        val root = JSONObject()
            .put("schema", "eta-skill-catalog/v1")
            .put(
                "skills",
                JSONArray().put(
                    JSONObject()
                        .put("id", "notes-cleaner")
                        .put("name", "笔记清理")
                        .put("archiveUrl", "https://skills.example.com/notes.zip"),
                ),
            )

        val item = SkillCatalogParser.parse(root, source).single()

        assertNull(item.archiveSha256)
        assertFalse(item.integrityVerified)
    }

    @Test
    fun rejectsUnsupportedSchemaInvalidChecksumAndDuplicateIds() {
        assertThrows(SkillCatalogException::class.java) {
            SkillCatalogParser.parse(
                JSONObject().put("schema", "other/v1").put("skills", JSONArray()),
                source,
            )
        }
        val checksumEntry = JSONObject()
            .put("id", "alpha")
            .put("name", "Alpha")
            .put("archiveUrl", "https://skills.example.com/alpha.zip")
            .put("archiveSha256", "not-a-checksum")
        assertThrows(SkillCatalogException::class.java) {
            SkillCatalogParser.parse(
                JSONObject()
                    .put("schema", "eta-skill-catalog/v1")
                    .put("skills", JSONArray().put(checksumEntry)),
                source,
            )
        }
        val clean = JSONObject()
            .put("id", "alpha")
            .put("name", "Alpha")
            .put("archiveUrl", "https://skills.example.com/alpha.zip")
        assertThrows(SkillCatalogException::class.java) {
            SkillCatalogParser.parse(
                JSONObject()
                    .put("schema", "eta-skill-catalog/v1")
                    .put("skills", JSONArray().put(clean).put(clean)),
                source,
            )
        }
    }
}
