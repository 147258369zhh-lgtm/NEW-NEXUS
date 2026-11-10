package io.github.zhh_lgtm.nexus.kernel.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractInvariantsTest {
    @Test
    fun idsRejectUnsafeCharacters() {
        assertThrows(IllegalArgumentException::class.java) { RunId("../run") }
        assertEquals("run-1", RunId("run-1").value)
    }

    @Test
    fun capabilityMustBeAvailableGrantedAndAuthorized() {
        val snapshot = CapabilitySnapshot(
            hostId = HostId("native"),
            capability = "screen.read",
            available = true,
            granted = true,
            authorized = false,
        )
        assertFalse(snapshot.usable)
        assertTrue(snapshot.copy(authorized = true).usable)
    }

    @Test
    fun planRejectsBrokenDependenciesAndDuplicates() {
        val step = PlanStep(
            id = "step-1",
            title = "Inspect",
            description = "Read current state",
            verification = "State was read",
            reversible = true,
        )
        assertThrows(IllegalArgumentException::class.java) {
            PlanDocument(PlanId("plan-1"), null, 1, listOf(step, step.copy(title = "Again")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlanDocument(PlanId("plan-1"), null, 1, listOf(step.copy(dependsOn = setOf("missing"))))
        }
    }

    @Test
    fun migrationManifestRejectsPathTraversalAndDuplicates() {
        val hash = "0".repeat(64)
        assertThrows(IllegalArgumentException::class.java) {
            MigrationArtifactEntry("../eta.db", hash, 1, MigrationCategory.CONVERSATIONS)
        }
        val entry = MigrationArtifactEntry("databases/eta.db", hash, 1, MigrationCategory.CONVERSATIONS)
        assertThrows(IllegalArgumentException::class.java) {
            NexusMigrationManifest(
                sourceVersionName = "4.11.8",
                sourceDatabaseVersions = mapOf("eta.db" to 27),
                createdAt = 1,
                encrypted = true,
                entries = listOf(entry, entry.copy(category = MigrationCategory.MEMORY)),
            )
        }
    }
}
