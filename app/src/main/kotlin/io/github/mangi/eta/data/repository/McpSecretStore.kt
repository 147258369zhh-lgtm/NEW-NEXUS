package io.github.mangi.eta.data.repository

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.mangi.eta.R
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** MCP 凭据只以 Android Keystore 密文保存在 App 私有偏好中。 */
internal class McpSecretStore(
    context: Context,
    private val keyProvider: (create: Boolean) -> SecretKey = ::secretKey,
) {
    private val context = context.applicationContext
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun bearerToken(serverId: String): String? {
        if (!preferences.contains(tokenKey(serverId))) return null
        return try {
            val encoded = requireNotNull(preferences.getString(tokenKey(serverId), null))
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > GCM_IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                keyProvider(false),
                GCMParameterSpec(GCM_TAG_BITS, payload, 0, GCM_IV_BYTES),
            )
            cipher.doFinal(payload, GCM_IV_BYTES, payload.size - GCM_IV_BYTES)
                .toString(Charsets.UTF_8).also { require(it.isNotBlank()) }
        } catch (_: Exception) {
            // 不返回 null，也不在读取时生成密钥：避免将失效密文误判成无认证。
            throw McpCredentialsUnavailable(context.getString(R.string.mcp_credentials_unavailable))
        }
    }

    fun credentialStatus(serverId: String): McpCredentialStatus = try {
        if (bearerToken(serverId) == null) McpCredentialStatus.MISSING else McpCredentialStatus.AVAILABLE
    } catch (_: McpCredentialsUnavailable) {
        McpCredentialStatus.UNAVAILABLE
    }

    /** 回滚使用原密文，重填/删除凭据不需要先解密已经失效的旧值。 */
    @Synchronized
    fun encryptedSnapshot(serverId: String): String? = preferences.getString(tokenKey(serverId), null)

    @Synchronized
    @SuppressLint("UseKtx") // 必须检查 commit 的返回值；KTX edit 不返回写入是否成功。
    fun restoreEncryptedSnapshot(serverId: String, encoded: String?) {
        check(preferences.edit().putString(tokenKey(serverId), encoded).commit()) { "MCP 凭据恢复失败" }
    }

    @Synchronized
    fun setBearerToken(serverId: String, token: String) {
        val normalized = token.trim()
        if (normalized.isBlank()) {
            clear(serverId)
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider(true))
        val encrypted = cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + encrypted
        check(
            preferences.edit()
            .putString(tokenKey(serverId), Base64.encodeToString(payload, Base64.NO_WRAP))
            .commit()
        ) { "MCP 凭据保存失败" }
    }

    @Synchronized
    fun clear(serverId: String) {
        check(preferences.edit().remove(tokenKey(serverId)).commit()) { "MCP 凭据删除失败" }
    }

    private fun tokenKey(serverId: String): String = "bearer_$serverId"

    private companion object {
        fun secretKey(create: Boolean): SecretKey {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            check(create) { "MCP credential key unavailable" }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build()
                )
                generateKey()
            }
        }

        const val PREFERENCES_NAME = "eta_mcp_secrets"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "eta_mcp_credentials_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}

internal enum class McpCredentialStatus { MISSING, AVAILABLE, UNAVAILABLE }

internal class McpCredentialsUnavailable(message: String) : IllegalStateException(message)
