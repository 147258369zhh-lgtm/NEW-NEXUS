package com.iris.localai.engine.llama

import androidx.annotation.Keep
import java.io.File

/** JNI callback used by the pinned llama.cpp Android runtime. */
@Keep
fun interface PieceSink {
    fun onPiece(utf8: ByteArray): Boolean
}

@Keep
fun interface LoadProgressListener {
    fun onProgress(progress: Float): Boolean
}

/**
 * Frozen JNI surface of the llama.cpp arm64 runtime adapted from IRIDIS 2.4.5 (Apache-2.0).
 * Method names and descriptors must not be renamed by R8.
 */
@Keep
object LlamaNative {
    val unavailableReason: String? by lazy { tryLoad() }

    private fun tryLoad(): String? {
        CpuFeatures.missingForLlama()?.let { return it }
        return try {
            System.loadLibrary("iris_llama")
            null
        } catch (failure: Throwable) {
            "无法加载内置 GGUF 引擎：${failure.message ?: failure.javaClass.simpleName}"
        }
    }

    @JvmStatic external fun nativeGpuTicks(): Int
    @JvmStatic external fun nativeGpuLastLog(): String
    @JvmStatic external fun nativeCreate(): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeLoad(
        handle: Long,
        pathUtf8: ByteArray,
        nCtx: Int,
        nThreads: Int,
        nBatch: Int,
        flashAttn: Boolean,
        nGpuLayers: Int,
        kvType: Int,
        useMmap: Boolean,
        listener: LoadProgressListener?,
    ): String?
    @JvmStatic external fun nativeUnload(handle: Long)
    @JvmStatic external fun nativeCancel(handle: Long)
    @JvmStatic external fun nativeClearCancel(handle: Long)
    @JvmStatic external fun nativeReset(handle: Long)
    @JvmStatic external fun nativeSetThreads(handle: Long, threads: Int)
    @JvmStatic external fun nativeMeta(handle: Long): String
    @JvmStatic external fun nativeGenerate(
        handle: Long,
        roles: Array<ByteArray>,
        contents: Array<ByteArray>,
        floats: FloatArray,
        ints: IntArray,
        sink: PieceSink?,
    ): String
}

private object CpuFeatures {
    private val features: Set<String> by lazy {
        runCatching {
            File("/proc/cpuinfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("Features") }
                    ?.substringAfter(':')
                    ?.trim()
                    ?.split(' ', '\t')
                    ?.filter(String::isNotEmpty)
                    ?.toSet()
            } ?: emptySet()
        }.getOrDefault(emptySet())
    }

    fun missingForLlama(): String? {
        if (features.isEmpty()) return null
        val required = mapOf("asimddp" to "dotprod", "i8mm" to "i8mm", "asimdhp" to "fp16")
        val missing = required.filterKeys { it !in features }.values
        return if (missing.isEmpty()) null else {
            "此设备 CPU 缺少 ${missing.joinToString("、")} 指令集，无法运行内置 GGUF 引擎；" +
                "当前原生库面向骁龙 8 Gen 1 / 天玑 9000 及以上的 ARM64 设备"
        }
    }
}
