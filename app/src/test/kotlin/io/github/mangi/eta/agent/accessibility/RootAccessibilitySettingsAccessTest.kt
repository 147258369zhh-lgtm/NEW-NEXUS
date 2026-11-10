package io.github.mangi.eta.agent.accessibility

import android.app.Application
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import io.github.mangi.eta.agent.phone.PhoneProviderAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36, 37], application = Application::class)
class RootAccessibilitySettingsAccessTest {
    @Test
    fun readsAndWritesStayWithinTheAppUserAndNeverWriteTheMasterSwitch() {
        val access = FakeAccess()
        val settings = RootAccessibilitySettingsAccess(access, 12)
        assertEquals(AccessibilitySettingsSnapshot(true, "other/.Reader:eta/.Service"), settings.read())
        settings.writeServices("other/.Reader")
        assertEquals("other/.Reader", access.values["enabled_accessibility_services"])
        assertEquals(listOf("enabled_accessibility_services"), access.writtenKeys)
        assertTrue(access.users.isNotEmpty() && access.users.all { it == 12 })
    }

    @Test
    fun missingOrIncompatibleProviderDoesNotMasqueradeAsAnEmptyList() {
        val access = FakeAccess().apply { absentResponse = true }
        assertThrows(IllegalStateException::class.java) { RootAccessibilitySettingsAccess(access, 0).read() }
    }

    @Test
    fun writesMustBeReadBackAndUnchangedValuesAreRejected() {
        val access = FakeAccess().apply { ignoreWrites = true }
        assertThrows(IllegalStateException::class.java) {
            RootAccessibilitySettingsAccess(access, 0).writeServices("other/.Reader")
        }
    }

    @Test
    fun unknownMasterStateFailsClosedAndEmptyListsRemainReadable() {
        val access = FakeAccess()
        access.values["accessibility_enabled"] = "unknown"
        assertThrows(IllegalStateException::class.java) { RootAccessibilitySettingsAccess(access, 0).read() }
        access.values["accessibility_enabled"] = "0"
        access.values["enabled_accessibility_services"] = null
        assertEquals(AccessibilitySettingsSnapshot(false, ""), RootAccessibilitySettingsAccess(access, 0).read())
    }

    @Test
    fun colorOsRiskCloseExemptionAppendsOnlyThisPackageAndIsIdempotent() {
        val access = FakeAccess().apply {
            values["accessibility_turn_off_skip_package"] = "com.example.reader:com.example.screen"
        }
        val settings = RootAccessibilitySettingsAccess(access, 12)

        assertEquals(
            VendorRiskCloseExemptionOutcome.APPLIED,
            settings.ensureVendorRiskCloseExemption("fuck.andes"),
        )
        assertEquals(
            "com.example.reader:com.example.screen:fuck.andes",
            access.values["accessibility_turn_off_skip_package"],
        )
        assertEquals(
            VendorRiskCloseExemptionOutcome.ALREADY_EXEMPT,
            settings.ensureVendorRiskCloseExemption("fuck.andes"),
        )
        assertEquals(
            "com.example.reader:com.example.screen:fuck.andes",
            access.values["accessibility_turn_off_skip_package"],
        )
        assertTrue(access.users.all { it == 12 })
    }

    @Test
    fun colorOsRiskCloseExemptionDoesNotCreateTheVendorKeyOnOtherRoms() {
        val access = FakeAccess()
        val settings = RootAccessibilitySettingsAccess(access, 0)

        assertEquals(
            VendorRiskCloseExemptionOutcome.UNSUPPORTED,
            settings.ensureVendorRiskCloseExemption("fuck.andes"),
        )
        assertEquals(emptyList<String>(), access.writtenKeys)
    }

    private class FakeAccess : PhoneProviderAccess {
        override val callerPackage = "root"
        val values = mutableMapOf<String, String?>(
            "accessibility_enabled" to "1", "enabled_accessibility_services" to "other/.Reader:eta/.Service",
        )
        val writtenKeys = mutableListOf<String>()
        val users = mutableListOf<Int>()
        var absentResponse = false
        var ignoreWrites = false
        override fun call(uri: Uri, method: String, arg: String?, extras: Bundle): Bundle? {
            assertEquals("settings", uri.authority)
            users += extras.getInt("_user", -1)
            if (absentResponse) return null
            return when (method) {
                "GET_secure" -> Bundle().apply { putString("value", values[arg]) }
                "PUT_secure" -> {
                    writtenKeys += requireNotNull(arg)
                    if (!ignoreWrites) values[arg] = extras.getString("value")
                    null
                }
                else -> error("unexpected provider call")
            }
        }
        override fun query(uri: Uri, columns: Array<String>, selection: String?, args: Array<String>?, sort: String?): Cursor? = error("unexpected")
        override fun insert(uri: Uri, values: ContentValues): Uri? = error("unexpected")
        override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int = error("unexpected")
        override fun applyBatch(authority: String, operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> = error("unexpected")
    }
}
