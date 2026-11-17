package io.github.mangi.eta.agent.capability

import org.junit.Assert.assertEquals
import org.junit.Test

class SkillArchiveSourcesTest {
    @Test fun archiveMirrorsUseHttpsWithoutCredentialsAndKeepPriority() {
        assertEquals(
            listOf("https://primary.example/a.zip", "https://mirror.example/a.zip"),
            SkillArchiveSources.candidates(
                "https://primary.example/a.zip",
                listOf(
                    "https://mirror.example/a.zip",
                    "https://mirror.example/a.zip",
                    "http://insecure.example/a.zip",
                    "https://user:secret@mirror.example/private.zip",
                    "https://mirror.example:8443/a.zip",
                    "https://mirror.example/a.zip#fragment",
                ),
            ),
        )
    }
}
