package io.github.mangi.eta.agent.device

import java.io.IOException
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import okhttp3.Interceptor
import okhttp3.Response

/** 只解释已经发生的连接失败；不探测网络、不请求权限、不阻断成功或云端请求。 */
internal class LocalNetworkFailureInterceptor(
    private val accessDenied: () -> Boolean,
    private val permissionMessage: () -> String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = try {
        chain.proceed(chain.request())
    } catch (failure: IOException) {
        if (failure !is LocalNetworkAccessException && !chain.call().isCanceled() &&
            !Thread.currentThread().isInterrupted && isConnectionFailure(failure) &&
            isLocalNetworkHost(chain.request().url.host) && accessDenied()
        ) throw LocalNetworkAccessException(permissionMessage(), failure)
        throw failure
    }

    private fun isConnectionFailure(failure: IOException): Boolean =
        failure is SocketException || failure is SocketTimeoutException || failure is UnknownHostException
}

internal class LocalNetworkAccessException(message: String, cause: IOException? = null) : IOException(message, cause) {
    companion object { const val CODE = "LOCAL_NETWORK_PERMISSION_REQUIRED" }
}

/** 保守识别显式私网地址及 mDNS 名称。普通域名不额外 DNS 解析，回环地址不需要 LAN 授权。 */
internal fun isLocalNetworkHost(host: String): Boolean {
    val normalized = host.lowercase(Locale.ROOT).trimEnd('.')
    if (normalized.endsWith(".local")) return true
    val address = when {
        ':' in normalized && normalized.all { it in "0123456789abcdef:." } ->
            runCatching { InetAddress.getByName(normalized) }.getOrNull()
        else -> {
            val parts = normalized.split('.')
            if (parts.size != 4) return false
            val numbers = parts.map { part ->
                if (part.isEmpty() || part.any { it !in '0'..'9' }) return false
                part.toIntOrNull()?.takeIf { it in 0..255 } ?: return false
            }
            InetAddress.getByAddress(numbers.map(Int::toByte).toByteArray())
        }
    } ?: return false
    return !address.isLoopbackAddress && (address.isSiteLocalAddress || address.isLinkLocalAddress ||
        (address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc))
}
