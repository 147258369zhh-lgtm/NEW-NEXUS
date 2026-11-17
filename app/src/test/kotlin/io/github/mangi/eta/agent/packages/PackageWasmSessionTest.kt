package io.github.mangi.eta.agent.packages

import android.app.Application
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PackageWasmSessionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val binary = byteArrayOf(0, 97, 115, 109, 1, 0, 0, 0)
    private fun row() = JSONObject().put("id", "core").put("bytes", Base64.encodeToString(binary, Base64.NO_WRAP)).put("exports", JSONArray().put("add"))

    @Test fun declaredModuleAndExportUseOneBoundedEngineWithinTheInvocationAndCloseItOnce() {
        val bridge = Bridge()
        PackageWasmSession(JSONArray().put(row()), 16L * 1024 * 1024, bridge).use { session ->
            val request = JSONObject().put("module", "core").put("export", "add")
                .put("args", JSONArray().put(JSONObject().put("type", "i32").put("value", 42)))
            repeat(2) { assertTrue(session.call(request).contains("success")) }
            assertEquals(1, bridge.starts); assertEquals(1, bridge.loads); assertEquals(2, bridge.calls)
            assertEquals(16L * 1024 * 1024, bridge.memory); assertEquals(42L, bridge.bits.single())
            assertThrows(IllegalArgumentException::class.java) { session.call(JSONObject(request.toString()).put("export", "undeclared")) }
            assertThrows(IllegalArgumentException::class.java) { session.call(JSONObject(request.toString()).put("module", "elsewhere")) }
            session.interrupt(); assertEquals(1, bridge.interrupts)
            session.close(); session.close(); assertEquals(1, bridge.finishes)
            assertThrows(IllegalStateException::class.java) { session.call(request) }
        }
    }

    @Test fun numericArgumentsPreserveExactI64AndRejectUnsafeIntegersAndInfiniteFloats() {
        val args = JSONArray().put(JSONObject().put("type", "i64").put("value", Long.MAX_VALUE.toString()))
            .put(JSONObject().put("type", "f32").put("value", 1.25))
            .put(JSONObject().put("type", "f64").put("value", -2.5))
        val parsed = PackageWasmSession.arguments(args)
        assertArrayEquals(intArrayOf(2, 3, 4), parsed.first)
        assertEquals(Long.MAX_VALUE, parsed.second[0]); assertEquals(1.25f.toRawBits().toLong(), parsed.second[1]); assertEquals((-2.5).toRawBits(), parsed.second[2])
        listOf(JSONObject().put("type", "i64").put("value", "9223372036854775808"),
            JSONObject().put("type", "i64").put("value", 9007199254740992.0),
            JSONObject().put("type", "i32").put("value", 1.5),
            JSONObject().put("type", "f32").put("value", "1e100")).forEach {
            assertTrue(runCatching { PackageWasmSession.arguments(JSONArray().put(it)) }.isFailure)
        }
    }

    @Test fun realToolPkgImportCarriesOnlyDeclaredValidatedModuleBytesToTheSandboxSource() {
        val store = CapabilityPackageStore(context)
        val archive = archive("modules/core.wasm", binary)
        val installed = store.install(store.stage(Uri.fromFile(archive)))
        assertFalse(installed.issues.contains("WASM_MODULES_PENDING"))
        assertEquals(1, installed.wasmModuleCount)
        assertThrows(IllegalArgumentException::class.java) { store.configure(installed.id, memoryMb = 8) }
        store.configure(installed.id, enabled = true)
        val source = store.source(installed)
        assertEquals("exports.registerToolPkg = () => {};", source.getJSONObject("scripts").getString("main.js"))
        assertArrayEquals(binary, Base64.decode(source.getJSONArray("wasm_modules").getJSONObject(0).getString("bytes"), Base64.NO_WRAP))
        assertFalse(source.toString().contains("not-declared.wasm"))
        store.configure(installed.id, enabled = false)
        assertThrows(IllegalArgumentException::class.java) { store.source(installed) }
        assertEquals(1, store.source(installed, requireEnabled = false).getJSONArray("wasm_modules").length())
        val invalid = archive("modules/core.wasm", "not wasm".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { store.stage(Uri.fromFile(invalid)) }
        val traversal = archive("../escape.wasm", binary)
        assertThrows(IllegalArgumentException::class.java) { store.stage(Uri.fromFile(traversal)) }
    }

    private fun archive(path: String, contents: ByteArray): File {
        val file = File.createTempFile("wasm-import-", ".toolpkg", context.cacheDir)
        val manifest = JSONObject().put("toolpkg_id", "fixture.wasm").put("main", "main.js")
            .put("wasm_modules", JSONArray().put(JSONObject().put("id", "core").put("path", path).put("exports", JSONArray().put("add"))))
        ZipOutputStream(file.outputStream()).use { zip ->
            mapOf("manifest.json" to manifest.toString().toByteArray(), "main.js" to "exports.registerToolPkg = () => {};".toByteArray(),
                "modules/core.wasm" to contents, "modules/not-declared.wasm" to binary).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        return file
    }

    private class Bridge : PackageWasmBridge {
        var starts = 0; var loads = 0; var calls = 0; var finishes = 0; var interrupts = 0; var memory = 0L
        var bits = LongArray(0)
        override fun start(memoryBytes: Long) { starts++; memory = memoryBytes }
        override fun load(bytes: ByteArray): Long { loads++; return 101 }
        override fun call(handle: Long, name: String, types: IntArray, bits: LongArray): String {
            assertEquals(101L, handle); calls++; this.bits = bits
            return """{"success":true,"results":[{"type":"i32","value":42}]}"""
        }
        override fun interrupt() { interrupts++ }
        override fun finish() { finishes++ }
    }
}
