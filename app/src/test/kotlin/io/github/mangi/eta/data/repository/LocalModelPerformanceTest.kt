package io.github.mangi.eta.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelPerformanceTest {
    private val gib = 1024L * 1024 * 1024

    @Test
    fun findX7UltraUsesConservativeEightKProfile() {
        val profile = LocalModelPerformanceMonitor.recommend(
            snapshot = device(totalRam = 16 * gib, availableRam = 12 * gib),
            record = model(size = 2_588_147_712L, format = LocalModelFormat.LITERT_LM),
        )

        assertEquals(8_192, profile.contextWindow)
        assertEquals(4, profile.threads)
        assertEquals(0, profile.gpuLayers)
        assertEquals(LocalPerformanceState.OPTIMAL, profile.state)
        assertTrue(profile.warnings.isEmpty())
    }

    @Test
    fun ggufStartsWithPortableCpuBaseline() {
        val profile = LocalModelPerformanceMonitor.recommend(
            snapshot = device(totalRam = 16 * gib, availableRam = 12 * gib),
            record = model(size = 500L * 1024 * 1024, format = LocalModelFormat.GGUF),
        )

        assertEquals(0, profile.gpuLayers)
        assertEquals(4, profile.threads)
    }

    @Test
    fun lowerMemoryPhonesReceiveSmallerContextWindows() {
        val eightGb = LocalModelPerformanceMonitor.recommend(
            snapshot = device(totalRam = 8 * gib, availableRam = 6 * gib),
            record = gemma(),
        )
        val sixGb = LocalModelPerformanceMonitor.recommend(
            snapshot = device(totalRam = 6 * gib, availableRam = 4 * gib),
            record = gemma(),
        )
        val fourGb = LocalModelPerformanceMonitor.recommend(
            snapshot = device(totalRam = 4 * gib, availableRam = 2 * gib),
            record = gemma(),
        )

        assertEquals(4_096, eightGb.contextWindow)
        assertEquals(4_096, sixGb.contextWindow)
        assertEquals(2_048, fourGb.contextWindow)
    }

    @Test
    fun severeThermalPressureCapsContextAndMarksRuntimeThrottled() {
        val profile = LocalModelPerformanceMonitor.recommend(
            snapshot = device(totalRam = 16 * gib, availableRam = 12 * gib, thermal = 3),
            record = gemma(),
        )

        assertEquals(2_048, profile.contextWindow)
        assertTrue(LocalPerformanceWarning.THERMAL_PRESSURE in profile.warnings)
        assertEquals(LocalPerformanceState.THROTTLED, profile.state)
    }

    @Test
    fun powerSavingCapsContextWindowAndExplainsWhy() {
        val profile = LocalModelPerformanceMonitor.recommend(
            snapshot = device(totalRam = 16 * gib, availableRam = 12 * gib, powerSaving = true),
            record = gemma(),
        )

        assertEquals(4_096, profile.contextWindow)
        assertTrue(LocalPerformanceWarning.POWER_SAVING in profile.warnings)
        assertEquals(LocalPerformanceState.WATCH, profile.state)
    }

    @Test
    fun lowAvailableRamAndStorageBecomeActionableWarnings() {
        val profile = LocalModelPerformanceMonitor.recommend(
            snapshot = device(
                totalRam = 16 * gib,
                availableRam = gib / 2,
                freeStorage = gib / 4,
            ),
            record = gemma(),
        )

        assertTrue(LocalPerformanceWarning.LOW_AVAILABLE_MEMORY in profile.warnings)
        assertTrue(LocalPerformanceWarning.LOW_STORAGE in profile.warnings)
        assertEquals(LocalPerformanceState.THROTTLED, profile.state)
    }

    @Test
    fun openAiModelListIsParsedAndDeduplicated() {
        val ids = parseOpenAiModelIds(
            """{"object":"list","data":[{"id":"qwen2.5:0.5b"},{"id":"gemma4-e2b"},{"id":"qwen2.5:0.5b"}]}""",
        )

        assertEquals(listOf("qwen2.5:0.5b", "gemma4-e2b"), ids)
        assertTrue(parseOpenAiModelIds("<html>not json</html>").isEmpty())
    }

    private fun gemma() = model(size = 2_588_147_712L, format = LocalModelFormat.LITERT_LM)

    private fun model(size: Long, format: LocalModelFormat) = LocalModelRecord(
        id = "test",
        displayName = if (format == LocalModelFormat.GGUF) "Qwen local GGUF" else "Gemma 4 E2B",
        fileName = if (format == LocalModelFormat.GGUF) "qwen.gguf" else "gemma.litertlm",
        format = format,
        sizeBytes = size,
        sha256 = "0".repeat(64),
    )

    private fun device(
        totalRam: Long,
        availableRam: Long,
        freeStorage: Long = 100 * gib,
        thermal: Int = 0,
        powerSaving: Boolean = false,
    ) = LocalDevicePerformanceSnapshot(
        socManufacturer = "Qualcomm",
        socModel = "SM8650",
        deviceModel = "PHY110",
        androidSdk = 36,
        cpuCores = 8,
        totalRamBytes = totalRam,
        availableRamBytes = availableRam,
        freeStorageBytes = freeStorage,
        thermalStatus = thermal,
        batteryPercent = 80,
        powerSaveMode = powerSaving,
    )
}
