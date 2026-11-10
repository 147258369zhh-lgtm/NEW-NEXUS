package io.github.mangi.eta.agent.model

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import io.github.mangi.eta.agent.device.LocalNetworkAccessException
import java.io.IOException
import java.net.ConnectException
import okhttp3.Request
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36, 37], application = Application::class)
class AgentHttpClientPermissionTest {
    @After fun reset() { AgentHttpClient.initialize(RuntimeEnvironment.getApplication()) }

    @Test
    fun sharedAndModelClientsUseLivePermissionsWhileOlderAndroidDoesNotCheckLanPermission() {
        val context = PermissionContext()
        AgentHttpClient.initialize(context)
        for (base in listOf(AgentHttpClient.client, AgentHttpClient.modelClient,
            AgentHttpClient.modelClient(ProviderRequestPurpose.COMPACTION))) {
            val cause = ConnectException("EPERM")
            val client = base.newBuilder().addInterceptor { throw cause }.build()
            fun execute() = client.newCall(Request.Builder().url("https://192.168.1.2").build()).execute()
            context.granted = false
            val missing = assertThrows(IOException::class.java) { execute() }
            if (Build.VERSION.SDK_INT >= 37) {
                assertTrue(missing is LocalNetworkAccessException)
                assertTrue(missing.message.orEmpty().contains("Permission health"))
            } else assertSame(cause, missing)
            context.granted = true
            assertSame(cause, assertThrows(IOException::class.java) { execute() })
        }
        if (Build.VERSION.SDK_INT < 37) assertEquals(0, context.checks)
        else assertTrue(context.checks >= 6)
    }

    private class PermissionContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        var granted = false
        var checks = 0
        override fun getApplicationContext(): Context = this
        override fun checkSelfPermission(permission: String): Int {
            assertEquals(Manifest.permission.ACCESS_LOCAL_NETWORK, permission)
            checks++
            return if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
    }
}
