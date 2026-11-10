package io.github.mangi.eta.data.repository

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object AutoBackupPassword {
    private const val ALIAS = "eta_auto_backup_password_v1"
    private fun prefs(context: Context) = context.getSharedPreferences("eta_auto_backup_secret", Context.MODE_PRIVATE)
    fun configured(context: Context) = prefs(context).contains("ciphertext")
    fun save(context: Context, password: String) {
        if (password.isEmpty()) { check(prefs(context).edit().clear().commit()); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        check(prefs(context).edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("ciphertext", Base64.encodeToString(cipher.doFinal(password.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)).commit())
    }
    fun read(context: Context): String? {
        val p = prefs(context)
        val value = p.getString("ciphertext", null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(p.getString("iv", ""), Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(value, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (failure: Exception) { throw IllegalStateException("自动备份密码不可用，请在备份设置中重新设置", failure) }
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
