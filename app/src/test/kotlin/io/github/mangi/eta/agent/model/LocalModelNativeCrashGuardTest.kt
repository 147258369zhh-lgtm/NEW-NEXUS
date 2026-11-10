package io.github.mangi.eta.agent.model

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelNativeCrashGuardTest {
    @Test
    fun `lite runtime estimate does not count mapped weights several times`() {
        val size = 512L * 1024 * 1024
        val litert = LocalModelNativeCrashGuard.estimateWorkingSetBytes(
            recordSize = size,
            contextWindow = 4_096,
            engine = NativeModelEngine.LITERT_LM,
        )
        // The old 2.2x multiplier plus a 256 MiB fixed allowance produced more than 1.4 GiB for
        // this modest model and blocked valid starts on an otherwise healthy phone.
        assertTrue(litert < size + 600L * 1024 * 1024)
        assertTrue(litert > size)
    }

    @Test
    fun `longer context increases estimated native working set`() {
        val short = LocalModelNativeCrashGuard.estimateWorkingSetBytes(
            recordSize = 343_000_000L,
            contextWindow = 2_048,
            engine = NativeModelEngine.LITERT_LM,
        )
        val long = LocalModelNativeCrashGuard.estimateWorkingSetBytes(
            recordSize = 343_000_000L,
            contextWindow = 8_192,
            engine = NativeModelEngine.LITERT_LM,
        )

        assertTrue(long > short)
    }

    @Test
    fun `gemma four class weights can keep a four thousand token window`() {
        val estimate = LocalModelNativeCrashGuard.estimateWorkingSetBytes(
            recordSize = 3_660_000_000L,
            contextWindow = 4_096,
            engine = NativeModelEngine.LITERT_LM,
        )

        // The OPPO test device exposes roughly 5.2 GiB to the app. The old 2.2x weight
        // multiplier estimated this model above 8 GiB and collapsed the context to 1K.
        assertTrue(estimate < 4_850L * 1024 * 1024)
    }

    @Test
    fun `native attempt identity separates model engine runtime and backend`() {
        val base = NativeAttempt(
            recordId = "record",
            modelSha256 = "sha",
            engine = NativeModelEngine.LITERT_LM,
            backend = "gpu",
            phase = "engine-initialize",
            startedAt = 1L,
        )
        val cpu = base.copy(backend = "cpu-4")
        val gguf = base.copy(engine = NativeModelEngine.LLAMA_CPP)
        val newRuntime = base.copy(runtimeId = "new-runtime")

        assertNotEquals(base.signature(), cpu.signature())
        assertNotEquals(base.signature(), gguf.signature())
        assertNotEquals(base.signature(), newRuntime.signature())
    }
}
