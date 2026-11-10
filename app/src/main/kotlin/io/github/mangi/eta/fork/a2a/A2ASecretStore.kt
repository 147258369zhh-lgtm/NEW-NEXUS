package io.github.mangi.eta.fork.a2a

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores bearer tokens as AES-GCM ciphertext backed by an Android Keystore key. */
internal class A2ASecretStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun save(alias: String, secret: String): String {
        requireValidAlias(alias)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + encrypted
        preferences.edit()
            .putString(alias, Base64.encodeToString(payload, Base64.NO_WRAP))
            .apply()
        return alias
    }

    fun load(alias: String?): String? {
        if (alias == null) return null
        requireValidAlias(alias)
        val payload = preferences.getString(alias, null)?.let {
            Base64.decode(it, Base64.NO_WRAP)
        } ?: return null
        require(payload.size > GCM_IV_BYTES) { "Corrupt A2A secret payload" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(GCM_TAG_BITS, payload, 0, GCM_IV_BYTES),
        )
        return cipher.doFinal(payload, GCM_IV_BYTES, payload.size - GCM_IV_BYTES)
            .toString(Charsets.UTF_8)
    }

    fun delete(alias: String?) {
        if (alias == null) return
        requireValidAlias(alias)
        preferences.edit().remove(alias).apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_NAME, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_NAME,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "eta_a2a_secrets"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_NAME = "eta_a2a_bearer_tokens_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private val ALIAS_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")

        internal fun requireValidAlias(alias: String) {
            require(ALIAS_PATTERN.matches(alias)) { "Invalid A2A secret alias" }
        }
    }
}
