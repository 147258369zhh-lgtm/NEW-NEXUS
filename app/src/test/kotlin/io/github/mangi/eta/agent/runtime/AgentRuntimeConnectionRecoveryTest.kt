package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.Looper
import android.os.Messenger
import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentRuntimeConnectionRecoveryTest {
    private class Host : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val connections = CopyOnWriteArrayList<ServiceConnection>()
        val unbound = CopyOnWriteArrayList<ServiceConnection>()
        override fun getApplicationContext(): Context = this
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            connections.add(connection)
            return true // Simulate Android accepting a bind but never delivering its callback.
        }
        override fun unbindService(connection: ServiceConnection) { unbound.add(connection) }
    }

    @Test fun timedOutRegistrationCannotPoisonNextBindOrAcceptStaleCallback() {
        val host = Host()
        val owner = AgentRuntimeConnection.Owner(bindTimeoutMs = 400)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit<AgentRuntimeConnection.Lease?> { owner.acquire(host, AndroidAgentLogger) }
            await { host.connections.size == 1 }
            assertNull(first.get(2, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(host.connections[0]), host.unbound.toList())

            val next = executor.submit<AgentRuntimeConnection.Lease?> { owner.acquire(host, AndroidAgentLogger) }
            await { host.connections.size == 2 }
            val stale = Messenger(Handler(Looper.getMainLooper()))
            val valid = Messenger(Handler(Looper.getMainLooper()))
            val component = ComponentName(host, "Runtime")
            host.connections[0].onServiceConnected(component, stale.binder)
            host.connections[1].onServiceConnected(component, valid.binder)
            val lease = next.get(2, TimeUnit.SECONDS)!!
            assertSame(valid.binder, lease.binder)
            lease.close()
            host.connections[1].onBindingDied(component)
            shadowOf(Looper.getMainLooper()).idle()
        } finally { executor.shutdownNow() }
    }

    @Test fun interruptedWaitReleasesItsRegistrationWithoutBlockingNextAttempt() {
        val host = Host()
        val owner = AgentRuntimeConnection.Owner()
        val interrupted = java.util.concurrent.atomic.AtomicBoolean()
        val thread = Thread {
            try { owner.acquire(host, AndroidAgentLogger) }
            catch (_: InterruptedException) { interrupted.set(true) }
        }
        thread.start()
        await { host.connections.size == 1 }
        thread.interrupt()
        thread.join(2_000)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(thread.isAlive)
        assertTrue(interrupted.get())
        assertEquals(host.connections.toList(), host.unbound.toList())
    }

    @Test fun disconnectedLastLeaseIsReleasedBeforeANewRegistrationCanBeUsed() {
        val host = Host()
        val owner = AgentRuntimeConnection.Owner()
        val executor = Executors.newSingleThreadExecutor()
        val component = ComponentName(host, "Runtime")
        try {
            val first = executor.submit<AgentRuntimeConnection.Lease?> { owner.acquire(host, AndroidAgentLogger) }
            await { host.connections.size == 1 }
            host.connections[0].onServiceConnected(component, Messenger(Handler(Looper.getMainLooper())).binder)
            val oldLease = first.get(2, TimeUnit.SECONDS)!!
            host.connections[0].onServiceDisconnected(component)
            oldLease.close()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(host.connections[0]), host.unbound.toList())

            val next = executor.submit<AgentRuntimeConnection.Lease?> { owner.acquire(host, AndroidAgentLogger) }
            await { host.connections.size == 2 }
            val valid = Messenger(Handler(Looper.getMainLooper()))
            host.connections[1].onServiceConnected(component, valid.binder)
            val nextLease = next.get(2, TimeUnit.SECONDS)!!
            oldLease.close()
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(valid.binder, nextLease.binder)
            assertEquals(1, host.unbound.size)
            nextLease.close()
            host.connections[1].onBindingDied(component)
            shadowOf(Looper.getMainLooper()).idle()
        } finally { executor.shutdownNow() }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        assertTrue(condition())
    }
}
