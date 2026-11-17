package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class AsrProvider { QWEN_REALTIME, QWEN_FLASH, QWEN_FILE, DOUBAO, LOCAL, AZURE, BAIDU, IFLYTEK, TENCENT, XIAOMI, DEEPSEEK }

internal fun AsrProvider.supportsLiveRecognition(): Boolean =
    this == AsrProvider.QWEN_REALTIME || this == AsrProvider.DOUBAO || this == AsrProvider.LOCAL || this == AsrProvider.IFLYTEK

@Serializable
enum class TtsProvider { NONE, QWEN, DOUBAO, GEMINI, MINIMAX, LOCAL, AZURE, BAIDU, IFLYTEK, TENCENT, XIAOMI, DEEPSEEK }

@Serializable
data class DeepSeekSpeechConfig(
    val baseUrl: String = "https://api.deepseek.com",
    val voice: String = "stella",
)

@Serializable
data class XiaomiMiMoSpeechConfig(
    val language: String = "auto",
    val voice: String = "mimo_default",
)

@Serializable
data class AzureSpeechConfig(
    val region: String = "eastasia",
    val locale: String = "zh-CN",
    val voice: String = "zh-CN-XiaoxiaoNeural",
)

@Serializable
data class BaiduSpeechConfig(
    val devPid: Int = 1537,
    val voice: Int = 0,
    val speed: Int = 5,
    val pitch: Int = 5,
    val volume: Int = 5,
)

@Serializable
data class IflytekSpeechConfig(
    val appId: String = "",
    val language: String = "zh_cn",
    val accent: String = "mandarin",
    val voice: String = "xiaoyan",
    val speed: Int = 50,
)

@Serializable
data class TencentSpeechConfig(
    val region: String = "ap-shanghai",
    val engine: String = "16k_zh",
    val voice: Int = 1001,
    val speed: Float = 0f,
    val volume: Float = 0f,
)

@Serializable
enum class SpeechRegion { BEIJING, SINGAPORE }

@Serializable
data class QwenSpeechConfig(
    val region: SpeechRegion = SpeechRegion.BEIJING,
    val baseUrl: String = "",
) {
    fun endpoint(): String = baseUrl.trim().trimEnd('/').ifEmpty {
        if (region == SpeechRegion.BEIJING) "https://dashscope.aliyuncs.com"
        else "https://dashscope-intl.aliyuncs.com"
    }
}

@Serializable
data class DoubaoSpeechConfig(
    val baseUrl: String = "https://openspeech.bytedance.com",
    val legacyAuth: Boolean = false,
    val appId: String = "",
    val resourceId: String = "",
)

@Serializable
enum class GeminiTtsModel(val apiId: String) {
    FLASH("gemini-3.8-flash-tts"),
    FLASH_LITE("gemini-3.8-flash-lite-tts"),
}

@Serializable
data class GeminiTtsConfig(
    val baseUrl: String = "https://generativelanguage.googleapis.com",
    val model: GeminiTtsModel = GeminiTtsModel.FLASH,
    val voice: String = "Kore",
    val style: String = "",
)

@Serializable
enum class MiniMaxTtsModel(val apiId: String) {
    TURBO("speech-2.8-turbo"),
    HD("speech-2.8-hd"),
}

@Serializable
data class MiniMaxTtsConfig(
    val baseUrl: String = "https://api.minimax.cn",
    val model: MiniMaxTtsModel = MiniMaxTtsModel.TURBO,
    val voice: String = "female-chengshu",
    val speed: Float = 1f,
)

@Serializable
data class SpeechOssConfig(
    val region: String = "cn-beijing",
    val endpoint: String = "https://oss-cn-beijing.aliyuncs.com",
    val bucket: String = "",
    val prefix: String = "eta-speech/",
)

@Serializable
data class SpeechSettings(
    val asr: AsrProvider = AsrProvider.QWEN_REALTIME,
    val tts: TtsProvider = TtsProvider.NONE,
    val autoSpeak: Boolean = false,
    /** 助手浮窗播报结束后继续监听下一轮；仅用于用户主动发起的语音会话。 */
    val continuousConversation: Boolean = true,
    val duplexConversation: Boolean = false,
    /** 聊天页回答完成后自动朗读最终回答；与助手浮窗的自动播报分开保存。 */
    val chatAutoSpeak: Boolean = false,
    val language: String = "",
    val qwenAsr: QwenSpeechConfig = QwenSpeechConfig(),
    val qwenTts: QwenSpeechConfig = QwenSpeechConfig(),
    val doubaoAsr: DoubaoSpeechConfig = DoubaoSpeechConfig(),
    val doubaoTts: DoubaoSpeechConfig = DoubaoSpeechConfig(),
    val qwenVoice: String = "Cherry",
    val doubaoVoice: String = "zh_female_vv_uranus_bigtts",
    val geminiTts: GeminiTtsConfig = GeminiTtsConfig(),
    val minimaxTts: MiniMaxTtsConfig = MiniMaxTtsConfig(),
    val azureAsr: AzureSpeechConfig = AzureSpeechConfig(),
    val azureTts: AzureSpeechConfig = AzureSpeechConfig(),
    val baiduAsr: BaiduSpeechConfig = BaiduSpeechConfig(),
    val baiduTts: BaiduSpeechConfig = BaiduSpeechConfig(),
    val iflytekAsr: IflytekSpeechConfig = IflytekSpeechConfig(),
    val iflytekTts: IflytekSpeechConfig = IflytekSpeechConfig(),
    val tencentAsr: TencentSpeechConfig = TencentSpeechConfig(),
    val tencentTts: TencentSpeechConfig = TencentSpeechConfig(),
    val xiaomiAsr: XiaomiMiMoSpeechConfig = XiaomiMiMoSpeechConfig(),
    val xiaomiTts: XiaomiMiMoSpeechConfig = XiaomiMiMoSpeechConfig(),
    val deepSeekAsr: DeepSeekSpeechConfig = DeepSeekSpeechConfig(),
    val deepSeekTts: DeepSeekSpeechConfig = DeepSeekSpeechConfig(),
    val oss: SpeechOssConfig = SpeechOssConfig(),
    /** Installed LocalModelRecord ids. Binding an artifact is independent from chat model selection. */
    val localAsrModelId: String = "",
    val localTtsModelId: String = "",
    val localTtsVoiceId: String = "zf_xiaoxiao",
    val localTtsLocale: String = "zh-CN",
    val localTtsRate: Float = 1f,
    /** 助手浮窗停顿多久后自动发送；0 表示只在用户点击完成或松手时发送。 */
    val autoSendSilenceMs: Int = DEFAULT_AUTO_SEND_SILENCE_MS,
    /** 识别结果交付前使用当前对话模型保守纠错。 */
    val refineTranscript: Boolean = false,
    /** 只控制 Eta 自身的语音输入，关闭时保留服务配置以便再次开启。 */
    val recognitionEnabled: Boolean = true,
) {
    companion object {
        const val DEFAULT_AUTO_SEND_SILENCE_MS = 2_000
        val AUTO_SEND_SILENCE_OPTIONS = listOf(1_000, 1_500, 2_000, 3_000, 0)
    }
}

internal enum class SpeechCredentialField {
    QWEN_ASR, QWEN_TTS, DOUBAO_ASR, DOUBAO_TTS, GEMINI_TTS, MINIMAX_TTS, OSS_KEY_ID, OSS_KEY_SECRET,
    AZURE_ASR, AZURE_TTS,
    BAIDU_ASR_API_KEY, BAIDU_ASR_SECRET_KEY, BAIDU_TTS_API_KEY, BAIDU_TTS_SECRET_KEY,
    IFLYTEK_ASR_API_KEY, IFLYTEK_ASR_API_SECRET, IFLYTEK_TTS_API_KEY, IFLYTEK_TTS_API_SECRET,
    TENCENT_ASR_SECRET_ID, TENCENT_ASR_SECRET_KEY, TENCENT_TTS_SECRET_ID, TENCENT_TTS_SECRET_KEY,
    XIAOMI_ASR, XIAOMI_TTS,
    DEEPSEEK_ASR, DEEPSEEK_TTS,
}

/** 凭据不属于 Settings，避免被普通配置导出或调试输出带出。 */
@Serializable
class SpeechCredentials(
    val qwenAsr: String = "",
    val qwenTts: String = "",
    val doubaoAsr: String = "",
    val doubaoTts: String = "",
    val ossAccessKeyId: String = "",
    val ossAccessKeySecret: String = "",
    val geminiTts: String = "",
    val minimaxTts: String = "",
    val azureAsr: String = "",
    val azureTts: String = "",
    val baiduAsrApiKey: String = "",
    val baiduAsrSecretKey: String = "",
    val baiduTtsApiKey: String = "",
    val baiduTtsSecretKey: String = "",
    val iflytekAsrApiKey: String = "",
    val iflytekAsrApiSecret: String = "",
    val iflytekTtsApiKey: String = "",
    val iflytekTtsApiSecret: String = "",
    val tencentAsrSecretId: String = "",
    val tencentAsrSecretKey: String = "",
    val tencentTtsSecretId: String = "",
    val tencentTtsSecretKey: String = "",
    val xiaomiAsr: String = "",
    val xiaomiTts: String = "",
    val deepSeekAsr: String = "",
    val deepSeekTts: String = "",
) {
    internal fun withValue(field: SpeechCredentialField, value: String): SpeechCredentials = SpeechCredentials(
        qwenAsr = if (field == SpeechCredentialField.QWEN_ASR) value else qwenAsr,
        qwenTts = if (field == SpeechCredentialField.QWEN_TTS) value else qwenTts,
        doubaoAsr = if (field == SpeechCredentialField.DOUBAO_ASR) value else doubaoAsr,
        doubaoTts = if (field == SpeechCredentialField.DOUBAO_TTS) value else doubaoTts,
        ossAccessKeyId = if (field == SpeechCredentialField.OSS_KEY_ID) value else ossAccessKeyId,
        ossAccessKeySecret = if (field == SpeechCredentialField.OSS_KEY_SECRET) value else ossAccessKeySecret,
        geminiTts = if (field == SpeechCredentialField.GEMINI_TTS) value else geminiTts,
        minimaxTts = if (field == SpeechCredentialField.MINIMAX_TTS) value else minimaxTts,
        azureAsr = if (field == SpeechCredentialField.AZURE_ASR) value else azureAsr,
        azureTts = if (field == SpeechCredentialField.AZURE_TTS) value else azureTts,
        baiduAsrApiKey = if (field == SpeechCredentialField.BAIDU_ASR_API_KEY) value else baiduAsrApiKey,
        baiduAsrSecretKey = if (field == SpeechCredentialField.BAIDU_ASR_SECRET_KEY) value else baiduAsrSecretKey,
        baiduTtsApiKey = if (field == SpeechCredentialField.BAIDU_TTS_API_KEY) value else baiduTtsApiKey,
        baiduTtsSecretKey = if (field == SpeechCredentialField.BAIDU_TTS_SECRET_KEY) value else baiduTtsSecretKey,
        iflytekAsrApiKey = if (field == SpeechCredentialField.IFLYTEK_ASR_API_KEY) value else iflytekAsrApiKey,
        iflytekAsrApiSecret = if (field == SpeechCredentialField.IFLYTEK_ASR_API_SECRET) value else iflytekAsrApiSecret,
        iflytekTtsApiKey = if (field == SpeechCredentialField.IFLYTEK_TTS_API_KEY) value else iflytekTtsApiKey,
        iflytekTtsApiSecret = if (field == SpeechCredentialField.IFLYTEK_TTS_API_SECRET) value else iflytekTtsApiSecret,
        tencentAsrSecretId = if (field == SpeechCredentialField.TENCENT_ASR_SECRET_ID) value else tencentAsrSecretId,
        tencentAsrSecretKey = if (field == SpeechCredentialField.TENCENT_ASR_SECRET_KEY) value else tencentAsrSecretKey,
        tencentTtsSecretId = if (field == SpeechCredentialField.TENCENT_TTS_SECRET_ID) value else tencentTtsSecretId,
        tencentTtsSecretKey = if (field == SpeechCredentialField.TENCENT_TTS_SECRET_KEY) value else tencentTtsSecretKey,
        xiaomiAsr = if (field == SpeechCredentialField.XIAOMI_ASR) value else xiaomiAsr,
        xiaomiTts = if (field == SpeechCredentialField.XIAOMI_TTS) value else xiaomiTts,
        deepSeekAsr = if (field == SpeechCredentialField.DEEPSEEK_ASR) value else deepSeekAsr,
        deepSeekTts = if (field == SpeechCredentialField.DEEPSEEK_TTS) value else deepSeekTts,
    )

    override fun toString(): String = "SpeechCredentials(redacted)"
}
