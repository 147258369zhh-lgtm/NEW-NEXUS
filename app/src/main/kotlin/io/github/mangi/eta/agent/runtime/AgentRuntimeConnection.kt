package io.github.mangi.eta.agent.runtime

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import android.os.SystemClock
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.fork.diagnostics.RuntimeDebugTrace
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Shared connection; failed or abandoned binds never become the next request's connection. */
internal object AgentRuntimeConnection {
    class Lease internal constructor(val messenger: Messenger, private val releaseAction: () -> Unit) : AutoCloseable {
        val binder: IBinder get() = messenger.binder
        private var released = false
        override fun close() {
            synchronized(this) {
                if (released) return
                released = true
            }
            releaseAction()
        }
    }

    private val shared = Owner()
    fun acquire(context: Context, callLogger: AgentLogger): Lease? = shared.acquire(context, callLogger)

    internal class Owner(private val bindTimeoutMs: Long = 8_000L) {
        private val lock = Any()
        private val mainHandler = Handler(Looper.getMainLooper())
        private var current: Attempt? = null
        private val idleUnbind = Runnable { unbindIfIdle() }

        private inner class Attempt(val context: Context, val logger: AgentLogger) : ServiceConnection {
            var messenger: Messenger? = null
            var latch = CountDownLatch(1)
            var bound = false
            var waiting = 0
            var leases = 0
            var startedAt = SystemClock.elapsedRealtime()

            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                synchronized(lock) {
                    if (current !== this) return
                    messenger = Messenger(service)
                    latch.countDown()
                    RuntimeDebugTrace.serviceConnected(SystemClock.elapsedRealtime() - startedAt)
                    scheduleIdleLocked(this)
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                synchronized(lock) {
                    if (current !== this) return
                    messenger = null
                    latch.countDown()
                    latch = CountDownLatch(1)
                    startedAt = SystemClock.elapsedRealtime()
                    // Android reconnects this registration; do not bind it twice.
                    RuntimeDebugTrace.serviceDisconnected()
                    if (leases == 0 && waiting == 0) resetLocked(this)
                }
            }

            override fun onBindingDied(name: ComponentName) = reset(this)
            override fun onNullBinding(name: ComponentName) = reset(this)
        }

        fun acquire(context: Context, logger: AgentLogger): Lease? {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Agent Runtime 同步客户端不能在主线程调用" }
            val attempt: Attempt
            val latch: CountDownLatch
            val shouldBind: Boolean
            synchronized(lock) {
                mainHandler.removeCallbacks(idleUnbind)
                current?.let { existing ->
                    existing.messenger?.let { return leaseLocked(existing, it) }
                }
                shouldBind = current == null
                attempt = current ?: Attempt(context.applicationContext, logger).also { current = it }
                attempt.waiting++
                latch = attempt.latch
            }
            if (shouldBind) mainHandler.post { bind(attempt) }
            try {
                if (!latch.await(bindTimeoutMs, TimeUnit.MILLISECONDS)) return null
                return synchronized(lock) {
                    if (current !== attempt) null else attempt.messenger?.let { leaseLocked(attempt, it) }
                }
            } finally {
                synchronized(lock) {
                    attempt.waiting--
                    if (current === attempt && attempt.waiting == 0 && attempt.leases == 0) {
                        if (attempt.messenger == null) resetLocked(attempt) else scheduleIdleLocked(attempt)
                    }
                }
            }
        }

        private fun leaseLocked(attempt: Attempt, messenger: Messenger): Lease {
            attempt.leases++
            return Lease(messenger) {
                synchronized(lock) {
                    attempt.leases--
                    if (current === attempt) {
                        if (attempt.messenger == null && attempt.leases == 0 && attempt.waiting == 0) resetLocked(attempt)
                        else scheduleIdleLocked(attempt)
                    }
                }
            }
        }

        private fun bind(attempt: Attempt) {
            synchronized(lock) { if (current !== attempt) return }
            val succeeded = runCatching {
                attempt.context.bindService(AgentRuntimeWire.serviceIntent(), attempt,
                    Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT or Context.BIND_INCLUDE_CAPABILITIES)
            }.onFailure { failure ->
                attempt.logger.warn("Agent runtime service bind failed: type=${failure.safeLogType()}")
                RuntimeDebugTrace.bindingFailed(failure.safeLogType())
            }.getOrDefault(false)
            synchronized(lock) {
                attempt.bound = succeeded
                if (current !== attempt) {
                    if (succeeded) mainHandler.post { unbind(attempt) }
                } else if (!succeeded) {
                    resetLocked(attempt)
                } else {
                    scheduleIdleLocked(attempt)
                }
            }
        }

        private fun scheduleIdleLocked(attempt: Attempt) {
            if (attempt.leases != 0 || attempt.waiting != 0 || !attempt.bound) return
            mainHandler.removeCallbacks(idleUnbind)
            mainHandler.postDelayed(idleUnbind, 30_000L)
        }

        private fun unbindIfIdle() = synchronized(lock) {
            current?.takeIf { it.leases == 0 && it.waiting == 0 && it.bound }?.let(::resetLocked)
        }

        private fun reset(attempt: Attempt) = synchronized(lock) {
            if (current === attempt) resetLocked(attempt)
        }

        private fun resetLocked(attempt: Attempt) {
            current = null
            attempt.messenger = null
            attempt.latch.countDown()
            mainHandler.removeCallbacks(idleUnbind)
            if (attempt.bound) mainHandler.post { unbind(attempt) }
        }

        private fun unbind(attempt: Attempt) {
            if (!attempt.bound) return
            attempt.bound = false
            runCatching { attempt.context.unbindService(attempt) }.onFailure { failure ->
                attempt.logger.warn("Agent runtime service unbind failed: type=${failure.safeLogType()}")
            }
        }
    }
}
