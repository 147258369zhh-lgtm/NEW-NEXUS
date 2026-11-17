package io.github.mangi.eta.agent.packages

import android.app.Application
import android.net.Uri
import io.github.mangi.eta.agent.capability.NexusRunHosts
import io.github.mangi.eta.agent.control.NexusSettingsControlPlane
import io.github.zhh_lgtm.nexus.application.api.InMemoryCapabilityDirectory
import io.github.zhh_lgtm.nexus.application.api.SettingsActorKind
import io.github.zhh_lgtm.nexus.kernel.api.EffectClass
import io.github.zhh_lgtm.nexus.kernel.api.HostKind
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class CapabilityPackageStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val store get() = CapabilityPackageStore(context)

    @Test fun allDistributedOperitScriptsCanBeReviewedAndInstalledWithoutExecutingOrGrantingTools() {
        val catalog = store.builtinNames()
        assertEquals(31, catalog.size)
        catalog.forEach { name ->
            val preview = store.stageBuiltin(name)
            val installed = store.install(preview)
            assertFalse(name, installed.enabled)
            assertTrue(installed.allowedTools.isEmpty())
            assertTrue(installed.digest.matches(Regex("[a-f0-9]{64}")))
        }
        assertEquals(31, store.list().size)
    }

    @Test fun updateKeepsPreviousVersionAndRollbackRequiresFreshEnableAndDeletionDoesNotRestoreIt() = runBlocking {
        val first = store.install(store.stage(script("1.0.0")))
        store.configure(first.id, enabled = true, allowedTools = setOf("read_file"))
        val second = store.install(store.stage(script("2.0.0")))
        assertFalse(second.enabled)
        assertTrue(second.allowedTools.isEmpty())
        assertEquals(first.digest, second.previous)
        store.rollback(second.id, second.digest)
        val rolledBack = store.list().single()
        assertEquals(first.digest, rolledBack.digest)
        assertFalse(rolledBack.enabled)
        store.delete(rolledBack.id, rolledBack.digest)
        assertTrue(store.list().isEmpty())
    }

    @Test fun previewCannotOverwriteAChangedVersionAndTraversalArchiveIsRejected() {
        val one = store.install(store.stage(script("1.0.0")))
        val two = store.stage(script("2.0.0"))
        val three = store.install(store.stage(script("3.0.0")))
        assertThrows(IllegalArgumentException::class.java) { store.install(two) }
        assertEquals(three.digest, store.list().single().digest)
        val archive = File.createTempFile("package-path-", ".zip", context.cacheDir)
        ZipOutputStream(archive.outputStream()).use { zip -> zip.putNextEntry(ZipEntry("../escape.js")); zip.write("bad".toByteArray()); zip.closeEntry() }
        assertThrows(IllegalArgumentException::class.java) { store.stage(Uri.fromFile(archive)) }
        assertFalse(File(context.cacheDir, "escape.js").exists())
        assertNotEquals(one.digest, three.digest)
    }

    @Test fun toolPkgArchiveKeepsRealScriptsAndResourcesAndIdentifiesUnadaptedRegistration() {
        val source = File(requireNotNull(script("1.0.0").path)).readText()
        val manifest = JSONObject().put("schema_version", 1).put("api_version", "1.0.0").put("toolpkg_id", "fixture.bundle")
            .put("main", "main.js").put("subpackages", JSONArray().put(JSONObject().put("id", "echo").put("entry", "packages/echo.js")))
        val archive = File.createTempFile("package-bundle-", ".toolpkg", context.cacheDir)
        ZipOutputStream(archive.outputStream()).use { zip ->
            mapOf("manifest.json" to manifest.toString(), "main.js" to "exports.registerToolPkg = () => true;",
                "packages/echo.js" to source, "resources/report.txt" to "resource-content").forEach { (path, text) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
            }
        }
        val preview = store.stage(Uri.fromFile(archive))
        assertEquals("sandbox", preview.content.kind)
        assertTrue(preview.content.issues.contains("MAIN_REGISTRATION_PENDING"))
        val installed = store.install(preview)
        store.configure(installed.id, enabled = true)
        val scripts = store.scripts(installed)
        assertTrue(scripts.has("main.js")); assertTrue(scripts.has("packages/echo.js"))
        assertFalse(scripts.has("resources/report.txt"))
        assertEquals("packages/echo.js", installed.tools.single().entry)
        assertEquals("resource-content", File(context.filesDir, "capability-packages/versions/${installed.id}/${installed.digest}/resources/report.txt").readText())
        store.saveRegistration(installed, JSONArray().put(JSONObject().put("kind", "NavigationEntry").put("id", "open-report")
            .put("title", "查看报告").put("action", JSONObject().put("nexus_callback", "NavigationEntry/open-report/action"))))
        val registered = store.list().single()
        assertFalse(registered.issues.contains("MAIN_REGISTRATION_PENDING"))
        assertEquals("NavigationEntry/open-report/action", registered.tools.single { it.callbackKey != null }.callbackKey)
        assertEquals("main.js", registered.mainEntry)
        val originalTool = registered.tools.single { it.callbackKey == null }
        assertEquals("echo", originalTool.name)
    }

    @Test fun packageSettingsUseTheRealAuditedControlPlaneAndRevocationRemovesRunTools() = runBlocking {
        store.ensureBuiltinTime()
        val record = store.list().single()
        val snapshot = PackageRunSnapshot.load(context)
        NexusRunHosts(InMemoryCapabilityDirectory(emptyList()), snapshot.bindings(context)).use { hosts ->
            val name = PackageRunSnapshot.toolName(record.id, record.tools.first())
            assertEquals(HostKind.PLUGIN, hosts.describe(name)!!.hostKind)
            assertEquals(EffectClass.READ, hosts.describe(name)!!.effectClass)
            assertTrue(hosts.isAvailable(name))
            val plane = NexusSettingsControlPlane.get(context)
            val changed = plane.applyJson("settings.packages.configure", JSONObject().put("package_id", record.id)
                .put("digest", record.digest).put("enabled", false).put("memory_mb", 32), SettingsActorKind.LOCAL_USER, "package-fixture")
            assertFalse(hosts.isAvailable(name))
            assertEquals(32, store.list().single().memoryMb)
            plane.rollback(changed.getString("auditId"), SettingsActorKind.LOCAL_USER)
            assertTrue(hosts.isAvailable(name))
            assertEquals(64, store.list().single().memoryMb)
        }
        store.delete(record.id, record.digest)
        store.ensureBuiltinTime()
        assertTrue(store.list().isEmpty())
    }

    private fun script(version: String): Uri {
        val metadata = JSONObject().put("name", "fixture").put("version", version).put("display_name", "Fixture")
            .put("tools", JSONArray().put(JSONObject().put("name", "echo").put("parameters", JSONArray()
                .put(JSONObject().put("name", "text").put("type", "string").put("required", true)))))
        val file = File.createTempFile("package-source-", ".js", context.cacheDir)
        file.writeText("/* METADATA\n$metadata\n*/\nexports.echo = async function(args) { return args.text; };\n")
        return Uri.fromFile(file)
    }
}
