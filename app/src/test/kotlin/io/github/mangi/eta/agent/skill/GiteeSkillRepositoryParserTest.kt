package io.github.mangi.eta.agent.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GiteeSkillRepositoryParserTest {
    @Test fun parsesSlugAndTreeUrl() {
        assertEquals(GiteeSkillRepository("owner", "skills"), GiteeSkillRepositoryParser.parse("owner/skills"))
        assertEquals(
            GiteeSkillRepository("owner", "skills", "main", "skills/pdf"),
            GiteeSkillRepositoryParser.parse("https://gitee.com/owner/skills/tree/main/skills/pdf"),
        )
    }

    @Test fun rejectsOtherHostsCredentialsAndRefConflicts() {
        listOf("https://example.com/a/b", "http://gitee.com/a/b", "https://u:p@gitee.com/a/b").forEach {
            assertThrows(GiteeSkillSourceException::class.java) { GiteeSkillRepositoryParser.parse(it) }
        }
        assertThrows(GiteeSkillSourceException::class.java) {
            GiteeSkillRepositoryParser.resolve("https://gitee.com/a/b/tree/main/skills", "dev", null)
        }
    }

    @Test fun blobUrlKeepsTheSelectedRefAndSkillPathAndUnknownRoutesFailClosed() {
        assertEquals(
            GiteeSkillRepository("owner", "skills", "dev", "skills/pdf"),
            GiteeSkillRepositoryParser.parse("https://gitee.com/owner/skills/blob/dev/skills/pdf/SKILL.md"),
        )
        listOf("https://gitee.com/owner/skills/issues/123", "https://gitee.com/owner/skills/blob").forEach {
            assertThrows(GiteeSkillSourceException::class.java) { GiteeSkillRepositoryParser.parse(it) }
        }
    }
}
