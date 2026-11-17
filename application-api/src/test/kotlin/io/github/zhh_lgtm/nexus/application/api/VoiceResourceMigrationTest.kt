package io.github.zhh_lgtm.nexus.application.api

import io.github.zhh_lgtm.nexus.kernel.api.MigrationArtifactEntry
import io.github.zhh_lgtm.nexus.kernel.api.MigrationCategory
import io.github.zhh_lgtm.nexus.kernel.api.NexusMigrationManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceResourceMigrationTest {
    @Test fun nexusBackupsHaveAnExplicitRestorePurposeAndRejectUnsupportedSources() {
        val manifest = NexusMigrationManifest(sourceApplicationId = io.github.zhh_lgtm.nexus.kernel.api.NexusProduct.APPLICATION_ID,
            sourceVersionName = "0.1.0", sourceDatabaseVersions = mapOf("eta.db" to 28), createdAt = 1, encrypted = true,
            entries = listOf(MigrationArtifactEntry("data/report..final.md", "a".repeat(64), 10, MigrationCategory.WORKSPACE_FILES)))
        assertFalse(NexusMigrationValidator.validate(manifest).valid)
        assertTrue(NexusMigrationValidator.validate(manifest, MigrationPurpose.RESTORE_NEXUS).valid)
        assertFalse(NexusMigrationValidator.validate(manifest.copy(sourceApplicationId = "unrelated.application")).valid)
        val huge = manifest.copy(entries = listOf(MigrationArtifactEntry("one", "a".repeat(64), Long.MAX_VALUE, MigrationCategory.MODELS),
            MigrationArtifactEntry("two", "b".repeat(64), Long.MAX_VALUE, MigrationCategory.MODELS)))
        val rejected = NexusMigrationValidator.validate(huge, MigrationPurpose.RESTORE_NEXUS)
        assertFalse(rejected.valid); assertEquals(Long.MAX_VALUE, rejected.totalBytes)
    }
    @Test fun voiceBargeInReleasesAssistantAudioOwner() {
        var state = VoiceSessionSnapshot()
        state = VoiceStateMachine.transition(state, VoiceTransition.StartListening)
        state = VoiceStateMachine.transition(state, VoiceTransition.SpeechCaptured)
        state = VoiceStateMachine.transition(state, VoiceTransition.ResponseStarted)
        state = VoiceStateMachine.transition(state, VoiceTransition.StartSpeaking)
        state = VoiceStateMachine.transition(state, VoiceTransition.UserInterrupted)
        assertEquals(VoiceState.INTERRUPTED, state.state)
        assertEquals(AudioOwner.USER, state.audioOwner)
    }

    @Test fun lowerPriorityWorkloadsYieldAndStopUnderPressure() {
        assertTrue(ResourcePriorityPolicy.shouldYield(WorkloadType.PLUGIN_WARMUP, WorkloadType.FOREGROUND_CHAT))
        assertFalse(ResourcePriorityPolicy.mayContinue(
            WorkloadType.NIGHTLY_CONSOLIDATION,
            ResourcePressure(lowMemory = true, batterySaver = true),
        ))
    }

    @Test fun credentialsRequireEncryptedMigration() {
        val manifest = NexusMigrationManifest(
            sourceVersionName = "4.11.8",
            sourceDatabaseVersions = mapOf("eta.db" to 27),
            createdAt = 1,
            encrypted = false,
            entries = listOf(MigrationArtifactEntry(
                path = "credentials/provider.json",
                sha256 = "0".repeat(64),
                bytes = 10,
                category = MigrationCategory.CREDENTIALS,
            )),
        )
        val validation = NexusMigrationValidator.validate(manifest)
        assertFalse(validation.valid)
        assertTrue("CREDENTIALS_REQUIRE_ENCRYPTION" in validation.issues)
    }
}
