package io.github.mangi.eta.agent.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VoiceprintProfileStoreTest {
    @Test
    fun `enrollment stores features and verifies compatible captures`() {
        val store = VoiceprintProfileStore(RuntimeEnvironment.getApplication())
        store.clear()
        val features = byteArrayOf(1, 2, 3, 4)
        assertEquals(VoiceprintVerification.NO_PROFILE, store.verify(features, 0.72f))
        val profile = store.enroll("owner", features)
        assertNotNull(profile)
        assertEquals(VoiceprintVerification.VERIFIED, store.verify(features, 0.72f))
        assertEquals(VoiceprintVerification.REJECTED, store.verify(byteArrayOf(4, 3, 2, 1), 0.72f))
        store.clear()
        assertNull(store.profile())
    }

    @Test
    fun `having a profile does not verify missing capture features`() {
        val store = VoiceprintProfileStore(RuntimeEnvironment.getApplication())
        store.clear()
        store.enroll("owner", byteArrayOf(1, 2, 3))
        assertEquals(VoiceprintVerification.UNAVAILABLE, store.verify(null, 0.72f))
        assertEquals(VoiceprintVerification.UNAVAILABLE, store.verify(byteArrayOf(1, 2), 0.72f))
        assertEquals(VoiceprintVerification.UNAVAILABLE, store.verify(ByteArray(3), 0.72f))
        for (threshold in listOf(0f, -1f, 2f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(VoiceprintVerification.UNAVAILABLE, store.verify(byteArrayOf(1, 2, 3), threshold))
        }
    }

    @Test
    fun `invalid enrollment does not create a profile`() {
        val store = VoiceprintProfileStore(RuntimeEnvironment.getApplication())
        store.clear()
        assertNull(store.enroll("", byteArrayOf(1)))
        assertNull(store.enroll("owner", ByteArray(0)))
        assertNull(store.enroll("owner", ByteArray(3)))
        assertEquals(VoiceprintVerification.NO_PROFILE, store.verify(byteArrayOf(1), 0.72f))
    }
}
