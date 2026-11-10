package io.github.mangi.eta.data.repository

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.google.ai.edge.litertlm.ModelInfo
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderEndpoint
import io.github.mangi.eta.agent.model.mnn.MnnModelPackage
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.BufferedOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Local model artifacts are kept as user-managed files. A downloaded artifact is not treated as
 * an API endpoint: binding it to a provider still requires an OpenAI-compatible local runtime.
 */
@Serializable
internal data class LocalModelRecord(
    val id: String,
    val displayName: String,
    val fileName: String,
    val format: LocalModelFormat,
    val sizeBytes: Long,
    val sha256: String,
    val sourceUrl: String? = null,
    val license: String? = null,
    val installedAt: Long = System.currentTimeMillis(),
    val lastVerifiedAt: Long? = null,
    val runtime: LocalModelRuntime = LocalModelRuntime.EXTERNAL_OPENAI_COMPATIBLE,
    val serviceEndpoint: String = DEFAULT_LOCAL_SERVICE_ENDPOINT,
    val serverModelId: String = displayName,
    // Safe fallback for a new locally managed file. The binding screen replaces this with a
    // device-aware value before the model is exposed to chat/meeting context budgeting.
    val contextWindow: Int = 8_192,
    val inputModalities: List<String> = listOf(Model.TEXT_MODALITY),
    val outputModalities: List<String> = listOf(Model.TEXT_MODALITY),
    val supportsTools: Boolean = false,
    val supportsReasoning: Boolean = false,
    val boundProviderId: String? = null,
    val boundModelSelectionId: String? = null,
    val runtimeSettings: LocalModelRuntimeSettings = LocalModelRuntimeSettings(),
    val lastBenchmark: LocalModelPerformanceResult? = null,
) {
    val supportsVision: Boolean
        get() = Model.IMAGE_MODALITY in inputModalities || Model.IMAGE_MODALITY in outputModalities

    companion object {
        const val DEFAULT_LOCAL_SERVICE_ENDPOINT = "http://127.0.0.1:11434/v1"
    }
}

private data class NativeLiteRtModelInfo(
    val maxContextTokens: Int?,
    val supportsVision: Boolean,
    val supportsThinking: Boolean,
)

@Serializable
internal enum class LocalModelFormat(val extensions: Set<String>) {
    LITERT_LM(setOf("litertlm")),
    GGUF(setOf("gguf")),
    MNN(emptySet()),
    TASK(setOf("task")),
    ONNX(setOf("onnx")),
    TFLITE(setOf("tflite", "litert")),
    SAFETENSORS(setOf("safetensors")),
    OTHER(setOf("bin", "pt", "pth"));

    companion object {
        fun fromFileName(name: String): LocalModelFormat? {
            val extension = name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { extension in it.extensions }
        }

        val supportedExtensions: Set<String> = entries.flatMapTo(mutableSetOf()) { it.extensions }
    }
}

@Serializable
internal enum class LocalModelRuntime {
    EXTERNAL_OPENAI_COMPATIBLE,
    BUILT_IN_PENDING,
    BUILT_IN_LITERT_LM,
    BUILT_IN_LLAMA_CPP,
    BUILT_IN_MNN,
}

@Serializable
internal data class LocalModelCatalog(
    val version: Int = 1,
    val models: List<LocalModelRecord> = emptyList(),
)

/** 续传元数据：确认 .part 属于哪个地址，并支持 If-Range 防止远端文件变更后拼接出错。 */
@Serializable
internal data class LocalModelPartMeta(
    val id: String,
    val url: String,
    val etag: String? = null,
    val lastModified: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

internal data class LocalModelPreset(
    val id: String,
    val url: String,
    val fallbackUrls: List<String> = emptyList(),
    val expectedSizeBytes: Long?,
    val expectedSha256: String?,
    val license: String,
    val inputModalities: List<String>,
    val outputModalities: List<String>,
    val displayName: String = "",
) {
    val downloadUrls: List<String>
        get() = (listOf(url) + fallbackUrls).distinct()

    companion object {
        val Qwen3DynamicInt4 = LocalModelPreset(
            id = "qwen3-0.6b-litert-lm",
            url = "https://modelscope.cn/models/litert-community/Qwen3-0.6B/resolve/master/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
            fallbackUrls = listOf(
                "https://hf-mirror.com/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
            ),
            expectedSizeBytes = 343_490_560L,
            expectedSha256 = "bf1aba9ce8d963124407353c6aa4cbf72eba78db21ae133de983eac672ce83e0",
            license = "Apache-2.0",
            inputModalities = listOf(Model.TEXT_MODALITY),
            outputModalities = listOf(Model.TEXT_MODALITY),
        )

        val Gemma3OneBInt4 = LocalModelPreset(
            id = "gemma3-1b-litert-lm",
            url = "https://modelscope.cn/models/litert-community/Gemma3-1B-IT/resolve/master/gemma3-1b-it-int4.litertlm",
            expectedSizeBytes = 584_417_280L,
            expectedSha256 = "1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be",
            license = "Gemma Terms of Use",
            inputModalities = listOf(Model.TEXT_MODALITY),
            outputModalities = listOf(Model.TEXT_MODALITY),
        )

        val Gemma4E2B = LocalModelPreset(
            id = "gemma-4-e2b-litert-lm",
            // ModelScope mirrors the same artifact and is reachable from the default network in China.
            url = "https://modelscope.cn/models/litert-community/gemma-4-E2B-it-litert-lm/resolve/master/gemma-4-E2B-it.litertlm",
            fallbackUrls = listOf(
                "https://hf-mirror.com/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            ),
            expectedSizeBytes = 2_588_147_712L,
            expectedSha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
            license = "Apache-2.0",
            inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY),
            outputModalities = listOf(Model.TEXT_MODALITY),
            displayName = "Gemma 4 E2B",
        )

        val Gemma4E4B = LocalModelPreset(
            id = "gemma-4-e4b-litert-lm",
            url = "https://modelscope.cn/models/litert-community/gemma-4-E4B-it-litert-lm/resolve/master/gemma-4-E4B-it.litertlm",
            fallbackUrls = listOf(
                "https://hf-mirror.com/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            ),
            expectedSizeBytes = 3_659_530_240L,
            expectedSha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
            license = "Gemma Terms of Use",
            inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY),
            outputModalities = listOf(Model.TEXT_MODALITY),
            displayName = "Gemma 4 E4B",
        )

        val Gemma3nE2B = LocalModelPreset(
            id = "gemma-3n-e2b-litert-lm",
            url = "https://modelscope.cn/models/google/gemma-3n-E2B-it-litert-lm/resolve/master/gemma-3n-E2B-it-int4.litertlm",
            expectedSizeBytes = 3_655_827_456L,
            expectedSha256 = "2ed7bc3a0026c93d5b8a4544b352d9d00cd66ff0bac3ef6a20ac3d2cba4010d6",
            license = "Gemma Terms of Use",
            inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY),
            outputModalities = listOf(Model.TEXT_MODALITY),
            displayName = "Gemma 3n E2B",
        )

        val Kokoro82M = LocalModelPreset(
            id = "kokoro-82m-tts-litert-lm",
            url = "https://modelscope.cn/models/litert-community/Kokoro-82M/resolve/master/kokoro_int8_gpu_fp16.litertlm",
            fallbackUrls = listOf(
                "https://hf-mirror.com/litert-community/Kokoro-82M/resolve/main/kokoro_int8_gpu_fp16.litertlm",
            ),
            expectedSizeBytes = 294_750_530L,
            expectedSha256 = "1ef178b127ec48c737daf17eabb4cea5f6b3ffbc510aced270fe5c267bbeac52",
            license = "Apache-2.0",
            inputModalities = listOf(Model.TEXT_MODALITY),
            outputModalities = listOf("audio"),
            displayName = "Kokoro 82M",
        )

        val KokoroCpu = LocalModelPreset(
            id = "kokoro-82m-cpu-tts-litert-lm",
            url = "https://modelscope.cn/models/litert-community/Kokoro-82M/resolve/master/kokoro_int8_fp16.litertlm",
            fallbackUrls = listOf(
                "https://hf-mirror.com/litert-community/Kokoro-82M/resolve/main/kokoro_int8_fp16.litertlm",
            ),
            expectedSizeBytes = 294_783_298L,
            expectedSha256 = "e7b931ee5d4b7104012a838c9eee240d19ea69cb3ca269041a3274f83dbb7fa1",
            license = "Apache-2.0",
            inputModalities = listOf(Model.TEXT_MODALITY),
            outputModalities = listOf("audio"),
            displayName = "Kokoro 82M · CPU",
        )

        val PocketTts100M = LocalModelPreset(
            id = "pocket-tts-100m-litert-lm",
            url = "https://modelscope.cn/models/litert-community/pocket-tts/resolve/master/pocket_tts_int8.litertlm",
            fallbackUrls = listOf(
                "https://hf-mirror.com/litert-community/pocket-tts/resolve/main/pocket_tts_int8.litertlm",
            ),
            expectedSizeBytes = 760_061_960L,
            expectedSha256 = "9c5427cf3fe2d6680f8268e1c06537496be52d818ef12233a461bf193f314b3c",
            license = "Apache-2.0",
            inputModalities = listOf(Model.TEXT_MODALITY),
            outputModalities = listOf("audio"),
            displayName = "PocketTTS 100M",
        )

        val Phonon31M = LocalModelPreset(
            id = "phonon-31m-tts-litert-lm",
            url = "https://modelscope.cn/models/litert-community/phonon-31m-en/resolve/master/phonon_int8.litertlm",
            fallbackUrls = listOf(
                "https://hf-mirror.com/litert-community/phonon-31m-en/resolve/main/phonon_int8.litertlm",
            ),
            expectedSizeBytes = 87_205_888L,
            expectedSha256 = "b33a9a0d65acec36762db38a2bff6d899f4aab34b196bb56d89fab5665eabebe",
            license = "Apache-2.0",
            inputModalities = listOf(Model.TEXT_MODALITY),
            outputModalities = listOf("audio"),
            displayName = "Phonon 31M",
        )

        val ParakeetTdt06B = LocalModelPreset(
            id = "parakeet-tdt-0.6b-asr-litert-lm",
            url = "https://modelscope.cn/models/litert-community/parakeet-tdt-0.6b-v3/resolve/master/parakeet_tdt_0.6b_v3_5s_i8_stateful.litertlm",
            fallbackUrls = listOf(
                "https://hf-mirror.com/litert-community/parakeet-tdt-0.6b-v3/resolve/main/parakeet_tdt_0.6b_v3_5s_i8_stateful.litertlm",
            ),
            expectedSizeBytes = 614_588_752L,
            expectedSha256 = "66c5a74cf6c2c1d2b990901f6cece5bb53e3214dbceafa0989900a6f0eeb3504",
            license = "NVIDIA Open Model License",
            inputModalities = listOf("audio"),
            outputModalities = listOf(Model.TEXT_MODALITY),
            displayName = "Parakeet TDT 0.6B",
        )

        val Qwen3Asr06B = LocalModelPreset(
            id = "qwen3-asr-0.6b-litert-lm",
            url = "https://modelscope.cn/models/litert-community/Qwen3-ASR-0.6B/resolve/master/qwen3_asr_0.6b_5s_i8.litertlm",
            fallbackUrls = listOf(
                "https://hf-mirror.com/litert-community/Qwen3-ASR-0.6B/resolve/main/qwen3_asr_0.6b_5s_i8.litertlm",
            ),
            expectedSizeBytes = 959_627_232L,
            expectedSha256 = "d4444d51f0c08142f57e16097673150f3ea02900a00b29a58a41f6e7578db251",
            license = "Apache-2.0",
            inputModalities = listOf("audio"),
            outputModalities = listOf(Model.TEXT_MODALITY),
            displayName = "Qwen3 ASR 0.6B · 中文",
        )

        val MoonshineTiny = LocalModelPreset(
            id = "moonshine-tiny-asr-litert-lm",
            url = "https://modelscope.cn/models/litert-community/moonshine-tiny/resolve/master/moonshine_tiny_5s_i8.litertlm",
            fallbackUrls = listOf(
                "https://hf-mirror.com/litert-community/moonshine-tiny/resolve/main/moonshine_tiny_5s_i8.litertlm",
            ),
            expectedSizeBytes = 29_676_624L,
            expectedSha256 = "9876ebf427f9b46cf986d0e5f2cef9647249c54453082722b59f70411d15c4d1",
            license = "MIT",
            inputModalities = listOf("audio"),
            outputModalities = listOf(Model.TEXT_MODALITY),
            displayName = "Moonshine Tiny · 英文",
        )

        val EmbeddingGemma2 = LocalModelPreset(
            id = "embeddinggemma-2-litert-lm",
            url = "https://modelscope.cn/models/litert-community/embeddinggemma-2-740m-litert-lm/resolve/master/embeddinggemma-2-740m.litertlm",
            fallbackUrls = listOf(
                "https://hf-mirror.com/litert-community/embeddinggemma-2-740m-litert-lm/resolve/main/embeddinggemma-2-740m.litertlm",
            ),
            expectedSizeBytes = 484_622_336L,
            expectedSha256 = "e7a8a2204b91e0f96e92960e84a09a89212e1633dcb7575a9bf3378b4df77f4c",
            license = "Gemma Terms of Use",
            inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY),
            outputModalities = listOf("embedding"),
            displayName = "EmbeddingGemma 2",
        )

        val Qwen3NpuSm8650 = LocalModelPreset(
            id = "qwen3-0.6b-sm8650-npu-litert-lm",
            url = "https://hf-mirror.com/macunaima/Qwen3-0.6B-SM8650-LiteRT-LM-AOT-Dynamic-INT8-Full/resolve/main/qwen3-0.6B-sm8650-aot-dynamic-int8.litertlm",
            fallbackUrls = listOf(
                "https://huggingface.co/macunaima/Qwen3-0.6B-SM8650-LiteRT-LM-AOT-Dynamic-INT8-Full/resolve/main/qwen3-0.6B-sm8650-aot-dynamic-int8.litertlm",
            ),
            expectedSizeBytes = 819_812_816L,
            expectedSha256 = "30aee1147ea6fc8bfed001a7dbb66e491c55a57b47c8faf87f6dd58f8c2cab3d",
            license = "Apache-2.0",
            inputModalities = listOf(Model.TEXT_MODALITY),
            outputModalities = listOf(Model.TEXT_MODALITY),
            displayName = "Qwen3 0.6B · NPU",
        )

        val Qwen25Small = LocalModelPreset(
            id = "qwen2.5-0.5b-instruct-gguf",
            url = "https://modelscope.cn/models/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/master/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            fallbackUrls = listOf(
                "https://hf-mirror.com/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            ),
            expectedSizeBytes = 491_400_032L,
            // ModelScope publishes the LFS object hash in X-Linked-ETag for this immutable revision.
            expectedSha256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
            license = "Apache-2.0",
            inputModalities = listOf(Model.TEXT_MODALITY),
            outputModalities = listOf(Model.TEXT_MODALITY),
        )

        val Qwen3OnePointSevenB = LocalModelPreset(
            id = "qwen3-1.7b-litert-lm",
            url = "https://modelscope.cn/models/litert-community/Qwen3-1.7B/resolve/master/Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
            fallbackUrls = listOf(
                "https://hf-mirror.com/litert-community/Qwen3-1.7B/resolve/main/Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
            ),
            expectedSizeBytes = 977_184_032L,
            expectedSha256 = "2a72330b504a6596d53a2c00923c57173b10f1b83e6a541213ffec08d1fc5de8",
            license = "Apache-2.0",
            inputModalities = listOf(Model.TEXT_MODALITY),
            outputModalities = listOf(Model.TEXT_MODALITY),
        )

        val Qwen25OnePointFiveB = LocalModelPreset(
            id = "qwen2.5-1.5b-instruct-gguf",
            url = "https://modelscope.cn/models/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/master/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            fallbackUrls = listOf(
                "https://hf-mirror.com/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            ),
            expectedSizeBytes = 1_117_320_736L,
            expectedSha256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
            license = "Apache-2.0",
            inputModalities = listOf(Model.TEXT_MODALITY),
            outputModalities = listOf(Model.TEXT_MODALITY),
        )

        val Qwen25ThreeB = LocalModelPreset(
            id = "qwen2.5-3b-instruct-gguf",
            url = "https://modelscope.cn/models/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/master/qwen2.5-3b-instruct-q4_k_m.gguf",
            fallbackUrls = listOf(
                "https://hf-mirror.com/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            ),
            expectedSizeBytes = 2_104_932_768L,
            expectedSha256 = "626b4a6678b86442240e33df819e00132d3ba7dddfe1cdc4fbb18e0a9615c62d",
            license = "Apache-2.0",
            inputModalities = listOf(Model.TEXT_MODALITY),
            outputModalities = listOf(Model.TEXT_MODALITY),
        )

        val Qwen3FourB = LocalModelPreset(
            id = "qwen3-4b-litert-lm",
            url = "https://modelscope.cn/models/litert-community/Qwen3-4B/resolve/master/qwen3_4b_mixed_int4.litertlm",
            fallbackUrls = listOf(
                "https://hf-mirror.com/litert-community/Qwen3-4B/resolve/main/qwen3_4b_mixed_int4.litertlm",
            ),
            expectedSizeBytes = 2_272_935_936L,
            expectedSha256 = "4877b236f09c0b519958612afeb3c6fc6575c7ce56080d0d3a5406150e0ff9ce",
            license = "Apache-2.0",
            inputModalities = listOf(Model.TEXT_MODALITY),
            outputModalities = listOf(Model.TEXT_MODALITY),
        )

        val all = listOf(
            Qwen3DynamicInt4,
            Gemma3OneBInt4,
            Gemma4E2B,
            Gemma4E4B,
            Gemma3nE2B,
            Kokoro82M,
            KokoroCpu,
            PocketTts100M,
            Phonon31M,
            ParakeetTdt06B,
            Qwen3Asr06B,
            MoonshineTiny,
            EmbeddingGemma2,
            Qwen3NpuSm8650,
            Qwen25Small,
            Qwen3OnePointSevenB,
            Qwen25OnePointFiveB,
            Qwen25ThreeB,
            Qwen3FourB,
        )
    }
}

internal enum class LocalModelDownloadStage {
    QUEUED,
    DOWNLOADING,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

internal data class LocalModelDownloadState(
    val id: String? = null,
    val displayName: String = "",
    val stage: LocalModelDownloadStage? = null,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long? = null,
    val message: String? = null,
    val workName: String? = null,
    val sourceUrl: String? = null,
) {
    val progress: Float?
        get() {
            val total = totalBytes?.takeIf { it > 0 } ?: return null
            return (bytesDownloaded.toFloat() / total).coerceIn(0f, 1f)
        }
}

internal data class LocalModelBinding(
    val providerId: String,
    val modelSelectionId: String,
)

internal data class BuiltinLocalModelDescriptor(
    val record: LocalModelRecord,
    val file: File,
    val settings: LocalModelRuntimeSettings,
)

internal data class BuiltinMnnModelDescriptor(
    val record: LocalModelRecord,
    val directory: File,
    val settings: LocalModelRuntimeSettings,
)

internal class LocalModelException(message: String, val retryable: Boolean = false, cause: Throwable? = null) :
    IOException(message, cause)

/** Hard上限：单个模型文件最多 8 GiB，下载、导入与续传都受它约束。 */
internal const val MAX_LOCAL_MODEL_BYTES = 8L * 1024 * 1024 * 1024

/** `206 Partial Content` 返回的区间，用于确认服务器真的从我们要求的位置续传。 */
internal data class HttpContentRange(val start: Long, val end: Long, val total: Long?)

internal fun parseContentRange(header: String?): HttpContentRange? {
    val raw = header?.trim().orEmpty()
    if (!raw.startsWith("bytes", ignoreCase = true)) return null
    val spec = raw.substringAfter(' ', "").trim()
    val range = spec.substringBefore('/', "")
    val totalText = spec.substringAfter('/', "")
    val start = range.substringBefore('-', "").trim().toLongOrNull() ?: return null
    val end = range.substringAfter('-', "").trim().toLongOrNull() ?: return null
    if (start < 0 || end < start) return null
    val total = totalText.trim().takeIf { it.isNotEmpty() && it != "*" }?.toLongOrNull()
    return HttpContentRange(start, end, total)
}

/** 一次 HTTP 响应应当如何写盘：追加到续传文件、清空重下、还是判定失败。 */
internal sealed interface DownloadResponsePlan {
    data class Append(val offset: Long, val totalBytes: Long?) : DownloadResponsePlan
    data class Restart(val totalBytes: Long?, val reason: String) : DownloadResponsePlan
    data class Fail(
        val message: String,
        val retryable: Boolean,
        val discardPartial: Boolean = false,
    ) : DownloadResponsePlan
}

/**
 * 决定下载响应的落盘方式。纯函数，便于在 JVM 单元测试里覆盖 200/206/416 与各种异常响应。
 */
internal fun planDownloadResponse(
    statusCode: Int,
    requestedOffset: Long,
    contentRange: HttpContentRange?,
    contentLength: Long?,
    expectedSize: Long?,
    maxBytes: Long = MAX_LOCAL_MODEL_BYTES,
): DownloadResponsePlan {
    if (statusCode == 416 && requestedOffset > 0) {
        // 临时文件已经不比服务器文件短（通常是上次其实下完了），清空后重下最稳妥。
        return DownloadResponsePlan.Restart(
            totalBytes = expectedSize,
            reason = "临时文件已到达服务器末尾，已清除并重新下载",
        )
    }
    if (statusCode == 200) {
        val total = contentLength ?: expectedSize
        sizeProblem(total, expectedSize, maxBytes)?.let { return it }
        return DownloadResponsePlan.Restart(
            totalBytes = total,
            reason = "服务器未提供可续传区间，已从头下载",
        )
    }
    if (statusCode == 206) {
        val range = contentRange ?: return DownloadResponsePlan.Fail(
            "服务器续传响应缺少 Content-Range，已清除临时文件并重新下载",
            retryable = true,
            discardPartial = true,
        )
        if (range.start != requestedOffset) {
            return DownloadResponsePlan.Fail(
                "服务器返回的续传区间与请求不一致，已清除临时文件并重新下载",
                retryable = true,
                discardPartial = true,
            )
        }
        val total = range.total ?: contentLength?.let { requestedOffset + it }
        sizeProblem(total, expectedSize, maxBytes)?.let { return it }
        return DownloadResponsePlan.Append(offset = requestedOffset, totalBytes = total)
    }
    if (statusCode in 200..299) {
        return DownloadResponsePlan.Fail("下载服务器返回了不支持的 HTTP $statusCode", retryable = false)
    }
    val retryable = statusCode == 408 || statusCode == 429 || statusCode >= 500
    return DownloadResponsePlan.Fail("下载服务器返回 HTTP $statusCode", retryable = retryable)
}

private fun sizeProblem(total: Long?, expectedSize: Long?, maxBytes: Long): DownloadResponsePlan.Fail? {
    if (total == null) return null
    if (total <= 0) {
        return DownloadResponsePlan.Fail("服务器声明的模型文件为空", retryable = false, discardPartial = true)
    }
    if (total > maxBytes) {
        return DownloadResponsePlan.Fail("模型文件超过 8 GiB 安全上限", retryable = false, discardPartial = true)
    }
    if (expectedSize != null && expectedSize > 0 && total != expectedSize) {
        return DownloadResponsePlan.Fail(
            "服务器文件大小与预设不一致，请核对下载地址",
            retryable = false,
            discardPartial = true,
        )
    }
    return null
}

/** 下载/导入前需要预留的空间：文件本身加上 5%（至少 64 MiB）的落盘余量。 */
internal fun requiredFreeBytes(totalBytes: Long?, minimumHeadroom: Long = 64L * 1024 * 1024): Long? =
    totalBytes?.takeIf { it > 0 }?.let { it + maxOf(minimumHeadroom, it / 20) }

/** 识别被保存成模型扩展名的网页/JSON（模型库登录页、错误页最常见）。 */
internal fun looksLikeTextPayload(prefix: ByteArray): Boolean {
    if (prefix.isEmpty()) return false
    var start = 0
    if (prefix.size >= 3 &&
        prefix[0] == 0xEF.toByte() &&
        prefix[1] == 0xBB.toByte() &&
        prefix[2] == 0xBF.toByte()
    ) {
        start = 3
    }
    val sample = prefix.copyOfRange(start, prefix.size)
    if (sample.any { (it.toInt() and 0xFF) < 0x09 }) return false
    val text = sample.toString(Charsets.UTF_8).trimStart()
    return text.startsWith("<") || text.startsWith("{") || text.startsWith("[")
}

internal object LocalModelRepository {
    private const val MAX_ARTIFACT_BYTES = MAX_LOCAL_MODEL_BYTES
    private const val PROVIDER_ID = "eta-local-model-runtime"
    private const val PREFS = "eta_local_models"
    private const val INDEX_FILE = "index-v1.json"
    private const val MODELS_DIR = "models"
    private const val DOWNLOADS_DIR = "downloads"
    internal const val DOWNLOAD_WORK_NAME = "eta-local-model-download"
    private const val DOWNLOAD_TAG = "eta-local-model-download"
    private const val DOWNLOAD_ID_PREFIX = "lm-"
    private const val PART_META_SUFFIX = ".meta"
    private const val ACTIVE_STATE_GRACE_MS = 30_000L
    private const val STALE_PART_RETENTION_MS = 6L * 60 * 60 * 1000
    private const val MAX_MNN_TREE_ITEMS = 10_000
    internal const val BUILTIN_LITERT_ENDPOINT = "http://127.0.0.1:9379/v1"
    internal const val BUILTIN_PROVIDER_ID = "eta-local-model-runtime-builtin"
    internal const val BUILTIN_GGUF_ENDPOINT = "http://127.0.0.1:9380/v1"
    internal const val BUILTIN_GGUF_PROVIDER_ID = "eta-local-model-runtime-gguf"
    internal const val BUILTIN_MNN_ENDPOINT = "http://127.0.0.1:9381/v1"
    internal const val BUILTIN_MNN_PROVIDER_ID = "eta-local-model-runtime-mnn"
    private val activeStages = setOf(
        LocalModelDownloadStage.QUEUED,
        LocalModelDownloadStage.DOWNLOADING,
        LocalModelDownloadStage.VERIFYING,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val mutex = Mutex()
    private val mutableModels = MutableStateFlow<List<LocalModelRecord>>(emptyList())
    private val mutableDownload = MutableStateFlow(LocalModelDownloadState())
    private val mutableRuntime = MutableStateFlow(LocalModelRuntimeSnapshot())
    private val http = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var applicationContext: Context? = null

    val models: StateFlow<List<LocalModelRecord>> = mutableModels.asStateFlow()
    val download: StateFlow<LocalModelDownloadState> = mutableDownload.asStateFlow()
    val runtime: StateFlow<LocalModelRuntimeSnapshot> = mutableRuntime.asStateFlow()

    fun record(recordId: String): LocalModelRecord? =
        mutableModels.value.firstOrNull { it.id == recordId }

    fun recordsForAbility(input: String? = null, output: String? = null): List<LocalModelRecord> =
        mutableModels.value.filter { record ->
            (input == null || input in record.inputModalities) &&
                (output == null || output in record.outputModalities)
        }

    fun init(context: Context) {
        val app = context.applicationContext
        if (applicationContext === app) return
        synchronized(this) {
            if (applicationContext === app) return
            applicationContext = app
            mutableModels.value = readCatalog(app).models.sortedByDescending { it.installedAt }
            mutableDownload.value = readDownloadState(app)
        }
    }

    fun defaultDownloadUrl(context: Context): String =
        preferences(context).getString("default_download_url", null)
            ?.takeIf(String::isNotBlank)
            ?: LocalModelPreset.Qwen3DynamicInt4.url

    fun setDefaultDownloadUrl(context: Context, url: String) {
        preferences(context).edit().putString("default_download_url", url.trim()).apply()
    }

    fun presetFor(url: String): LocalModelPreset? =
        LocalModelPreset.all.firstOrNull { preset -> url.trim() in preset.downloadUrls }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val context = requireContext()
        mutex.withLock { mutableModels.value = readCatalog(context).models.sortedByDescending { it.installedAt } }
        runCatching { reconcileDownloadState(context) }
        runCatching { cleanupStaleDownloads(context) }
    }

    suspend fun startDownload(
        context: Context,
        displayName: String,
        sourceUrl: String,
    ): String = withContext(Dispatchers.IO) {
        init(context)
        val appContext = context.applicationContext
        val safeName = displayName.trim().ifBlank { "local-model" }.take(120)
        val validated = validateDownloadUrl(sourceUrl)
        val preset = presetFor(validated)
        val id = downloadIdFor(validated)
        // 同一个地址复用同一个 id，也就复用同一个 .part 文件；并发下载同一模型只会续传不会重下。
        if (unfinishedDownloadWork(appContext).isNotEmpty()) {
            val current = mutableDownload.value
            if (current.id == id && current.stage in activeStages) return@withContext id
            throw LocalModelException("已有模型下载任务正在进行，请先取消或等待其完成")
        }
        writeDownloadState(
            context,
            LocalModelDownloadState(
                id = id,
                displayName = safeName,
                stage = LocalModelDownloadStage.QUEUED,
                message = "queued",
                workName = DOWNLOAD_WORK_NAME,
                sourceUrl = validated,
            ),
        )
        val request = OneTimeWorkRequestBuilder<LocalModelDownloadWorker>()
            .setInputData(
                workDataOf(
                    "id" to id,
                    "name" to safeName,
                    "url" to validated,
                    "fallbackUrls" to (preset?.downloadUrls.orEmpty()
                        .filterNot { it == validated }
                        .toTypedArray()),
                    "expectedSize" to (preset?.expectedSizeBytes ?: 0L),
                    "expectedSha" to (preset?.expectedSha256.orEmpty()),
                    "license" to (preset?.license.orEmpty()),
                    "inputModalities" to (preset?.inputModalities ?: listOf(Model.TEXT_MODALITY)).joinToString("|"),
                    "outputModalities" to (preset?.outputModalities ?: listOf(Model.TEXT_MODALITY)).joinToString("|"),
                ),
            )
            .addTag(DOWNLOAD_TAG)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        val enqueued = runCatching {
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                DOWNLOAD_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
        enqueued.exceptionOrNull()?.let { failure ->
            writeDownloadState(context, LocalModelDownloadState())
            throw LocalModelException(
                "无法启动下载任务：${failure.message.orEmpty().take(160)}",
                cause = failure,
            )
        }
        id
    }

    fun cancelDownload(context: Context) {
        init(context)
        val appContext = context.applicationContext
        val current = mutableDownload.value
        if (current.stage == null) return
        runCatching { WorkManager.getInstance(appContext).cancelAllWorkByTag(DOWNLOAD_TAG) }
        runCatching { WorkManager.getInstance(appContext).cancelUniqueWork(DOWNLOAD_WORK_NAME) }
        writeDownloadState(
            appContext,
            current.copy(
                stage = LocalModelDownloadStage.CANCELLED,
                message = "已取消，临时文件保留，重新开始同一地址可续传",
            ),
        )
    }

    fun clearFinishedDownload(context: Context) {
        val current = mutableDownload.value
        if (current.stage in setOf(
                LocalModelDownloadStage.COMPLETED,
                LocalModelDownloadStage.FAILED,
                LocalModelDownloadStage.CANCELLED,
            )
        ) {
            writeDownloadState(context, LocalModelDownloadState())
        }
    }

    suspend fun importUri(context: Context, uri: Uri): LocalModelRecord = withContext(Dispatchers.IO) {
        init(context)
        val appContext = context.applicationContext
        val resolver = appContext.contentResolver
        val fileName = queryDisplayName(appContext, uri)?.trim().orEmpty()
        if (fileName.endsWith(".zip", ignoreCase = true)) {
            return@withContext importMnnZipUri(appContext, uri, fileName)
        }
        val format = LocalModelFormat.fromFileName(fileName) ?: throw LocalModelException(
            if (fileName.isBlank()) {
                "无法确定所选文件的名称与格式，请改从文件管理器直接选择模型文件"
            } else {
                "不支持该模型文件格式（$fileName）。可导入 " +
                    LocalModelFormat.supportedExtensions.sorted().joinToString(", ")
            },
        )
        val id = UUID.randomUUID().toString()
        val part = downloadDirectory(appContext).resolve("$id.part")
        val meta = partMetaFile(appContext, id)
        val size = try {
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法读取所选模型文件" }
                copyLimited(input, part, availableArtifactBudget(appContext))
            }
        } catch (failure: Exception) {
            part.delete()
            meta.delete()
            throw failure
        }
        try {
            val sha = LocalModelFiles.sha256(part)
            validateArtifactOrDelete(part, format, meta)
            installArtifact(
                context = appContext,
                id = id,
                displayName = fileName.substringBeforeLast('.'),
                sourceName = fileName,
                format = format,
                part = part,
                sizeBytes = size,
                sha256 = sha,
                sourceUrl = uri.toString(),
                license = null,
                inputModalities = listOf(Model.TEXT_MODALITY),
                outputModalities = listOf(Model.TEXT_MODALITY),
            )
        } catch (failure: Exception) {
            part.delete()
            meta.delete()
            throw failure
        }
    }

    /**
     * Imports a zipped MNN repository. MNN LLMs are directory packages, so a lone `llm.mnn` file is
     * never accepted. Extraction is bounded and rejects archive paths outside the staging directory.
     */
    private suspend fun importMnnZipUri(context: Context, uri: Uri, fileName: String): LocalModelRecord {
        val id = UUID.randomUUID().toString()
        val archive = downloadDirectory(context).resolve("$id.mnn.zip")
        val unpack = downloadDirectory(context).resolve("$id.mnn-unpack")
        try {
            val bytes = context.contentResolver.openInputStream(uri).use { source ->
                requireNotNull(source) { "无法读取 MNN 模型压缩包" }
                copyLimited(source, archive, availableArtifactBudget(context))
            }
            if (bytes <= 0) throw LocalModelException("MNN 模型压缩包为空")
            val modelRoot = FileInputStream(archive).use { input ->
                MnnModelPackage.extractZip(
                    input = input,
                    destination = unpack,
                    maxBytes = availableArtifactBudget(context),
                )
            }
            val size = MnnModelPackage.directorySize(modelRoot)
            val sha = MnnModelPackage.sha256(modelRoot)
            return installMnnDirectory(
                context = context,
                id = id,
                displayName = fileName.removeSuffix(".zip").ifBlank { modelRoot.name },
                sourceDirectory = modelRoot,
                sourceName = fileName,
                sizeBytes = size,
                sha256 = sha,
                sourceUrl = uri.toString(),
            )
        } catch (failure: Exception) {
            throw if (failure is LocalModelException) failure else LocalModelException(
                failure.message ?: "MNN 模型压缩包导入失败",
                cause = failure,
            )
        } finally {
            archive.delete()
            unpack.deleteRecursively()
        }
    }

    /** Imports an MNN model selected through Android's Storage Access Framework tree picker. */
    suspend fun importMnnTreeUri(context: Context, treeUri: Uri): LocalModelRecord = withContext(Dispatchers.IO) {
        init(context)
        val appContext = context.applicationContext
        val id = UUID.randomUUID().toString()
        val staging = downloadDirectory(appContext).resolve("$id.mnn-tree")
        try {
            val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
            copyDocumentDirectory(
                context = appContext,
                treeUri = treeUri,
                documentId = rootDocumentId,
                target = staging,
                maxBytes = availableArtifactBudget(appContext),
            )
            val root = MnnModelPackage.findModelRoot(staging)
                ?: throw LocalModelException(
                    "MNN 文件夹不完整：需要 config.json、llm.mnn 及权重/tokenizer 文件",
                )
            val displayName = documentDisplayName(appContext, treeUri, rootDocumentId)
                ?.ifBlank { root.name }
                ?: root.name
            installMnnDirectory(
                context = appContext,
                id = id,
                displayName = displayName,
                sourceDirectory = root,
                sourceName = displayName,
                sizeBytes = MnnModelPackage.directorySize(root),
                sha256 = MnnModelPackage.sha256(root),
                sourceUrl = treeUri.toString(),
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun copyDocumentDirectory(
        context: Context,
        treeUri: Uri,
        documentId: String,
        target: File,
        maxBytes: Long,
    ) {
        val resolver = context.contentResolver
        var total = 0L
        var itemCount = 0
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<String, File>>()
        queue += documentId to target
        while (queue.isNotEmpty()) {
            val (parentId, parentTarget) = queue.removeFirst()
            require(visited.add(parentId)) { "MNN 文件夹包含循环目录" }
            parentTarget.mkdirs()
            resolver.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId),
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val typeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    itemCount++
                    require(itemCount <= MAX_MNN_TREE_ITEMS) { "MNN 模型文件夹项目数过多" }
                    val childId = cursor.getString(idIndex)
                    val childName = safeMnnPathSegment(cursor.getString(nameIndex).orEmpty())
                    val childTarget = parentTarget.resolve(childName)
                    val mime = cursor.getString(typeIndex).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        queue += childId to childTarget
                    } else {
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                        childTarget.parentFile?.mkdirs()
                        val copied = resolver.openInputStream(childUri).use { input ->
                            requireNotNull(input) { "无法读取 MNN 模型文件：$childName" }
                            copyLimitedToStream(input, childTarget, maxBytes - total)
                        }
                        total += copied
                    }
                }
            } ?: throw LocalModelException("无法读取所选 MNN 模型文件夹")
        }
    }

    private fun safeMnnPathSegment(raw: String): String {
        val name = raw.trim().replace('/', '_').replace('\\', '_')
        require(name.isNotEmpty() && name != "." && name != "..") { "MNN 文件夹包含无效文件名" }
        return name
    }

    private fun documentDisplayName(context: Context, treeUri: Uri, documentId: String): String? =
        runCatching {
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()

    private suspend fun installMnnDirectory(
        context: Context,
        id: String,
        displayName: String,
        sourceDirectory: File,
        sourceName: String,
        sizeBytes: Long,
        sha256: String,
        sourceUrl: String?,
    ): LocalModelRecord {
        require(MnnModelPackage.isModelDirectory(sourceDirectory)) {
            "MNN 模型目录不完整：需要 config.json 与 llm.mnn"
        }
        val duplicate = mutex.withLock {
            mutableModels.value.firstOrNull { existing ->
                existing.format == LocalModelFormat.MNN &&
                    existing.sha256.equals(sha256, ignoreCase = true) &&
                    MnnModelPackage.isModelDirectory(modelDirectory(context).resolve(existing.fileName))
            }
        }
        if (duplicate != null) return duplicate
        val folderBase = LocalModelFiles.safeFileName(
            "${sha256.take(16)}-${displayName.ifBlank { sourceName.substringBeforeLast('.') }}-MNN",
        )
        val target = modelDirectory(context).resolve(folderBase)
        target.deleteRecursively()
        if (!sourceDirectory.renameTo(target)) {
            MnnModelPackage.copyDirectory(sourceDirectory, target, MAX_ARTIFACT_BYTES)
        }
        require(MnnModelPackage.isModelDirectory(target)) { "MNN 模型目录写入后不完整" }
        val modalities = buildList {
            add(Model.TEXT_MODALITY)
            if (MnnModelPackage.hasVision(target)) add(Model.IMAGE_MODALITY)
        }
        val record = LocalModelRecord(
            id = id,
            displayName = displayName.trim().ifBlank { target.name },
            fileName = target.name,
            format = LocalModelFormat.MNN,
            sizeBytes = sizeBytes,
            sha256 = sha256.lowercase(),
            sourceUrl = sourceUrl,
            license = null,
            lastVerifiedAt = System.currentTimeMillis(),
            contextWindow = 8_192,
            inputModalities = modalities,
            outputModalities = listOf(Model.TEXT_MODALITY),
            serverModelId = target.name,
            runtime = LocalModelRuntime.BUILT_IN_PENDING,
        )
        mutex.withLock {
            val current = readCatalog(context)
            val updated = current.copy(models = current.models.filterNot { it.id == record.id } + record)
            writeCatalog(context, updated)
            mutableModels.value = updated.models.sortedByDescending { it.installedAt }
        }
        return record
    }

    suspend fun bindToProvider(
        recordId: String,
        endpoint: String,
        serverModelId: String,
        contextWindow: Int,
        supportsTools: Boolean,
        supportsVision: Boolean,
        forcedProviderId: String? = null,
    ): LocalModelBinding = withContext(Dispatchers.IO) {
        val context = requireContext()
        val normalizedEndpoint = validateServiceEndpoint(endpoint)
        require(serverModelId.isNotBlank()) { "服务模型 ID 不能为空" }
        require(contextWindow in 1_024..1_048_576) { "上下文长度需在 1K 到 1M 之间" }
        val record = mutex.withLock {
            mutableModels.value.firstOrNull { it.id == recordId }
                ?: throw IllegalArgumentException("本地模型记录不存在")
        }
        // 每个服务地址一个 Provider：同一个端口上的多个模型共享 Provider，不同端口互不覆盖 baseUrl。
        val providerId = forcedProviderId ?: providerIdFor(normalizedEndpoint)
        val provider = ProviderRepository.providerById(providerId) ?: ProviderRepository.addProvider(
            CustomProviderSetting(
                id = providerId,
                name = "本地模型运行时",
                baseUrl = normalizedEndpoint,
                sourceType = "custom",
                endpointMode = OpenAiEndpointMode.CHAT_COMPLETIONS,
                isEnabled = true,
                isBuiltIn = false,
            ),
        )
        if (provider is CustomProviderSetting && (provider.baseUrl != normalizedEndpoint || !provider.isEnabled)) {
            ProviderRepository.updateProvider(
                provider.copy(baseUrl = normalizedEndpoint, isEnabled = true),
            )
        }
        val selectionId = record.boundModelSelectionId?.takeIf { existing ->
            provider.models.any { it.id == existing }
        } ?: "local-model-${record.sha256.take(16)}"
        val modalities = buildList {
            add(Model.TEXT_MODALITY)
            if (supportsVision) add(Model.IMAGE_MODALITY)
            addAll(record.inputModalities.filterNot { it == Model.IMAGE_MODALITY || it == Model.TEXT_MODALITY })
        }.distinct()
        ModelRepository.saveModel(
            providerId,
            Model(
                id = selectionId,
                modelId = serverModelId.trim(),
                displayName = record.displayName,
                isEnabled = true,
                contextWindow = contextWindow,
                contextWindowOverride = contextWindow,
                inputModalities = modalities,
                outputModalities = record.outputModalities,
                attachment = supportsVision,
                toolCall = supportsTools,
                reasoning = record.supportsReasoning,
                source = ModelSource.LOCAL,
                createdAt = record.installedAt,
            ),
        )
        mutateRecord(record.id) {
            it.copy(
                serviceEndpoint = normalizedEndpoint,
                serverModelId = serverModelId.trim(),
                contextWindow = contextWindow,
                supportsTools = supportsTools,
                inputModalities = modalities,
                boundProviderId = providerId,
                boundModelSelectionId = selectionId,
            )
        }
        LocalModelBinding(providerId, selectionId)
    }

    /**
     * Makes a downloaded LiteRT-LM or GGUF artifact an in-process model. No endpoint or API key is
     * requested from the user; ProviderClientFactory routes the generated provider to the exact
     * bundled runtime for the file format.
     */
    suspend fun activateBuiltInRuntime(recordId: String): LocalModelBinding = withContext(Dispatchers.IO) {
        val context = requireContext()
        val source = mutex.withLock {
            mutableModels.value.firstOrNull { it.id == recordId }
                ?: readCatalog(context).models.firstOrNull { it.id == recordId }
                ?: throw IllegalArgumentException("本地模型记录不存在")
        }
        require(source.format in setOf(LocalModelFormat.LITERT_LM, LocalModelFormat.GGUF, LocalModelFormat.MNN)) {
            "该模型格式需要兼容的外部运行时；内置引擎支持 .litertlm、.gguf 和 MNN 目录"
        }
        require(
            Model.TEXT_MODALITY in source.outputModalities &&
                "audio" !in source.outputModalities &&
                "embedding" !in source.outputModalities,
        ) {
            "该文件是语音、嵌入或其他专用组件，不能作为聊天模型启动"
        }
        val settings = source.runtimeSettings.validated()
        val sourcePath = artifactFile(context, source)
        if (source.format == LocalModelFormat.MNN) {
            require(MnnModelPackage.isModelDirectory(sourcePath)) { "MNN 模型目录已丢失或不完整，请重新导入" }
        } else {
            require(sourcePath.isFile) { "模型文件已丢失，请重新导入或下载" }
        }
        val endpoint = when (source.format) {
            LocalModelFormat.LITERT_LM -> BUILTIN_LITERT_ENDPOINT
            LocalModelFormat.GGUF -> BUILTIN_GGUF_ENDPOINT
            LocalModelFormat.MNN -> BUILTIN_MNN_ENDPOINT
            else -> error("unsupported built-in model format")
        }
        val providerId = when (source.format) {
            LocalModelFormat.LITERT_LM -> BUILTIN_PROVIDER_ID
            LocalModelFormat.GGUF -> BUILTIN_GGUF_PROVIDER_ID
            LocalModelFormat.MNN -> BUILTIN_MNN_PROVIDER_ID
            else -> error("unsupported built-in provider")
        }
        val nativeInfo = if (source.format == LocalModelFormat.LITERT_LM) {
            runCatching {
                ModelInfo.from(sourcePath.absolutePath).use { info ->
                    val llm = info as? ModelInfo.Llm
                    NativeLiteRtModelInfo(
                        maxContextTokens = info.maxContextTokens().takeIf { it > 0 },
                        supportsVision = info.inputModalities().vision,
                        supportsThinking = llm?.supportsThinking() == true,
                    )
                }
            }.getOrNull()
        } else {
            null
        }
        val nativeSettings = if (nativeInfo?.supportsVision == true) {
            settings.copy(imageInputEnabled = true)
        } else {
            settings
        }
        val nativeContextWindow = nativeInfo?.maxContextTokens
            ?.let { max -> minOf(nativeSettings.contextWindow, max) }
            ?: nativeSettings.contextWindow
        val nativeInputModalities = buildList {
            add(Model.TEXT_MODALITY)
            if (nativeInfo?.supportsVision == true) add(Model.IMAGE_MODALITY)
        }
        val binding = bindToProvider(
            recordId = source.id,
            endpoint = endpoint,
            serverModelId = source.id,
            contextWindow = nativeContextWindow,
            // Text chat models now use the in-process text tool bridge implemented by all three
            // bundled engines. Dedicated ASR/TTS/embedding artifacts are rejected above and never
            // receive chat tool capability.
            supportsTools = true,
            supportsVision = nativeInfo?.supportsVision == true,
            forcedProviderId = providerId,
        )
        mutateRecord(source.id) {
            it.copy(
                runtime = when (source.format) {
                    LocalModelFormat.LITERT_LM -> LocalModelRuntime.BUILT_IN_LITERT_LM
                    LocalModelFormat.GGUF -> LocalModelRuntime.BUILT_IN_LLAMA_CPP
                    else -> LocalModelRuntime.BUILT_IN_MNN
                },
                runtimeSettings = nativeSettings.copy(contextWindow = nativeContextWindow),
                supportsTools = true,
                supportsReasoning = nativeInfo?.supportsThinking ?: source.supportsReasoning,
                inputModalities = if (nativeInfo != null) nativeInputModalities else source.inputModalities,
                contextWindow = nativeContextWindow,
            )
        }
        binding
    }

    /** Selects an already loaded built-in model. Call this only after warm-up succeeds so a
     * device-specific load failure never becomes the global default model. */
    suspend fun selectBuiltInRuntime(recordId: String) = withContext(Dispatchers.IO) {
        val record = mutex.withLock { mutableModels.value.firstOrNull { it.id == recordId } }
            ?: throw IllegalArgumentException("本地模型记录不存在")
        require(record.runtime in setOf(
            LocalModelRuntime.BUILT_IN_LITERT_LM,
            LocalModelRuntime.BUILT_IN_LLAMA_CPP,
            LocalModelRuntime.BUILT_IN_MNN,
        )) {
            "本地模型尚未完成内置运行时注册"
        }
        val selectionId = requireNotNull(record.boundModelSelectionId) {
            "本地模型尚未连接到模型选择器"
        }
        // Older builds persisted toolCall=false when the native text bridge did not exist yet.
        // Upgrade the selected model in place so users who already downloaded a model do not have
        // to remove and import it again before tools become available.
        val providerId = requireNotNull(record.boundProviderId) { "本地模型缺少 Provider 记录" }
        ProviderRepository.providerById(providerId)?.models
            ?.firstOrNull { it.id == selectionId }
            ?.takeIf { it.toolCall != true }
            ?.let { model -> ModelRepository.saveModel(providerId, model.copy(toolCall = true)) }
        if (!record.supportsTools) mutateRecord(record.id) { it.copy(supportsTools = true) }
        RuntimeConfigRepository.setSelectedModelId(selectionId)
    }

    suspend fun updateRuntimeSettings(
        recordId: String,
        transform: (LocalModelRuntimeSettings) -> LocalModelRuntimeSettings,
    ): LocalModelRecord {
        val record = mutateRecord(recordId) {
            val normalized = transform(it.runtimeSettings).validated()
            it.copy(
                runtimeSettings = normalized,
                contextWindow = normalized.contextWindow,
            )
        }
        val providerId = record.boundProviderId
        val selectionId = record.boundModelSelectionId
        if (providerId != null && selectionId != null) {
            ProviderRepository.providerById(providerId)?.models
                ?.firstOrNull { it.id == selectionId }
                ?.let { model ->
                    ModelRepository.saveModel(
                        providerId,
                        model.copy(
                            contextWindow = record.runtimeSettings.contextWindow,
                            contextWindowOverride = record.runtimeSettings.contextWindow,
                            inputModalities = record.inputModalities,
                            attachment = record.supportsVision,
                        ),
                    )
                }
        }
        return record
    }

    internal fun builtInDescriptor(recordId: String): BuiltinLocalModelDescriptor? {
        val context = requireContext()
        val record = mutableModels.value.firstOrNull { it.id == recordId } ?: return null
        if (record.format != LocalModelFormat.LITERT_LM) return null
        return BuiltinLocalModelDescriptor(
            record = record,
            file = artifactFile(context, record),
            settings = record.runtimeSettings.validated(),
        )
    }

    internal fun ggufDescriptor(recordId: String): BuiltinLocalModelDescriptor? {
        val context = requireContext()
        val record = mutableModels.value.firstOrNull { it.id == recordId } ?: return null
        if (record.format != LocalModelFormat.GGUF) return null
        return BuiltinLocalModelDescriptor(
            record = record,
            file = artifactFile(context, record),
            settings = record.runtimeSettings.validated(),
        )
    }

    internal fun mnnDescriptor(recordId: String): BuiltinMnnModelDescriptor? {
        val context = requireContext()
        val record = mutableModels.value.firstOrNull { it.id == recordId } ?: return null
        if (record.format != LocalModelFormat.MNN) return null
        return BuiltinMnnModelDescriptor(
            record = record,
            directory = artifactFile(context, record),
            settings = record.runtimeSettings.validated(),
        )
    }

    internal fun publishRuntimeSnapshot(snapshot: LocalModelRuntimeSnapshot) {
        mutableRuntime.value = snapshot
    }

    /** Called after a download is committed. Failures must not turn a completed file into a
     * failed download, so auto-connect is best-effort and remains visible in model settings. */
    internal suspend fun autoActivateBuiltInIfConfigured(recordId: String) {
        val record = mutableModels.value.firstOrNull { it.id == recordId } ?: return
        if (record.format !in setOf(LocalModelFormat.LITERT_LM, LocalModelFormat.GGUF, LocalModelFormat.MNN) ||
            !record.runtimeSettings.autoConnect
        ) return
        runCatching { activateBuiltInRuntime(recordId) }
    }

    suspend fun delete(context: Context, recordId: String) = withContext(Dispatchers.IO) {
        init(context)
        val removed = mutex.withLock {
            val current = readCatalog(context)
            val record = current.models.firstOrNull { it.id == recordId } ?: return@withLock null
            val updated = current.copy(models = current.models.filterNot { it.id == recordId })
            writeCatalog(context, updated)
            mutableModels.value = updated.models.sortedByDescending { it.installedAt }
            record
        } ?: return@withContext
        val removedPath = modelDirectory(context).resolve(removed.fileName)
        if (removed.format == LocalModelFormat.MNN || removedPath.isDirectory) {
            removedPath.deleteRecursively()
        } else {
            removedPath.delete()
        }
        // 删除记录时一并移除可能残留的续传文件，否则会继续占住磁盘。
        downloadDirectory(context).resolve("${removed.id}.part").delete()
        partMetaFile(context, removed.id).delete()
        val boundProvider = removed.boundProviderId
        val boundSelection = removed.boundModelSelectionId
        if (boundProvider != null && boundSelection != null) {
            runCatching { ModelRepository.deleteModel(boundProvider, boundSelection) }
        }
    }

    suspend fun verify(recordId: String): LocalModelRecord = withContext(Dispatchers.IO) {
        val context = requireContext()
        val record = mutableModels.value.firstOrNull { it.id == recordId }
            ?: throw IllegalArgumentException("本地模型记录不存在")
        val file = modelDirectory(context).resolve(record.fileName)
        require(if (record.format == LocalModelFormat.MNN) file.isDirectory else file.isFile) {
            "模型${if (record.format == LocalModelFormat.MNN) "目录" else "文件"}已丢失，请重新导入或下载"
        }
        val actualSize = if (record.format == LocalModelFormat.MNN) {
            MnnModelPackage.directorySize(file)
        } else {
            file.length()
        }
        require(actualSize == record.sizeBytes) { "模型文件大小与记录不一致" }
        val sha = if (record.format == LocalModelFormat.MNN) {
            MnnModelPackage.sha256(file)
        } else {
            LocalModelFiles.sha256(file)
        }
        require(sha.equals(record.sha256, ignoreCase = true)) { "模型文件 SHA-256 校验失败" }
        if (record.format == LocalModelFormat.MNN) {
            require(MnnModelPackage.isModelDirectory(file)) { "MNN 模型目录不完整" }
        } else {
            validateArtifact(file, record.format)
        }
        mutateRecord(record.id) { it.copy(lastVerifiedAt = System.currentTimeMillis()) }
    }

    suspend fun recordBenchmark(
        recordId: String,
        result: LocalModelPerformanceResult,
    ): LocalModelRecord = mutateRecord(recordId) {
        it.copy(lastBenchmark = result)
    }

    internal suspend fun performDownload(
        context: Context,
        id: String,
        displayName: String,
        sourceUrl: String,
        expectedSize: Long?,
        expectedSha: String?,
        license: String?,
        inputModalities: List<String>,
        outputModalities: List<String>,
        onProgress: suspend (downloaded: Long, total: Long?) -> Unit,
    ): LocalModelRecord = withContext(Dispatchers.IO) {
        init(context)
        val validated = validateDownloadUrl(sourceUrl)
        val nameFromUrl = validated.toHttpUrlOrNull()?.pathSegments?.lastOrNull().orEmpty()
        val sourceName = nameFromUrl.ifBlank { displayName }
        val format = LocalModelFormat.fromFileName(sourceName)
            ?: throw LocalModelException("下载地址必须指向受支持的模型文件")
        val part = downloadDirectory(context).resolve("$id.part")
        val metaFile = partMetaFile(context, id)
        try {
            var metadata = readPartMeta(metaFile)
            if (metadata != null && metadata.url != validated) {
                // id 已按地址生成，理论上不会走到这里；真发生时旧数据不能当作续传基线。
                part.delete()
                metaFile.delete()
                metadata = null
            }
            var startBytes = part.takeIf { it.isFile }?.length() ?: 0L
            if (startBytes > MAX_ARTIFACT_BYTES) {
                part.delete()
                startBytes = 0
            }
            // A previous worker can be interrupted after the last byte reaches disk but before
            // the artifact is installed. Reuse and verify that complete part instead of sending a
            // Range request past EOF and making the user download the whole model a second time.
            if (expectedSize != null && expectedSize > 0 && startBytes == expectedSize) {
                onProgress(startBytes, startBytes)
                val completedSha = LocalModelFiles.sha256(part)
                if (expectedSha.isNullOrBlank() || completedSha.equals(expectedSha, ignoreCase = true)) {
                    validateArtifactOrDelete(part, format, metaFile)
                    return@withContext installArtifact(
                        context = context,
                        id = id,
                        displayName = displayName,
                        sourceName = sourceName,
                        format = format,
                        part = part,
                        sizeBytes = startBytes,
                        sha256 = completedSha,
                        sourceUrl = validated,
                        license = license,
                        inputModalities = inputModalities,
                        outputModalities = outputModalities,
                    ).also { metaFile.delete() }
                }
                part.delete()
                metaFile.delete()
                startBytes = 0
            }
            val request = Request.Builder().url(validated).get()
            if (startBytes > 0) {
                request.header("Range", "bytes=$startBytes-")
                // If-Range：远端文件变了就让服务器退回 200 全量，避免拼出半新半旧的文件。
                metadata?.etag?.takeIf { it.isNotBlank() }?.let { request.header("If-Range", it) }
                    ?: metadata?.lastModified?.takeIf { it.isNotBlank() }
                        ?.let { request.header("If-Range", it) }
            }
            val response = try {
                http.newCall(request.build()).execute()
            } catch (failure: IOException) {
                throw LocalModelException(
                    "下载连接失败: ${failure.message.orEmpty().take(160)}",
                    retryable = true,
                    cause = failure,
                )
            }
            var total: Long? = expectedSize
            response.use { res ->
                val plan = planDownloadResponse(
                    statusCode = res.code,
                    requestedOffset = startBytes,
                    contentRange = parseContentRange(res.header("Content-Range")),
                    contentLength = res.body.contentLength().takeIf { it >= 0 },
                    expectedSize = expectedSize,
                )
                when (plan) {
                    is DownloadResponsePlan.Fail -> {
                        if (plan.discardPartial) {
                            part.delete()
                            metaFile.delete()
                        }
                        throw LocalModelException(plan.message, retryable = plan.retryable)
                    }

                    is DownloadResponsePlan.Restart -> {
                        part.delete()
                        startBytes = 0
                        total = plan.totalBytes
                        // 例如 416：响应本身没有可用正文，清掉临时文件后交给重试做一次完整下载。
                        if (!res.isSuccessful) {
                            throw LocalModelException(plan.reason, retryable = true)
                        }
                    }

                    is DownloadResponsePlan.Append -> {
                        startBytes = plan.offset
                        total = plan.totalBytes
                    }
                }
                val contentType = res.header("Content-Type").orEmpty().lowercase()
                if (contentType.startsWith("text/") || contentType.contains("html")) {
                    throw LocalModelException("下载地址返回了网页而不是模型文件，请检查链接是否需要登录或已失效")
                }
                // 续传只需要剩余空间，不能拿完整文件大小去卡已经下了一大半的任务。
                ensureFreeSpace(context, total?.let { (it - startBytes).coerceAtLeast(0L) })
                writePartMeta(
                    metaFile,
                    LocalModelPartMeta(
                        id = id,
                        url = validated,
                        etag = res.header("ETag"),
                        lastModified = res.header("Last-Modified"),
                    ),
                )
                copyResponseBody(
                    body = res.body.byteStream(),
                    part = part,
                    offset = startBytes,
                    total = total,
                    onProgress = onProgress,
                )
            }
            val actualSize = part.length()
            val knownTotal = total ?: expectedSize
            if (knownTotal != null && actualSize != knownTotal) {
                throw LocalModelException(
                    if (actualSize < knownTotal) {
                        "下载不完整，已保留临时文件供续传"
                    } else {
                        "下载内容超出预期长度，已保留临时文件"
                    },
                    retryable = true,
                )
            }
            if (actualSize > MAX_ARTIFACT_BYTES) {
                throw LocalModelException("模型文件超过 8 GiB 安全上限")
            }
            // 哈希 2 GiB 以上模型耗时很长，先把 UI 切到“校验中”，避免看起来卡在 100%。
            onProgress(actualSize, actualSize)
            val sha = LocalModelFiles.sha256(part)
            if (!expectedSha.isNullOrBlank() && !sha.equals(expectedSha, ignoreCase = true)) {
                throw LocalModelException("模型 SHA-256 校验失败，临时文件已删除")
            }
            validateArtifactOrDelete(part, format, metaFile)
            installArtifact(
                context = context,
                id = id,
                displayName = displayName,
                sourceName = sourceName,
                format = format,
                part = part,
                sizeBytes = actualSize,
                sha256 = sha,
                sourceUrl = validated,
                license = license,
                inputModalities = inputModalities,
                outputModalities = outputModalities,
            ).also { metaFile.delete() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // 可续传的失败保留 .part；其它失败（格式错、大小不符、文件损坏）立即清理。
            val keepPartial = failure is LocalModelException && failure.retryable
            if (!keepPartial) {
                part.delete()
                metaFile.delete()
            }
            if (failure is LocalModelException) throw failure
            throw LocalModelException(
                "下载失败：${failure.message.orEmpty().take(160)}",
                retryable = failure is IOException,
                cause = failure,
            )
        }
    }

    internal fun validateDownloadUrl(raw: String): String {
        val cleaned = raw.trim()
        val url = cleaned.toHttpUrlOrNull() ?: throw IllegalArgumentException("模型下载地址格式无效")
        require(url.scheme == "https" || url.scheme == "http") { "下载地址必须使用 HTTP 或 HTTPS" }
        // 明文 HTTP 只允许回环/私有网络，公网模型必须走 HTTPS，避免中间人替换模型文件。
        require(url.scheme == "https" || ProviderEndpoint.isLocalOrPrivate(cleaned)) {
            "公网下载地址必须使用 HTTPS"
        }
        require(url.username.isEmpty() && url.password.isEmpty()) { "下载地址不能包含账号或密码" }
        require(url.fragment == null) { "下载地址不能包含 # 片段" }
        require(LocalModelFiles.formatFromUrl(cleaned) != null) { "下载地址必须指向受支持的模型文件" }
        return url.toString()
    }

    internal fun validateServiceEndpoint(raw: String): String {
        val cleaned = raw.trim().trimEnd('/')
        val uri = runCatching { URI(cleaned) }.getOrNull()
        require(uri?.scheme in setOf("http", "https") && !uri?.host.isNullOrBlank()) {
            "本地服务地址必须是有效的 HTTP(S) 地址"
        }
        require(ProviderEndpoint.isLocalOrPrivate(cleaned)) {
            "为保护隐私，本地模型服务应使用回环或私有网络地址"
        }
        return cleaned
    }

    /** 下载 id 由规范化地址决定：同一模型重复点击只会续传，不会再起第二份并行下载。 */
    internal fun downloadIdFor(url: String): String =
        DOWNLOAD_ID_PREFIX + LocalModelFiles.shortHash(url.trim(), 20)

    /** 每个服务地址一个 Provider；默认端口沿用历史 id，保证既有绑定不失效。 */
    private fun providerIdFor(endpoint: String): String =
        if (endpoint == LocalModelRecord.DEFAULT_LOCAL_SERVICE_ENDPOINT) {
            PROVIDER_ID
        } else {
            "$PROVIDER_ID-${LocalModelFiles.shortHash(endpoint, 8)}"
        }

    private fun unfinishedDownloadWork(context: Context): List<WorkInfo> =
        runCatching {
            WorkManager.getInstance(context).getWorkInfosByTag(DOWNLOAD_TAG).get(5, TimeUnit.SECONDS)
        }.getOrDefault(emptyList()).filter { !it.state.isFinished }

    /** 进程被杀后状态可能停在“下载中”；确认没有 WorkManager 任务后降级为可续传的中断状态。 */
    private fun reconcileDownloadState(context: Context) {
        val current = mutableDownload.value
        if (current.stage !in activeStages) return
        if (System.currentTimeMillis() - preferences(context).getLong("download_updated_at", 0L) < ACTIVE_STATE_GRACE_MS) return
        if (unfinishedDownloadWork(context).isNotEmpty()) return
        writeDownloadState(
            context,
            current.copy(
                stage = LocalModelDownloadStage.FAILED,
                message = "下载任务已中断，重新开始同一地址可续传",
            ),
        )
    }

    /** 清理不属于当前任务的 .part/.meta，以及写索引时中断留下的临时文件。 */
    private fun cleanupStaleDownloads(context: Context) {
        val state = mutableDownload.value
        val keepId = state.id.takeIf { state.stage in activeStages }
        val cutoff = System.currentTimeMillis() - STALE_PART_RETENTION_MS
        downloadDirectory(context).listFiles()?.forEach { file ->
            val name = file.name
            if (!name.endsWith(".part") && !name.endsWith(PART_META_SUFFIX)) return@forEach
            if (keepId != null && name.startsWith(keepId)) return@forEach
            if (file.lastModified() < cutoff) file.delete()
        }
        val tempIndex = dataDirectory(context).resolve("$INDEX_FILE.tmp")
        if (tempIndex.isFile && tempIndex.lastModified() < cutoff) tempIndex.delete()
    }

    private fun partMetaFile(context: Context, id: String): File =
        downloadDirectory(context).resolve("$id$PART_META_SUFFIX")

    private fun readPartMeta(file: File): LocalModelPartMeta? =
        if (!file.isFile) {
            null
        } else {
            runCatching { json.decodeFromString<LocalModelPartMeta>(file.readText(Charsets.UTF_8)) }.getOrNull()
        }

    private fun writePartMeta(file: File, meta: LocalModelPartMeta) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(meta), Charsets.UTF_8)
        }
    }

    private fun ensureFreeSpace(context: Context, expectedBytes: Long?) {
        val required = requiredFreeBytes(expectedBytes) ?: return
        val available = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrNull() ?: return
        if (available < required) {
            throw LocalModelException(
                "存储空间不足：约需 ${LocalModelFiles.formatBytes(required)}，当前可用 " +
                    LocalModelFiles.formatBytes(available),
            )
        }
    }

    /** 导入前无法预知大小的上限：可用空间减去 64 MiB 余量，且不超过 8 GiB。 */
    private fun availableArtifactBudget(context: Context): Long {
        val available = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrNull()
            ?: return MAX_ARTIFACT_BYTES
        val budget = available - 64L * 1024 * 1024
        if (budget <= 0) throw LocalModelException("设备存储空间不足，无法保存模型文件")
        return minOf(MAX_ARTIFACT_BYTES, budget)
    }

    private suspend fun copyResponseBody(
        body: InputStream,
        part: File,
        offset: Long,
        total: Long?,
        onProgress: suspend (downloaded: Long, total: Long?) -> Unit,
    ) {
        val buffer = ByteArray(256 * 1024)
        FileOutputStream(part, offset > 0).use { fileOutput ->
            BufferedOutputStream(fileOutput).use { output ->
                body.buffered().use { input ->
                    var downloaded = offset
                    var lastReport = offset
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        downloaded += read
                        if (downloaded > MAX_ARTIFACT_BYTES) {
                            throw LocalModelException("模型文件超过 8 GiB 安全上限")
                        }
                        output.write(buffer, 0, read)
                        if (downloaded - lastReport >= 4L * 1024 * 1024 || downloaded == total) {
                            output.flush()
                            lastReport = downloaded
                            onProgress(downloaded, total)
                        }
                    }
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
        }
    }

    private fun validateArtifactOrDelete(file: File, format: LocalModelFormat, meta: File?) {
        try {
            validateArtifact(file, format)
        } catch (failure: Exception) {
            file.delete()
            meta?.delete()
            throw LocalModelException(
                failure.message ?: "模型文件格式校验失败，临时文件已清理",
                cause = failure,
            )
        }
    }

    private suspend fun installArtifact(
        context: Context,
        id: String,
        displayName: String,
        sourceName: String,
        format: LocalModelFormat,
        part: File,
        sizeBytes: Long,
        sha256: String,
        sourceUrl: String?,
        license: String?,
        inputModalities: List<String>,
        outputModalities: List<String>,
    ): LocalModelRecord {
        // 只有磁盘上确实存在同内容文件才算重复；记录还在但文件被删掉时必须重新安装。
        val duplicate = mutex.withLock {
            mutableModels.value.firstOrNull { existing ->
                existing.sha256.equals(sha256, ignoreCase = true) &&
                    modelDirectory(context).resolve(existing.fileName).let { file ->
                        file.isFile && file.length() == existing.sizeBytes
                    }
            }
        }
        if (duplicate != null) {
            part.delete()
            val merged = duplicate.copy(
                sourceUrl = duplicate.sourceUrl?.takeIf(String::isNotBlank) ?: sourceUrl,
                license = duplicate.license?.takeIf(String::isNotBlank) ?: license?.takeIf(String::isNotBlank),
                inputModalities = inputModalities.ifEmpty { duplicate.inputModalities }.distinct(),
                outputModalities = outputModalities.ifEmpty { duplicate.outputModalities }.distinct(),
            )
            val repaired = normalizePresetMetadata(merged)
            if (repaired != duplicate) {
                mutex.withLock {
                    val current = readCatalog(context)
                    val updated = current.copy(
                        models = current.models.map { existing ->
                            if (existing.id == duplicate.id) repaired else existing
                        },
                    )
                    writeCatalog(context, updated)
                    mutableModels.value = updated.models.sortedByDescending { it.installedAt }
                }
            }
            return repaired
        }
        val safeFileName = LocalModelFiles.safeFileName("${sha256.take(16)}-$sourceName")
        val target = modelDirectory(context).resolve(safeFileName)
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            if (target.length() != sizeBytes) {
                target.delete()
                throw LocalModelException("模型文件写入不完整，请重新下载", retryable = true)
            }
            part.delete()
        }
        val record = LocalModelRecord(
            id = id,
            displayName = displayName.trim().ifBlank { sourceName.substringBeforeLast('.') },
            fileName = safeFileName,
            format = format,
            sizeBytes = sizeBytes,
            sha256 = sha256.lowercase(),
            sourceUrl = sourceUrl,
            license = license?.takeIf(String::isNotBlank),
            lastVerifiedAt = System.currentTimeMillis(),
            // The runtime profile can raise this later; 4K avoids turning a first load into an
            // avoidable KV-cache allocation failure on a phone.
            contextWindow = 8_192,
            inputModalities = inputModalities.ifEmpty { listOf(Model.TEXT_MODALITY) }.distinct(),
            outputModalities = outputModalities.ifEmpty { listOf(Model.TEXT_MODALITY) }.distinct(),
            serverModelId = sourceName.substringBeforeLast('.').ifBlank { sourceName },
            // LiteRT-LM and GGUF both have first-party in-process adapters. Other formats still
            // need a compatible external OpenAI service (or a future dedicated adapter).
            runtime = if (format in setOf(LocalModelFormat.LITERT_LM, LocalModelFormat.GGUF)) {
                LocalModelRuntime.BUILT_IN_PENDING
            } else {
                LocalModelRuntime.EXTERNAL_OPENAI_COMPATIBLE
            },
        )
        mutex.withLock {
            val current = readCatalog(context)
            val updated = current.copy(models = current.models.filterNot { it.id == record.id } + record)
            writeCatalog(context, updated)
            mutableModels.value = updated.models.sortedByDescending { it.installedAt }
        }
        return record
    }

    private suspend fun mutateRecord(id: String, transform: (LocalModelRecord) -> LocalModelRecord): LocalModelRecord {
        val context = requireContext()
        return mutex.withLock {
            val current = readCatalog(context)
            val old = current.models.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("本地模型记录不存在")
            val updatedRecord = transform(old)
            val updated = current.copy(models = current.models.map { if (it.id == id) updatedRecord else it })
            writeCatalog(context, updated)
            mutableModels.value = updated.models.sortedByDescending { it.installedAt }
            updatedRecord
        }
    }

    private fun validateArtifact(file: File, format: LocalModelFormat) {
        require(file.isFile && file.length() > 0) { "模型文件为空或不存在" }
        val prefix = ByteArray(minOf(file.length(), 512L).toInt())
        FileInputStream(file).use { input -> require(input.read(prefix) > 0) { "模型文件头不完整" } }
        // 模型库登录页/错误页常被直接存成 .gguf/.litertlm，扩展名对了内容却是 HTML。
        require(!looksLikeTextPayload(prefix)) { "文件内容是网页或文本，而不是模型二进制" }
        if (format == LocalModelFormat.GGUF) {
            require(prefix.size >= 4 && String(prefix, 0, 4, Charsets.US_ASCII) == "GGUF") {
                "文件扩展名为 GGUF，但文件头无效"
            }
        }
        if (format == LocalModelFormat.LITERT_LM) {
            require(prefix.size >= 8 && String(prefix, 0, 8, Charsets.US_ASCII) == "LITERTLM") {
                "文件扩展名为 LiteRT-LM，但文件头无效"
            }
        }
        if (format == LocalModelFormat.TASK) {
            require(prefix.size >= 2 && prefix[0] == 0x50.toByte() && prefix[1] == 0x4B.toByte()) {
                "TASK 模型包不是有效 ZIP 容器"
            }
        }
    }

    private fun copyLimited(input: InputStream, target: File, maxBytes: Long): Long {
        target.parentFile?.mkdirs()
        return copyLimitedToStream(input, target, maxBytes)
    }

    private fun copyLimitedToStream(input: InputStream, target: File, maxBytes: Long): Long {
        require(maxBytes >= 0) { "MNN 模型超过 8 GiB 安全上限" }
        var written = 0L
        FileOutputStream(target).use { fileOutput ->
            BufferedOutputStream(fileOutput).use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    written += read
                    if (written > maxBytes) throw LocalModelException("模型文件超过 8 GiB 安全上限")
                    output.write(buffer, 0, read)
                }
                output.flush()
                fileOutput.fd.sync()
            }
        }
        return written
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        // 部分 SAF 提供方会抛异常或缺少 DISPLAY_NAME 列，这里统一降级为“未知文件名”。
        return runCatching {
            var cursor: Cursor? = null
            try {
                cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                if (cursor?.moveToFirst() == true && cursor.columnCount > 0) cursor.getString(0) else null
            } finally {
                cursor?.close()
            }
        }.getOrNull()
    }

    private fun readCatalog(context: Context): LocalModelCatalog {
        val file = dataDirectory(context).resolve(INDEX_FILE)
        if (!file.isFile) return LocalModelCatalog()
        val catalog = runCatching { json.decodeFromString<LocalModelCatalog>(file.readText()) }
            .getOrElse {
                val backup = dataDirectory(context).resolve("$INDEX_FILE.corrupt")
                runCatching { file.copyTo(backup, overwrite = true) }
                file.delete()
                LocalModelCatalog()
            }
        val normalized = catalog.copy(
            version = 2,
            models = catalog.models.map(::normalizePresetMetadata),
        )
        if (normalized != catalog) writeCatalog(context, normalized)
        return normalized
    }

    /**
     * Older catalogs could reuse an existing file and keep the default text-only modality
     * metadata. That made speech packages appear in the chat model selector and sent them to the
     * LLM loader. Repair known artifacts from their immutable preset identity on every catalog
     * read; custom imports remain untouched.
     */
    private fun normalizePresetMetadata(record: LocalModelRecord): LocalModelRecord {
        val preset = presetForRecord(record) ?: return record
        return record.copy(
            sourceUrl = record.sourceUrl?.takeIf(String::isNotBlank) ?: preset.url,
            license = record.license?.takeIf(String::isNotBlank) ?: preset.license.takeIf(String::isNotBlank),
            inputModalities = preset.inputModalities.distinct(),
            outputModalities = preset.outputModalities.distinct(),
        )
    }

    internal fun presetForRecord(record: LocalModelRecord): LocalModelPreset? {
        record.sourceUrl?.let(::presetFor)?.let { return it }
        if (!record.sha256.isBlank()) {
            LocalModelPreset.all.firstOrNull { preset ->
                preset.expectedSha256?.equals(record.sha256, ignoreCase = true) == true
            }?.let { return it }
        }
        val identities = buildSet {
            add(record.id)
            add(record.fileName.substringAfter('-', record.fileName))
            add(record.fileName)
        }.filter(String::isNotBlank)
        return LocalModelPreset.all.firstOrNull { preset ->
            val artifactName = preset.url.substringBefore('?').substringAfterLast('/')
            identities.any { identity ->
                identity.equals(artifactName, ignoreCase = true) ||
                    identity.equals(preset.id, ignoreCase = true) ||
                    identity.endsWith(artifactName, ignoreCase = true) ||
                    identity.equals(preset.displayName, ignoreCase = true)
            }
        }
    }

    private fun writeCatalog(context: Context, catalog: LocalModelCatalog) {
        val dir = dataDirectory(context)
        dir.mkdirs()
        val target = dir.resolve(INDEX_FILE)
        val temp = dir.resolve("$INDEX_FILE.tmp")
        temp.writeText(json.encodeToString(catalog), Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    private fun dataDirectory(context: Context): File = context.filesDir.resolve("local_models").also { it.mkdirs() }
    private fun modelDirectory(context: Context): File = dataDirectory(context).resolve(MODELS_DIR).also { it.mkdirs() }

    /** 内置运行时加载模型需要真实文件路径；目录拼接只保留在仓库内部。 */
    internal fun artifactFile(context: Context, record: LocalModelRecord): File =
        modelDirectory(context).resolve(record.fileName)

    private fun downloadDirectory(context: Context): File = dataDirectory(context).resolve(DOWNLOADS_DIR).also { it.mkdirs() }
    private fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun requireContext(): Context = requireNotNull(applicationContext) { "LocalModelRepository.init(context) 未调用" }

    private fun readDownloadState(context: Context): LocalModelDownloadState {
        val p = preferences(context)
        val stage = p.getString("download_stage", null)
            ?.let { raw -> LocalModelDownloadStage.entries.firstOrNull { it.name == raw } }
            ?: return LocalModelDownloadState()
        return LocalModelDownloadState(
            id = p.getString("download_id", null),
            displayName = p.getString("download_name", "").orEmpty(),
            stage = stage,
            bytesDownloaded = p.getLong("download_bytes", 0),
            totalBytes = p.getLong("download_total", -1).takeIf { it > 0 },
            message = p.getString("download_message", null),
            workName = p.getString("download_work", null),
            sourceUrl = p.getString("download_source_url", null),
        )
    }

    private fun writeDownloadState(context: Context, state: LocalModelDownloadState) {
        mutableDownload.value = state
        preferences(context).edit().apply {
            if (state.id == null && state.stage == null) {
                // This preference file also stores the editable default download URL. Removing
                // the whole file would silently reset a user's custom mirror after clearing a
                // finished download notice.
                remove("download_stage")
                remove("download_id")
                remove("download_name")
                remove("download_bytes")
                remove("download_total")
                remove("download_message")
                remove("download_work")
                remove("download_source_url")
                remove("download_updated_at")
            } else {
                state.stage?.let { putString("download_stage", it.name) } ?: remove("download_stage")
                state.id?.let { putString("download_id", it) } ?: remove("download_id")
                putString("download_name", state.displayName)
                putLong("download_bytes", state.bytesDownloaded)
                putLong("download_total", state.totalBytes ?: -1)
                state.message?.let { putString("download_message", it) } ?: remove("download_message")
                state.workName?.let { putString("download_work", it) } ?: remove("download_work")
                state.sourceUrl?.let { putString("download_source_url", it) } ?: remove("download_source_url")
                putLong("download_updated_at", System.currentTimeMillis())
            }
        }.apply()
    }

    /**
     * Worker 的进度回调。已取消/已完成的任务不会被迟到的进度覆盖，避免状态卡在“下载中”。
     */
    internal fun updateDownloadProgress(
        context: Context,
        id: String,
        displayName: String,
        stage: LocalModelDownloadStage,
        bytesDownloaded: Long,
        totalBytes: Long?,
        message: String? = null,
        sourceUrl: String? = mutableDownload.value.sourceUrl,
    ) {
        val current = mutableDownload.value
        if (current.id != null && current.id != id) return
        if (current.stage == LocalModelDownloadStage.CANCELLED ||
            current.stage == LocalModelDownloadStage.COMPLETED
        ) {
            return
        }
        writeDownloadState(
            context,
            LocalModelDownloadState(
                id = id,
                displayName = displayName,
                stage = stage,
                bytesDownloaded = bytesDownloaded,
                totalBytes = totalBytes,
                message = message,
                workName = DOWNLOAD_WORK_NAME,
                sourceUrl = sourceUrl,
            ),
        )
    }

    internal fun completeDownload(context: Context, state: LocalModelDownloadState) =
        writeDownloadState(context, state.copy(stage = LocalModelDownloadStage.COMPLETED))
}

internal object LocalModelFiles {
    private const val MAX_FILE_NAME_LENGTH = 180

    fun formatFromUrl(value: String): LocalModelFormat? {
        val path = runCatching { Uri.parse(value).lastPathSegment }.getOrNull().orEmpty()
        return LocalModelFormat.fromFileName(path)
    }

    fun shortHash(value: String, length: Int): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(length.coerceIn(4, 64))

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KiB", "MiB", "GiB", "TiB")
        var value = bytes.toDouble() / 1024
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return String.format(Locale.US, "%.1f %s", value, units[index])
    }

    fun safeFileName(value: String): String {
        val base = value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('.')
        require(base.isNotBlank()) { "文件名无效" }
        if (base.length <= MAX_FILE_NAME_LENGTH) return base
        // 截断时保留扩展名，否则超长文件名会让模型格式判断失效。
        val dot = base.lastIndexOf('.')
        val extension = if (dot > 0) base.substring(dot).take(16) else ""
        return base.take(MAX_FILE_NAME_LENGTH - extension.length) + extension
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

internal class LocalModelDownloadWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    private val id = inputData.getString("id") ?: ""
    private val name = inputData.getString("name") ?: "本地模型"

    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo(null)

    override suspend fun doWork(): Result {
        val primaryUrl = inputData.getString("url") ?: return Result.failure()
        val sources = (listOf(primaryUrl) + inputData.getStringArray("fallbackUrls").orEmpty())
            .distinct()
            .filter(String::isNotBlank)
        LocalModelRepository.init(applicationContext)
        val expectedSize = inputData.getLong("expectedSize", 0L).takeIf { it > 0 }
        LocalModelRepository.updateDownloadProgress(
            context = applicationContext,
            id = id,
            displayName = name,
            stage = LocalModelDownloadStage.DOWNLOADING,
            bytesDownloaded = 0L,
            totalBytes = expectedSize,
            sourceUrl = sources.firstOrNull(),
        )
        return try {
            foregroundSafely(null)
            var record: LocalModelRecord? = null
            var usedUrl = sources.firstOrNull().orEmpty()
            val sourceFailures = mutableListOf<Exception>()
            for (source in sources) {
                try {
                    record = LocalModelRepository.performDownload(
                        context = applicationContext,
                        id = id,
                        displayName = name,
                        sourceUrl = source,
                        expectedSize = expectedSize,
                        expectedSha = inputData.getString("expectedSha")?.takeIf { it.isNotBlank() },
                        license = inputData.getString("license")?.takeIf { it.isNotBlank() },
                        inputModalities = inputData.getString("inputModalities")
                            ?.split('|')?.filter(String::isNotBlank) ?: listOf(Model.TEXT_MODALITY),
                        outputModalities = inputData.getString("outputModalities")
                            ?.split('|')?.filter(String::isNotBlank) ?: listOf(Model.TEXT_MODALITY),
                        onProgress = { bytes, total ->
                            runCatching { setProgress(workDataOf("bytes" to bytes, "total" to (total ?: -1L))) }
                            val stage = if (total != null && bytes >= total) {
                                LocalModelDownloadStage.VERIFYING
                            } else {
                                LocalModelDownloadStage.DOWNLOADING
                            }
                            LocalModelRepository.updateDownloadProgress(
                                context = applicationContext,
                                id = id,
                                displayName = name,
                                stage = stage,
                                bytesDownloaded = bytes,
                                totalBytes = total,
                                sourceUrl = source,
                            )
                            foregroundSafely(
                                total?.takeIf { it > 0 }?.let { (bytes.toFloat() / it).coerceIn(0f, 1f) },
                            )
                        },
                    )
                    usedUrl = source
                    break
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    sourceFailures += failure
                    if (source != sources.last()) {
                        LocalModelRepository.updateDownloadProgress(
                            context = applicationContext,
                            id = id,
                            displayName = name,
                            stage = LocalModelDownloadStage.QUEUED,
                            bytesDownloaded = 0L,
                            totalBytes = expectedSize,
                            message = "当前下载源不可用，正在切换备用源",
                            sourceUrl = sources.getOrNull(sources.indexOf(source) + 1),
                        )
                    }
                }
            }
            val completed = record ?: throw sourceFailures.lastOrNull()
                ?.let { last ->
                    LocalModelException(
                        "所有下载源均失败：${last.message.orEmpty().take(160)}",
                        retryable = sourceFailures.any { it is LocalModelException && it.retryable },
                        cause = last,
                    )
                }
                ?: LocalModelException("没有可用的模型下载源", retryable = false)
            LocalModelRepository.completeDownload(
                applicationContext,
                LocalModelDownloadState(
                    id = id,
                    displayName = completed.displayName,
                    stage = LocalModelDownloadStage.COMPLETED,
                    bytesDownloaded = completed.sizeBytes,
                    totalBytes = completed.sizeBytes,
                    workName = LocalModelRepository.DOWNLOAD_WORK_NAME,
                    sourceUrl = usedUrl,
                ),
            )
            // Connecting metadata must not invalidate an already-completed download. The model
            // selector is populated immediately; engine loading is user-visible and warm-up is
            // performed by the local-model screen so a 2.5 GB model does not keep dataSync busy.
            runCatching { LocalModelRepository.autoActivateBuiltInIfConfigured(completed.id) }
            Result.success(workDataOf("recordId" to completed.id))
        } catch (cancelled: CancellationException) {
            LocalModelRepository.updateDownloadProgress(
                context = applicationContext,
                id = id,
                displayName = name,
                stage = LocalModelDownloadStage.CANCELLED,
                bytesDownloaded = LocalModelRepository.download.value.bytesDownloaded,
                totalBytes = LocalModelRepository.download.value.totalBytes,
                message = "已取消，临时文件保留供续传",
            )
            throw cancelled
        } catch (failure: Exception) {
            val retryable = failure is LocalModelException && failure.retryable
            val willRetry = retryable && runAttemptCount < MAX_RETRIES
            val current = LocalModelRepository.download.value
            LocalModelRepository.updateDownloadProgress(
                context = applicationContext,
                id = id,
                displayName = name,
                stage = if (willRetry) LocalModelDownloadStage.QUEUED else LocalModelDownloadStage.FAILED,
                bytesDownloaded = current.bytesDownloaded,
                totalBytes = current.totalBytes,
                message = if (willRetry) {
                    "下载中断，将在后台自动重试：${failure.message.orEmpty().take(120)}"
                } else {
                    failure.message ?: "下载失败"
                },
            )
            if (willRetry) Result.retry() else Result.failure()
        }
    }

    private var lastForegroundAt = 0L

    /**
     * 前台服务只是“保活”手段：Android 12+ 后台启动受限、Android 14+ 还要求 manifest 声明类型，
     * 任何一步失败都不应该让下载本身失败。
     */
    private suspend fun foregroundSafely(progress: Float?) {
        val now = android.os.SystemClock.elapsedRealtime()
        // 进度回调每 4 MiB 一次，这里限制通知刷新频率，避免刷爆 NotificationManager。
        if (now - lastForegroundAt < 1_000) return
        lastForegroundAt = now
        runCatching { setForeground(createForegroundInfo(progress)) }
    }

    private fun createForegroundInfo(progress: Float?): ForegroundInfo {
        runCatching {
            applicationContext.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    applicationContext.getString(R.string.local_models_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val intent = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
        val pending = intent?.let {
            PendingIntent.getActivity(
                applicationContext,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(applicationContext.getString(R.string.local_models_download_running, name))
            .setContentText(applicationContext.getString(R.string.local_models_download_foreground))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(pending)
        if (progress != null) {
            val percent = (progress * 100).toInt().coerceIn(0, 100)
            builder.setProgress(100, percent, false)
            builder.setContentText(
                "$percent% · " + applicationContext.getString(R.string.local_models_download_foreground),
            )
        } else {
            builder.setProgress(0, 0, true)
        }
        return ForegroundInfo(
            NOTIFICATION_ID,
            builder.build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        private const val CHANNEL_ID = "local-model-downloads"
        private const val NOTIFICATION_ID = 8140
        private const val MAX_RETRIES = 3
    }
}
