package io.github.mangi.eta.agent.voice

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Local voiceprint profile metadata and gate.
 *
 * Audio feature extraction is deliberately supplied by a platform/provider adapter. The store
 * keeps only a bounded quantized feature vector. Each capture must supply compatible features;
 * an enrolled profile by itself is never sufficient to verify a speaker.
 */
internal data class VoiceprintProfile(
    val id: String,
    val featureVector: ByteArray,
    val createdAt: Long,
    val modelIdentity: String = "legacy-mfcc-v1",
)

internal enum class VoiceprintVerification {
    DISABLED,
    VERIFIED,
    NO_PROFILE,
    REJECTED,
    UNAVAILABLE,
}

internal class VoiceprintProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("eta_voiceprint", Context.MODE_PRIVATE)

    /** Serialize accepted enrollment, page reads and deletion across controller instances on IO. */
    suspend fun <T> access(block: suspend VoiceprintProfileStore.() -> T): T = withContext(Dispatchers.IO) {
        storageMutex.withLock { block() }
    }

    fun profile(): VoiceprintProfile? {
        // Recognition may read while IO publishes an enrollment. Read one atomic preference map.
        val values = prefs.all
        val id = (values[KEY_ID] as? String)?.takeIf(String::isNotBlank) ?: return null
        if (id.length > 64) return null
        val encoded = (values[KEY_VECTOR] as? String)?.takeIf(String::isNotBlank) ?: return null
        if (encoded.length > ((MAX_FEATURE_BYTES + 2) / 3) * 4) return null
        val vector = runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
            ?.takeIf { it.isNotEmpty() && it.size <= MAX_FEATURE_BYTES && it.any { value -> value != 0.toByte() } } ?: return null
        return VoiceprintProfile(id, vector, values[KEY_CREATED] as? Long ?: 0L,
            values["model_identity"] as? String ?: "legacy-mfcc-v1")
    }

    fun enroll(profileId: String, featureVector: ByteArray, modelIdentity: String = "legacy-mfcc-v1"): VoiceprintProfile? {
        val id = profileId.trim().takeIf { it.length in 1..64 } ?: return null
        if (featureVector.isEmpty() || featureVector.size > MAX_FEATURE_BYTES || featureVector.all { it == 0.toByte() }) return null
        if (modelIdentity.isBlank() || modelIdentity.length > 190) return null
        val profile = VoiceprintProfile(id, featureVector.copyOf(), System.currentTimeMillis(), modelIdentity)
        check(prefs.edit()
            .putString(KEY_ID, profile.id)
            .putString(KEY_VECTOR, Base64.encodeToString(profile.featureVector, Base64.NO_WRAP))
            .putLong(KEY_CREATED, profile.createdAt)
            .putString("model_identity", modelIdentity)
            .commit()) { "声纹档案保存失败" }
        return profile
    }

    fun clear() { prefs.edit().clear().apply() }

    /** Compares provider-produced feature vectors without persisting raw audio. */
    fun verify(featureVector: ByteArray?, threshold: Float, modelIdentity: String = "legacy-mfcc-v1"): VoiceprintVerification {
        val profile = profile() ?: return VoiceprintVerification.NO_PROFILE
        if (profile.modelIdentity != modelIdentity) return VoiceprintVerification.UNAVAILABLE
        if (featureVector == null || featureVector.isEmpty() || featureVector.size != profile.featureVector.size ||
            featureVector.size > MAX_FEATURE_BYTES || featureVector.all { it == 0.toByte() } ||
            !threshold.isFinite() || threshold <= 0f || threshold > 1f) return VoiceprintVerification.UNAVAILABLE
        val score = cosineSimilarity(profile.featureVector, featureVector)
        return if (score >= threshold) {
            VoiceprintVerification.VERIFIED
        } else {
            VoiceprintVerification.REJECTED
        }
    }

    private fun cosineSimilarity(left: ByteArray, right: ByteArray): Float {
        if (left.size != right.size) return 0f
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        left.indices.forEach { index ->
            val l = left[index].toInt().toDouble()
            val r = right[index].toInt().toDouble()
            dot += l * r
            leftNorm += l * l
            rightNorm += r * r
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) return 0f
        return (dot / kotlin.math.sqrt(leftNorm * rightNorm)).toFloat()
    }

    private companion object {
        val storageMutex = Mutex()
        const val KEY_ID = "profile_id"
        const val KEY_VECTOR = "feature_vector"
        const val KEY_CREATED = "created_at"
        const val MAX_FEATURE_BYTES = 256 * 1024
    }
}
