package io.github.mangi.eta.agent.device

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class LocalNetworkFailureInterceptorTest {
    @Test
    fun explicitLanAddressesAreRecognizedWithoutDnsAndLoopbackIsExempt() {
        for (host in listOf("10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.2", "169.254.1.2",
            "fd00::1", "fc00::1234", "fe80::1", "::ffff:192.168.1.2", "bridge.local", "BRIDGE.LOCAL.")) {
            assertTrue(host, isLocalNetworkHost(host))
        }
        for (host in listOf("127.0.0.1", "127.12.0.1", "::1", "localhost", "8.8.8.8", "172.32.0.1",
            "172.15.255.255", "192.169.1.2", "2001:4860:4860::8888", "cloud.example", "bridge.local.example",
            "private.example", "192.168.1.999", "1.2.3", "fd00::invalid")) {
            assertFalse(host, isLocalNetworkHost(host))
        }
    }

    @Test
    fun onlyFailedLanConnectionsWithMissingPermissionReceiveTheGuide() {
        for (failure in listOf(ConnectException("EPERM with private details"), SocketTimeoutException(), UnknownHostException())) {
            val client = client({ true }, failure)
            val diagnosed = assertThrows(LocalNetworkAccessException::class.java) {
                client.newCall(request("https://192.168.1.2/private?token=secret")).execute()
            }
            assertEquals("Permission guide", diagnosed.message)
            assertSame(failure, diagnosed.cause)
        }
    }

    @Test
    fun grantAndRevocationAreReadForEachFailureWithoutRebuildingTheClient() {
        var denied = false
        val failure = ConnectException("offline")
        val client = client({ denied }, failure)
        assertSame(failure, assertThrows(IOException::class.java) { client.newCall(request()).execute() })
        denied = true
        assertThrows(LocalNetworkAccessException::class.java) { client.newCall(request()).execute() }
        denied = false
        assertSame(failure, assertThrows(IOException::class.java) { client.newCall(request()).execute() })
    }

    @Test
    fun cloudLoopbackTlsAndNonConnectionErrorsKeepTheirOriginalMeaning() {
        val connection = ConnectException("offline")
        for (url in listOf("https://cloud.example", "http://127.0.0.1", "http://[::1]")) {
            assertSame(connection, assertThrows(IOException::class.java) {
                client({ true }, connection).newCall(request(url)).execute()
            })
        }
        for (failure in listOf(SSLHandshakeException("certificate mismatch"), IOException("invalid MCP response"))) {
            assertSame(failure, assertThrows(IOException::class.java) {
                client({ true }, failure).newCall(request()).execute()
            })
        }
    }

    @Test
    fun successfulRequestsAreNotBlockedAndDoNotEvenCheckPermission() {
        val client = OkHttpClient.Builder()
            .addInterceptor(LocalNetworkFailureInterceptor({ error("must not check") }, { error("must not display") }))
            .addInterceptor { chain -> Response.Builder().request(chain.request()).code(200).message("OK")
                .protocol(Protocol.HTTP_1_1).body("ok".toResponseBody()).build() }
            .build()
        client.newCall(request()).execute().use { assertEquals("ok", it.body.string()) }
    }

    @Test
    fun cancellationKeepsTheCancellationFailureInsteadOfAskingForPermission() {
        val failure = ConnectException("canceled")
        val client = OkHttpClient.Builder()
            .addInterceptor(LocalNetworkFailureInterceptor({ error("must not check") }, { error("must not display") }))
            .addInterceptor { chain -> chain.call().cancel(); throw failure }
            .build()
        assertSame(failure, assertThrows(IOException::class.java) { client.newCall(request()).execute() })
    }

    private fun client(denied: () -> Boolean, failure: IOException) = OkHttpClient.Builder()
        .addInterceptor(LocalNetworkFailureInterceptor(denied, { "Permission guide" }))
        .addInterceptor { throw failure }.build()

    private fun request(url: String = "https://bridge.local") = Request.Builder().url(url).build()
}
