package io.github.mangi.eta.fork.a2a

import android.app.Application
import android.content.Context
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class A2AInboundCrossProcessConfigTest {
    private lateinit var context: Application
    private lateinit var key: SecretKeySpec

    @Before
    fun resetStores() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("eta_a2a_inbound", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("eta_a2a_secrets", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("eta_a2a_access_policy", Context.MODE_PRIVATE)
            .edit().clear().commit()
        key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    }

    @Test
    fun settingsAndRotatedCredentialAreVisibleBeforeTheServiceProcessReads() {
        val settingsProcess = configStore()
        val serviceProcess = configStore()
        val initialToken = serviceProcess.snapshot().token

        settingsProcess.setEnabled(true)
        settingsProcess.setTransportMode(A2ATransportMode.TAILSCALE)
        val rotated = settingsProcess.rotateToken()

        val observed = serviceProcess.snapshot()
        assertTrue(observed.enabled)
        assertEquals(A2ATransportMode.TAILSCALE, observed.transportMode)
        assertEquals(rotated, observed.token)
        assertFalse(rotated == initialToken)
    }

    @Test
    fun authorizationTierIsCommittedBeforeTheNextInboundRequestReadsIt() {
        val settingsProcess = A2AAccessPolicyStore(context)
        val serviceProcess = A2AAccessPolicyStore(context)

        settingsProcess.setTier("peer-a", A2AToolTier.RESTRICTED)

        assertEquals(A2AToolTier.RESTRICTED, serviceProcess.tier("peer-a"))
        assertTrue(serviceProcess.accessFor("peer-a").allowedTools.contains("write_file"))
    }

    @Test
    fun currentCredentialCanBeConfiguredWithoutInheritingNamedOrRotatedGrants() {
        val settingsProcess = A2AAccessPolicyStore(context)
        val serviceProcess = A2AAccessPolicyStore(context)
        val currentCredentialId = A2AAccessPolicyStore.tokenPeerId("fixture-current-credential")
        val rotatedCredentialId = A2AAccessPolicyStore.tokenPeerId("fixture-rotated-credential")
        settingsProcess.setTier("old-self-reported-peer", A2AToolTier.RESTRICTED)
        settingsProcess.observePeer(currentCredentialId)
        assertEquals(A2AToolTier.READ_ONLY, serviceProcess.tier(currentCredentialId))

        // These are the exact IDs used by both the settings controls and the inbound engine.
        settingsProcess.setTier(currentCredentialId, A2AToolTier.RESTRICTED)
        assertTrue(serviceProcess.accessFor(currentCredentialId).allowedTools.contains("write_file"))
        settingsProcess.observePeer(rotatedCredentialId)
        assertEquals(A2AToolTier.READ_ONLY, serviceProcess.tier(rotatedCredentialId))
        assertEquals(A2AToolTier.RESTRICTED, serviceProcess.tier("old-self-reported-peer"))
        assertFalse(currentCredentialId.contains("fixture-current-credential"))
    }

    private fun configStore() = A2AInboundConfigStore(
        context,
        A2ASecretStore(context) { key },
    )
}
