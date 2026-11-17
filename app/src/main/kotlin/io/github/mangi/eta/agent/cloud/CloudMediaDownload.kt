package io.github.mangi.eta.agent.cloud

import io.github.mangi.eta.agent.runtime.AgentRunController
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Only anonymous provider result downloads; credentials never leave the configured API origin. */
internal class CloudMediaDownload(baseClient: OkHttpClient = OkHttpClient()) {
    private val client = baseClient.newBuilder().cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).cache(null)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
                if (addresses.isEmpty() || addresses.any(::nonPublicAddress)) throw CloudApiException("media_download_address_denied")
            }
        }).build()

    fun download(controller: AgentRunController, raw: String, maxBytes: Long, timeoutSeconds: Int): CloudBinaryResponse =
        downloadWithPolicy(controller, raw, maxBytes, timeoutSeconds, ::validatedResultUrl)

    /** Explicit Images API URLs are anonymous downloads; DNS still denies private/metadata networks. */
    fun downloadPublicImage(controller: AgentRunController, raw: String, maxBytes: Long, timeoutSeconds: Int): CloudBinaryResponse =
        downloadWithPolicy(controller, raw, maxBytes, timeoutSeconds, ::validatedPublicImageUrl)

    private fun downloadWithPolicy(controller: AgentRunController, raw: String, maxBytes: Long, timeoutSeconds: Int,
        validate: (String) -> HttpUrl): CloudBinaryResponse {
        require(maxBytes in 1..128L * 1024 * 1024)
        var url = validate(raw)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())
        for (redirect in 0..3) {
            controller.throwIfStopped()
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw CloudApiException("media_download_timeout")
            val call = client.newCall(Request.Builder().url(url).header("Accept", "image/*,video/mp4,application/octet-stream").get().build())
            call.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
            val binding = controller.register(call::cancel)
            try {
                call.execute().use { response ->
                    controller.throwIfStopped()
                    if (response.code in setOf(301, 302, 303, 307, 308)) {
                        if (redirect == 3) throw CloudApiException("media_download_redirect_limit")
                        val next = response.header("Location")?.let(url::resolve)
                            ?: throw CloudApiException("media_download_redirect_invalid")
                        url = validate(next.toString())
                        return@use
                    }
                    if (!response.isSuccessful) throw CloudApiException("media_download_http_${response.code}", response.code)
                    val body = response.body
                    val length = body.contentLength()
                    if (length > maxBytes) throw CloudApiException("media_download_too_large")
                    val bytes = ByteArrayOutputStream()
                    body.byteStream().use { input ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            controller.throwIfStopped()
                            if (System.nanoTime() >= deadline) throw CloudApiException("media_download_timeout")
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (bytes.size().toLong() + read > maxBytes) throw CloudApiException("media_download_too_large")
                            bytes.write(buffer, 0, read)
                        }
                    }
                    if (bytes.size() == 0 || length >= 0 && bytes.size().toLong() != length) throw CloudApiException("media_download_incomplete")
                    return CloudBinaryResponse(bytes.toByteArray(), body.contentType()?.toString() ?: "application/octet-stream")
                }
            } catch (error: IOException) {
                controller.throwIfStopped()
                throw if (error is CloudApiException) error else CloudApiException("media_download_network_error")
            } finally { binding.close() }
        }
        throw CloudApiException("media_download_redirect_limit")
    }

    companion object {
        fun validatedPublicImageUrl(raw: String): HttpUrl {
            val url = raw.takeIf { it.length <= 8192 && it.none(Char::isISOControl) }?.toHttpUrlOrNull()
                ?: throw CloudApiException("media_download_url_invalid")
            if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) {
                throw CloudApiException("media_download_url_denied")
            }
            // Literal addresses must also pass before a request is created (DNS checks repeat this).
            if (url.host == "localhost" || url.host.endsWith(".localhost") ||
                (url.host.contains(':') || Regex("[0-9.]+").matches(url.host)) &&
                runCatching { nonPublicAddress(InetAddress.getByName(url.host)) }.getOrDefault(true)) {
                throw CloudApiException("media_download_address_denied")
            }
            return url.newBuilder().fragment(null).build()
        }

        fun validatedResultUrl(raw: String): HttpUrl {
            val url = raw.takeIf { it.length <= 8192 && it.none(Char::isISOControl) }?.toHttpUrlOrNull()
                ?: throw CloudApiException("media_download_url_invalid")
            if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() ||
                !Regex("[a-z0-9][a-z0-9.-]*\\.oss(?:-[a-z0-9-]+)?\\.aliyuncs\\.com").matches(url.host)) {
                throw CloudApiException("media_download_url_denied")
            }
            return url.newBuilder().fragment(null).build()
        }

        private fun nonPublicAddress(address: InetAddress): Boolean = address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress ||
            address.address.let { bytes -> bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc ||
                bytes.size == 4 && ((bytes[0].toInt() and 255) == 0 || (bytes[0].toInt() and 255) >= 224 ||
                    (bytes[0].toInt() and 255) == 100 && (bytes[1].toInt() and 255) in 64..127 ||
                    (bytes[0].toInt() and 255) == 192 && (bytes[1].toInt() and 255) == 0 ||
                    (bytes[0].toInt() and 255) == 198 && (bytes[1].toInt() and 255) in 18..19) }
    }
}
