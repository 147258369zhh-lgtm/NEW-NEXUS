package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NpuModelTargetingTest {
    @Test
    fun `normalizes suffix and vendor prefixes without losing the soc family`() {
        assertEquals("sm8650", NpuModelTargeting.baseSoc("SM8650P"))
        assertEquals("sm8650", NpuModelTargeting.baseSoc("SM8650-AB"))
        assertEquals("sm8750", NpuModelTargeting.targetOf("qwen3-0.6B-sm8750-ekv1280.litertlm"))
        assertNull(NpuModelTargeting.targetOf("gemma3-1b-it-int4.litertlm"))
    }

    @Test
    fun `reads the fixed kv allocation embedded in an aot filename`() {
        assertEquals(1280, NpuModelTargeting.kvCapacity("qwen3_sm8650_ekv1280_int8.litertlm"))
        assertEquals(1024, NpuModelTargeting.historyBudget("qwen3-sm8650-aot.litertlm"))
        assertEquals(4096, NpuModelTargeting.historyBudget("gemma-4-sm8650-aot.litertlm"))
    }

    @Test
    fun `only the matching chip or qcs portable target is accepted`() {
        assertTrue(NpuModelTargeting.matches("sm8650", "SM8650"))
        assertTrue(NpuModelTargeting.matches("qcs8650", "SM8650"))
        assertFalse(NpuModelTargeting.matches("sm8750", "SM8650"))
        assertFalse(NpuModelTargeting.matches(null, "SM8650"))
    }

    @Test
    fun `maps current supported qualcomm soc families to hexagon versions`() {
        assertEquals(75, NpuModelTargeting.hexagonArch("QTI", "SM8650"))
        assertEquals(79, NpuModelTargeting.hexagonArch("Qualcomm", "SM8750P"))
        assertEquals(81, NpuModelTargeting.hexagonArch("QTI", "SM8850"))
        assertNull(NpuModelTargeting.hexagonArch("Google", "Tensor G4"))
    }
}
