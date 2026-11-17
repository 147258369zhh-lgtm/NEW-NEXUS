package io.github.mangi.eta.agent.packages

import android.app.Service
import android.content.Intent
import android.os.*
import android.os.Process
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** No app storage, network or Java reflection is exposed to an isolated package UID. */
class PackageSandboxService : Service() {
    private val busy = AtomicBoolean(false)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "package-deadline").apply { isDaemon = true } }
    @Volatile private var session: PackageJsSession? = null
    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(DESCRIPTOR); return true }
            data.enforceInterface(DESCRIPTOR)
            require(getCallingUid() == applicationInfo.uid) { "PACKAGE_CALLER_DENIED" }
            if (code == HELLO) { reply?.writeNoException(); reply?.writeInt(Process.myUid()); return true }
            if (code == CANCEL) { session?.interrupt(); return true }
            if (code != INVOKE) return super.onTransact(code, data, reply, flags)
            val descriptor = requireNotNull(data.readTypedObject(ParcelFileDescriptor.CREATOR))
            val host = requireNotNull(data.readStrongBinder())
            val requestedTimeout = data.readLong().coerceIn(1000, 120_000)
            val memory = data.readLong().coerceIn(8L * 1024 * 1024, 256L * 1024 * 1024)
            if (!busy.compareAndSet(false, true)) {
                descriptor.close(); reply?.writeNoException(); reply?.writeString("{\"ok\":false,\"code\":\"PACKAGE_BUSY\"}"); return true
            }
            val finished = AtomicBoolean(false)
            val started = SystemClock.elapsedRealtime()
            val deadline = scheduler.schedule({
                session?.interrupt()
                scheduler.schedule({ if (!finished.get()) terminateIsolatedProcess() }, 500, TimeUnit.MILLISECONDS)
            }, requestedTimeout, TimeUnit.MILLISECONDS)
            val result = try {
                val bytes = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readNBytes(8 * 1024 * 1024 + 1) }
                require(bytes.size <= 8 * 1024 * 1024) { "PACKAGE_SOURCE_LIMIT" }
                val payload = JSONObject(bytes.toString(Charsets.UTF_8))
                val bootstrap = "globalThis.__nexusApiVersion = ${JSONObject.quote(payload.optString("api_version", "1.0.1"))};\n" +
                    "globalThis.__nexusProviderMode = ${payload.optBoolean("provider_mode")};\n" +
                    "globalThis.__nexusUiStream = ${payload.optBoolean("stream_mode") && payload.optString("function") == "__nexus_ui__"};\n" +
                    "globalThis.__nexusMainEntry = ${payload.optString("main_entry").takeIf { payload.opt("main_entry") != JSONObject.NULL }?.let(JSONObject::quote) ?: "null"};\n" +
                    "globalThis.__nexusPackageId = ${JSONObject.quote(payload.optString("package_id"))};\n" +
                    assets.open("packages/nexus-package-sdk.js").bufferedReader().use { it.readText() }
                val httpSdk = assets.open("packages/operit-http.js").bufferedReader().use { it.readText() }
                PackageJsSession(memory, payload.optJSONArray("wasm_modules") ?: org.json.JSONArray(), providerMode = payload.optBoolean("provider_mode"),
                    intermediateMode = payload.optBoolean("stream_mode")) {
                    tool, arguments -> callHost(host, tool, arguments)
                }.use { runtime ->
                    session = runtime
                    runtime.invoke(bootstrap, httpSdk, payload.getJSONObject("scripts"), payload.getString("entry"),
                        payload.getString("function"), payload.getJSONObject("arguments"), started + requestedTimeout)
                }.put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            } catch (failure: Exception) {
                JSONObject().put("ok", false).put("code", "PACKAGE_EXECUTION_FAILED")
                    .put("message", failure.message.orEmpty().take(500))
            } finally {
                finished.set(true); deadline.cancel(false); session = null; busy.set(false)
                runCatching { descriptor.close() }
            }
            reply?.writeNoException(); reply?.writeString(result.toString())
            return true
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onUnbind(intent: Intent?): Boolean {
        session?.interrupt()
        if (busy.get()) scheduler.schedule(::terminateIsolatedProcess, 500, TimeUnit.MILLISECONDS)
        return false
    }
    override fun onDestroy() { session?.interrupt(); scheduler.shutdownNow(); super.onDestroy(); terminateIsolatedProcess() }

    private fun callHost(host: IBinder, tool: String, arguments: JSONObject): JSONObject {
        val input = Parcel.obtain(); val output = Parcel.obtain()
        try {
            input.writeInterfaceToken(HOST_DESCRIPTOR)
            input.writeString(tool); input.writeString(arguments.toString())
            check(host.transact(HOST_INVOKE, input, output, 0)) { "PACKAGE_HOST_DISCONNECTED" }
            output.readException()
            return JSONObject(requireNotNull(output.readString()))
        } finally { input.recycle(); output.recycle() }
    }

    private fun terminateIsolatedProcess() {
        if (!Process.isApplicationUid(Process.myUid()) ||
            io.github.mangi.eta.AppProcessPolicy.isPackageSandbox(android.app.Application.getProcessName(), packageName)) {
            Process.killProcess(Process.myPid())
        }
    }

    internal companion object {
        const val DESCRIPTOR = "io.github.zhh_lgtm.nexus.package.sandbox.v1"
        const val HOST_DESCRIPTOR = "io.github.zhh_lgtm.nexus.package.host.v1"
        const val INVOKE = IBinder.FIRST_CALL_TRANSACTION
        const val CANCEL = IBinder.FIRST_CALL_TRANSACTION + 1
        const val HELLO = IBinder.FIRST_CALL_TRANSACTION + 2
        const val HOST_INVOKE = IBinder.FIRST_CALL_TRANSACTION
    }
}
