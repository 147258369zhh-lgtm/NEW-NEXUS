package io.github.mangi.eta.agent.connector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DshPluginManifestValidatorTest {
    @Test fun acceptsProtocolV1Contributions() {
        val result = DshPluginManifestValidator.validate(
            """{"dshGo":{"v":1,"minApp":"1.2.0","title":{"zh":"示例"},"assets":"assets","capabilities":["files"],"contributes":{"composer.actions":[{"id":"open","label":"打开","requires":["files"]}],"panels":[{"id":"main","title":"面板","view":"view.json"}]}}}""",
        )
        assertTrue(result is DshManifestValidation.Valid)
        result as DshManifestValidation.Valid
        assertEquals(setOf("open", "main"), result.manifest.contributionIds)
        assertEquals(2, result.manifest.contributions.values.sum())
    }

    @Test fun rejectsUnknownProtocolAndUnsafeAssets() {
        val wrongVersion = DshPluginManifestValidator.validate("""{"dshGo":{"v":2,"contributes":{"panels":[{"id":"a","title":"A"}]}}}""")
        assertTrue(wrongVersion is DshManifestValidation.Invalid)
        val unsafeAssets = DshPluginManifestValidator.validate(
            """{"dshGo":{"v":1,"assets":"../outside","contributes":{"panels":[{"id":"a","title":"A"}]}}}""",
        )
        assertTrue(unsafeAssets is DshManifestValidation.Invalid)
    }

    @Test fun ignoresUnknownFutureSurfaces() {
        val result = DshPluginManifestValidator.validate(
            """{"dshGo":{"v":1,"contributes":{"future.surface":[{"id":"future"}]}}}""",
        )
        assertTrue(result is DshManifestValidation.Valid)
    }
}
