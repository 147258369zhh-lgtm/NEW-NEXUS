package io.github.mangi.eta.ui.voice

import androidx.annotation.StringRes
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.data.repository.LocalModelRecord

internal enum class SpeechLocalVoiceGender { FEMALE, MALE }

internal data class SpeechLocalVoice(
    val id: String,
    @param:StringRes val nameRes: Int,
    val gender: SpeechLocalVoiceGender,
    @param:StringRes val toneRes: Int,
    val grade: String,
    val recommended: Boolean = false,
)

internal enum class SpeechLocalModelFamily { KOKORO, MODEL_DEFAULT }

/** Kokoro 82M 内置的 8 个普通话音色；未知本地模型使用模型自带默认声线。 */
internal object SpeechLocalVoiceCatalog {
    val chinese = listOf(
        SpeechLocalVoice(
            id = "zf_xiaoxiao",
            nameRes = R.string.speech_voice_local_xiaoxiao,
            gender = SpeechLocalVoiceGender.FEMALE,
            toneRes = R.string.speech_local_tone_standard_news,
            grade = "D",
            recommended = true,
        ),
        SpeechLocalVoice(
            id = "zf_xiaoni",
            nameRes = R.string.speech_voice_local_xiaoni,
            gender = SpeechLocalVoiceGender.FEMALE,
            toneRes = R.string.speech_local_tone_soft_friendly,
            grade = "D",
        ),
        SpeechLocalVoice(
            id = "zf_xiaobei",
            nameRes = R.string.speech_voice_local_xiaobei,
            gender = SpeechLocalVoiceGender.FEMALE,
            toneRes = R.string.speech_local_tone_clear_daily,
            grade = "D",
        ),
        SpeechLocalVoice(
            id = "zf_xiaoyi",
            nameRes = R.string.speech_voice_local_xiaoyi,
            gender = SpeechLocalVoiceGender.FEMALE,
            toneRes = R.string.speech_local_tone_lively_girl,
            grade = "D",
        ),
        SpeechLocalVoice(
            id = "zm_yunjian",
            nameRes = R.string.speech_voice_local_yunjian,
            gender = SpeechLocalVoiceGender.MALE,
            toneRes = R.string.speech_local_tone_steady_male,
            grade = "D",
        ),
        SpeechLocalVoice(
            id = "zm_yunxi",
            nameRes = R.string.speech_voice_local_yunxi,
            gender = SpeechLocalVoiceGender.MALE,
            toneRes = R.string.speech_local_tone_clear_young,
            grade = "D",
        ),
        SpeechLocalVoice(
            id = "zm_yunxia",
            nameRes = R.string.speech_voice_local_yunxia,
            gender = SpeechLocalVoiceGender.MALE,
            toneRes = R.string.speech_local_tone_warm_male,
            grade = "D",
        ),
        SpeechLocalVoice(
            id = "zm_yunyang",
            nameRes = R.string.speech_voice_local_yunyang,
            gender = SpeechLocalVoiceGender.MALE,
            toneRes = R.string.speech_local_tone_bright_news,
            grade = "D",
        ),
    )

    fun family(record: LocalModelRecord?): SpeechLocalModelFamily =
        if (record?.id?.contains("kokoro", ignoreCase = true) == true) {
            SpeechLocalModelFamily.KOKORO
        } else {
            SpeechLocalModelFamily.MODEL_DEFAULT
        }

    fun voice(voiceId: String): SpeechLocalVoice? = chinese.firstOrNull { it.id == voiceId }

    /** Kokoro 使用已验证目录；其他语音包保留其自身声线，运行时再按 listVoices 校验。 */
    fun normalize(settings: SpeechSettings, record: LocalModelRecord?): SpeechSettings {
        if (settings.tts != TtsProvider.LOCAL || record == null) return settings
        if (family(record) != SpeechLocalModelFamily.KOKORO) {
            return settings
        }
        if (voice(settings.localTtsVoiceId) == null) {
            return settings.copy(
                localTtsVoiceId = chinese.first().id,
                localTtsLocale = "zh-CN",
            )
        }
        return if (settings.localTtsLocale == "zh-CN") {
            settings
        } else {
            settings.copy(localTtsLocale = "zh-CN")
        }
    }
}
