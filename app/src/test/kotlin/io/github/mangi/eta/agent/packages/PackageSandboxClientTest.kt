package io.github.mangi.eta.agent.packages

import android.app.Application
import android.content.ComponentName
import android.os.Binder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PackageSandboxClientTest {
    @Test fun reconnectAfterSandboxProcessDisconnectUsesNewBinder() {
        val context = RuntimeEnvironment.getApplication()
        val client = PackageSandboxClient(context)
        val component = ComponentName(context, PackageSandboxService::class.java)
        val first = Binder()
        val replacement = Binder()
        val executor = Executors.newSingleThreadExecutor()
        try {
            client.connection.onServiceConnected(component, first)
            assertSame(first, client.connect())

            client.connection.onServiceDisconnected(component)
            val reconnect = executor.submit<android.os.IBinder> { client.connect() }
            client.connection.onServiceConnected(component, replacement)

            assertSame(replacement, reconnect.get(2, TimeUnit.SECONDS))
        } finally {
            client.close()
            executor.shutdownNow()
        }
    }
}
