package io.github.mangi.eta.agent.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GitLabSkillRepositoryParserTest {
    @Test fun parsesSlugAndTreeUrlWithNestedGroup() {
        assertEquals(GitLabSkillRepository("team/sub/skills"), GitLabSkillRepositoryParser.parse("team/sub/skills"))
        assertEquals(
            GitLabSkillRepository("team/sub/skills", "main", "skills/pdf"),
            GitLabSkillRepositoryParser.parse("https://gitlab.com/team/sub/skills/-/tree/main/skills/pdf"),
        )
    }

    @Test fun rejectsOtherHostsCredentialsAndConflictingOverrides() {
        listOf(
            "https://example.com/a/b",
            "http://gitlab.com/a/b",
            "https://user:pass@gitlab.com/a/b",
        ).forEach { value -> assertThrows(GitLabSkillSourceException::class.java) { GitLabSkillRepositoryParser.parse(value) } }
        assertThrows(GitLabSkillSourceException::class.java) {
            GitLabSkillRepositoryParser.resolve("https://gitlab.com/a/b/-/tree/main/skills", "dev", null)
        }
    }
}
