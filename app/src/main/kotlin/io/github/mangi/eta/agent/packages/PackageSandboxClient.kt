package io.github.mangi.eta.agent.packages

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import io.github.mangi.eta.agent.runtime.RuntimeInvocationScope
import io.github.mangi.eta.agent.runtime.RunResourceScope
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.json.JSONObject

internal class PackageSandboxClient(context: Context) : Closeable {
    private val context = context.applicationContext
    private val lock = Any()
    private var closed = false
    private var bound = false
    private var endpoint: IBinder? = null
    private var connected: CompletableFuture<IBinder>? = null
    private val activeScopes = java.util.concurrent.CopyOnWriteArraySet<RunResourceScope>()
    internal val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            synchronized(lock) { if (closed) return; endpoint = service; connected?.complete(service) }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            synchronized(lock) {
                endpoint = null
                // Android may reconnect the same binding after a sandbox process death. A
                // completed future still points at the dead Binder, so wait for the new callback.
                val pending = connected
                connected = CompletableFuture()
                pending?.takeIf { !it.isDone }?.completeExceptionally(IllegalStateException("PACKAGE_PROCESS_DISCONNECTED"))
            }
        }
        override fun onBindingDied(name: ComponentName) = disconnectedBinding()
        override fun onNullBinding(name: ComponentName) = disconnectedBinding()
        private fun disconnectedBinding() {
            val shouldUnbind = synchronized(lock) {
                endpoint = null
                connected?.completeExceptionally(IllegalStateException("PACKAGE_PROCESS_DISCONNECTED"))
                connected = null
                if (bound) { bound = false; true } else false
            }
            if (shouldUnbind) runCatching { context.unbindService(this) }
        }
    }

    fun invoke(source: File, timeoutMs: Long, memoryBytes: Long, host: (String, JSONObject) -> JSONObject): JSONObject {
        val info = android.app.ActivityManager.MemoryInfo()
        context.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(info)
        if (info.totalMem > 0 && (info.lowMemory || info.availMem < maxOf(info.threshold, memoryBytes + 256L * 1024 * 1024))) {
            throw PackageSandboxBusyException("PACKAGE_MEMORY_PRESSURE")
        }
        if (!ACTIVE.tryAcquire()) throw PackageSandboxBusyException("PACKAGE_RESOURCE_BUSY")
        val controller = RuntimeInvocationScope.controller
        val invoker = RuntimeInvocationScope.nestedToolExecutor
        val scope = RunResourceScope().also { activeScopes.add(it) }
        val binding = controller?.register { scope.close(); close() }
        try {
            controller?.throwIfCancelled()
            val service = connect()
            val sandboxUid = sandboxUid(service)
            require(sandboxUid != android.os.Process.myUid()) { "PACKAGE_ISOLATION_REQUIRED" }
            val callback = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code != PackageSandboxService.HOST_INVOKE) return super.onTransact(code, data, reply, flags)
                    data.enforceInterface(PackageSandboxService.HOST_DESCRIPTOR)
                    require(getCallingUid() == sandboxUid) { "PACKAGE_HOST_CALLER_INVALID" }
                    val tool = requireNotNull(data.readString()).also { require(it.length <= 128) }
                    val arguments = requireNotNull(data.readString()).also { require(it.toByteArray().size <= 64 * 1024) }
                    val operation = { RuntimeInvocationScope.withResources(scope) { controller?.throwIfCancelled(); host(tool, JSONObject(arguments)) } }
                    val routed = { if (invoker == null) operation() else RuntimeInvocationScope.withNestedTools(invoker, operation) }
                    val result = if (controller == null) routed() else RuntimeInvocationScope.withController(controller, routed)
                    val encoded = result.toString()
                    require(encoded.toByteArray().size <= 64 * 1024) { "PACKAGE_BRIDGE_OUTPUT_LIMIT" }
                    reply?.writeNoException(); reply?.writeString(encoded)
                    return true
                }
            }
            val input = Parcel.obtain(); val output = Parcel.obtain()
            try {
                ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    input.writeInterfaceToken(PackageSandboxService.DESCRIPTOR)
                    input.writeTypedObject(descriptor, 0); input.writeStrongBinder(callback)
                    input.writeLong(timeoutMs); input.writeLong(memoryBytes)
                    check(service.transact(PackageSandboxService.INVOKE, input, output, 0)) { "PACKAGE_PROCESS_DISCONNECTED" }
                    output.readException()
                    controller?.throwIfCancelled()
                    return JSONObject(requireNotNull(output.readString()))
                }
            } finally { input.recycle(); output.recycle() }
        } finally { binding?.close(); scope.close(); activeScopes.remove(scope); ACTIVE.release() }
    }

    internal fun connect(): IBinder {
        val future = synchronized(lock) {
            check(!closed) { "PACKAGE_HOST_CLOSED" }
            endpoint?.takeIf { it.isBinderAlive }?.let { return it }
            connected ?: CompletableFuture<IBinder>().also { future ->
                connected = future
                bound = context.bindIsolatedService(Intent(context, PackageSandboxService::class.java), Context.BIND_AUTO_CREATE,
                    UUID.randomUUID().toString().replace('-', '_'), context.mainExecutor, connection)
                if (!bound) future.completeExceptionally(IllegalStateException("PACKAGE_BIND_FAILED"))
            }
        }
        return future.get(10, TimeUnit.SECONDS)
    }

    private fun sandboxUid(service: IBinder): Int {
        val input = Parcel.obtain(); val output = Parcel.obtain()
        try {
            input.writeInterfaceToken(PackageSandboxService.DESCRIPTOR)
            check(service.transact(PackageSandboxService.HELLO, input, output, 0))
            output.readException()
            return output.readInt()
        } finally { input.recycle(); output.recycle() }
    }

    override fun close() {
        activeScopes.forEach { it.close() }
        val service = synchronized(lock) {
            if (closed) return
            closed = true
            connected?.completeExceptionally(IllegalStateException("PACKAGE_HOST_CLOSED"))
            endpoint.also { endpoint = null }
        }
        if (service != null) runCatching {
            val data = Parcel.obtain()
            try { data.writeInterfaceToken(PackageSandboxService.DESCRIPTOR); service.transact(PackageSandboxService.CANCEL, data, null, IBinder.FLAG_ONEWAY) }
            finally { data.recycle() }
        }
        synchronized(lock) { if (bound) { bound = false; runCatching { context.unbindService(connection) } } }
    }

    private companion object { val ACTIVE = java.util.concurrent.Semaphore(2) }
}

internal class PackageSandboxBusyException(val code: String) : IllegalStateException(code)
