package io.github.mangi.eta.agent.model

import android.content.Context
import android.os.Build
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Rules shared by the importer, backend picker, and LiteRT-LM loader. */
internal object NpuModelTargeting {
    private val baseSoc = Regex("^(?:sm|qcs|mt)(\\d{4})", RegexOption.IGNORE_CASE)
    private val targetSoc = Regex(
        "(?:^|[_.-])((?:sm|qcs|mt)\\d{4})[a-z]{0,2}(?:[_.-]|$)",
        RegexOption.IGNORE_CASE,
    )
    private val fixedKv = Regex("(?:^|[_.-])ekv(\\d{3,6})(?:[_.-]|$)", RegexOption.IGNORE_CASE)

    fun baseSoc(raw: String?): String? {
        val value = raw.orEmpty().trim()
        val match = baseSoc.find(value) ?: return null
        return match.value.lowercase()
    }

    fun targetOf(fileName: String): String? =
        targetSoc.find(fileName)?.groupValues?.get(1)?.lowercase()

    fun kvCapacity(fileName: String): Int? =
        fixedKv.find(fileName)?.groupValues?.get(1)?.toIntOrNull()

    fun historyBudget(fileName: String): Int = kvCapacity(fileName) ?: when {
        fileName.contains("gemma-4", ignoreCase = true) || fileName.contains("gemma4", ignoreCase = true) -> 4096
        else -> 1024
    }

    fun matches(target: String?, deviceSoc: String?): Boolean {
        val wanted = baseSoc(target) ?: return false
        if (wanted.startsWith("qcs")) return true
        return wanted == baseSoc(deviceSoc)
    }

    fun hexagonArch(manufacturer: String?, model: String?): Int? {
        if (!manufacturer.equals("QTI", ignoreCase = true) &&
            !manufacturer.orEmpty().contains("qualcomm", ignoreCase = true)
        ) return null
        return when (baseSoc(model)) {
            "sm8850" -> 81
            "sm8750" -> 79
            "sm8650" -> 75
            "sm8550" -> 73
            "sm8450", "sm8475" -> 69
            else -> null
        }
    }
}

internal sealed interface QualcommNpuRuntimeState {
    data class Unsupported(val reason: String) : QualcommNpuRuntimeState
    data object NotInstalled : QualcommNpuRuntimeState
    data object Downloading : QualcommNpuRuntimeState
    data class Verifying(val progress: Float?) : QualcommNpuRuntimeState
    data class Installed(val directory: String, val arch: Int) : QualcommNpuRuntimeState
    data class NeedsRepair(val reason: String) : QualcommNpuRuntimeState
    data class Failed(val reason: String) : QualcommNpuRuntimeState
}

/** Installs the licensed QAIRT libraries on demand and pairs them with Google's dispatch ABI. */
internal object QualcommNpuRuntimeManager {
    private const val QNN_VERSION = "2.50.0"
    private const val QNN_AAR_BYTES = 71_270_746L
    private const val QNN_AAR_SHA256 =
        "b507656e4031d8aa6a7ab0fecf725b941096737ad7440ad7ea288f7c9cc9d743"
    private const val MARKER = ".installed"
    private const val DISPATCH = "libLiteRtDispatch_Qualcomm.so"
    private const val COMPILER = "libLiteRtCompilerPlugin_Qualcomm.so"
    private val qnnUrls = listOf(
        "https://repo1.maven.org/maven2/com/qualcomm/qti/qnn-runtime/$QNN_VERSION/qnn-runtime-$QNN_VERSION.aar",
        "https://maven.aliyun.com/repository/central/com/qualcomm/qti/qnn-runtime/$QNN_VERSION/qnn-runtime-$QNN_VERSION.aar",
    )
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.MINUTES)
        .build()
    private val mutex = Mutex()
    private var applicationContext: Context? = null
    private val mutableState = MutableStateFlow<QualcommNpuRuntimeState>(
        QualcommNpuRuntimeState.Unsupported("正在检测 NPU"),
    )

    val state: StateFlow<QualcommNpuRuntimeState> = mutableState.asStateFlow()

    val arch: Int?
        get() = NpuModelTargeting.hexagonArch(Build.SOC_MANUFACTURER, Build.SOC_MODEL)

    val deviceSoc: String?
        get() = NpuModelTargeting.baseSoc(Build.SOC_MODEL)

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
        refresh()
    }

    fun refresh(): QualcommNpuRuntimeState = inspect().also { mutableState.value = it }

    fun runtimeDirectory(context: Context): File = File(
        context.filesDir,
        "npu/qnn-$QNN_VERSION-v${arch ?: 0}",
    )

    suspend fun ensureInstalled(context: Context): File = mutex.withLock {
        applicationContext = context.applicationContext
        val current = inspect()
        mutableState.value = current
        when (current) {
            is QualcommNpuRuntimeState.Installed -> File(current.directory)
            is QualcommNpuRuntimeState.Unsupported -> throw IllegalStateException(current.reason)
            is QualcommNpuRuntimeState.Failed -> throw IllegalStateException(current.reason)
            else -> installLocked(context.applicationContext)
        }
    }

    private suspend fun installLocked(context: Context): File = withContext(Dispatchers.IO) {
        val arch = arch ?: throw IllegalStateException("此设备没有可用的 Qualcomm NPU")
        val target = runtimeDirectory(context)
        val temporary = File(target.path + ".tmp").apply {
            deleteRecursively()
            mkdirs()
        }
        val download = File(context.cacheDir, "npu-dl/qnn-runtime-$QNN_VERSION.aar").apply {
            parentFile?.mkdirs()
        }
        try {
            mutableState.value = QualcommNpuRuntimeState.Downloading
            if (download.length() != QNN_AAR_BYTES || sha256(download) != QNN_AAR_SHA256) {
                download.delete()
                downloadWithMirrors(download)
            }
            mutableState.value = QualcommNpuRuntimeState.Verifying(null)
            if (download.length() != QNN_AAR_BYTES || sha256(download) != QNN_AAR_SHA256) {
                throw IOException("Qualcomm QNN 运行库校验失败")
            }
            copyPackaged(context, DISPATCH, temporary)
            runCatching { copyPackaged(context, COMPILER, temporary) }
            val qnnFiles = expectedQnn(arch)
            ZipFile(download).use { archive ->
                qnnFiles.forEachIndexed { index, name ->
                    val entry = archive.getEntry("jni/arm64-v8a/$name")
                        ?: throw IOException("QNN 运行库缺少 $name")
                    val output = File(temporary, name)
                    archive.getInputStream(entry).use { input ->
                        output.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
                    }
                    if (output.length() <= 0L) throw IOException("$name 为空")
                    mutableState.value = QualcommNpuRuntimeState.Verifying(
                        (index + 1f) / qnnFiles.size,
                    )
                }
            }
            val installed = (listOf(DISPATCH) + qnnFiles).map { name ->
                val file = File(temporary, name)
                if (!file.isFile || file.length() <= 0L) throw IOException("缺少 $name")
                "file=$name size=${file.length()} sha256=${sha256(file)}"
            }
            File(temporary, MARKER).writeText(
                "litertlm=0.19.1 runtime=2.3.2 qnn=$QNN_VERSION arch=v$arch\n" +
                    installed.joinToString(separator = "\n", postfix = "\n"),
            )
            target.deleteRecursively()
            if (!temporary.renameTo(target)) throw IOException("无法保存 NPU 运行库")
            download.delete()
            mutableState.value = QualcommNpuRuntimeState.Installed(target.path, arch)
            target
        } catch (failure: Throwable) {
            temporary.deleteRecursively()
            val state = QualcommNpuRuntimeState.Failed(
                failure.message ?: failure.javaClass.simpleName,
            )
            mutableState.value = state
            throw failure
        }
    }

    private fun inspect(): QualcommNpuRuntimeState {
        val context = applicationContext
            ?: return QualcommNpuRuntimeState.Unsupported("NPU 运行环境尚未初始化")
        val arch = arch ?: return QualcommNpuRuntimeState.Unsupported(
            "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL} 暂无可用的 Qualcomm LiteRT NPU 运行库",
        )
        val directory = runtimeDirectory(context)
        if (!directory.isDirectory) return QualcommNpuRuntimeState.NotInstalled
        val marker = File(directory, MARKER).takeIf(File::isFile)
            ?: return QualcommNpuRuntimeState.NeedsRepair("运行环境不完整")
        val expected = listOf(DISPATCH) + expectedQnn(arch)
        val missing = expected.filterNot { File(directory, it).let { file -> file.isFile && file.length() > 0L } }
        if (missing.isNotEmpty()) {
            return QualcommNpuRuntimeState.NeedsRepair("缺少 ${missing.joinToString()}")
        }
        val markerText = runCatching { marker.readText() }.getOrDefault("")
        return if (markerText.contains("qnn=$QNN_VERSION") && markerText.contains("arch=v$arch")) {
            QualcommNpuRuntimeState.Installed(directory.path, arch)
        } else {
            QualcommNpuRuntimeState.NeedsRepair("运行环境版本不匹配")
        }
    }

    private fun expectedQnn(arch: Int): List<String> = listOf(
        "libQnnHtp.so",
        "libQnnHtpPrepare.so",
        "libQnnSystem.so",
        "libQnnHtpV${arch}Stub.so",
        "libQnnHtpV${arch}Skel.so",
    )

    private fun copyPackaged(context: Context, name: String, target: File) {
        val source = File(context.applicationInfo.nativeLibraryDir, name)
        if (!source.isFile) throw IOException("安装包缺少 $name")
        source.copyTo(File(target, name), overwrite = true)
    }

    private fun downloadWithMirrors(target: File) {
        var lastFailure: Throwable? = null
        for (url in qnnUrls) {
            try {
                val request = Request.Builder().url(url).get().build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("空响应")
                    val total = body.contentLength().takeIf { it > 0 }
                    val buffer = ByteArray(256 * 1024)
                    var readTotal = 0L
                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                readTotal += count
                                mutableState.value = QualcommNpuRuntimeState.Downloading
                            }
                        }
                    }
                    if (total != null && readTotal != total) {
                        throw IOException("下载不完整：$readTotal/$total")
                    }
                }
                if (target.length() == QNN_AAR_BYTES) return
                throw IOException("运行库大小不符")
            } catch (failure: Throwable) {
                target.delete()
                lastFailure = failure
            }
        }
        throw IOException("QNN 运行库下载失败：${lastFailure?.message.orEmpty()}", lastFailure)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
