package io.github.mangi.eta.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QqGroupInviteTest {
    @Test
    fun inviteTargetsGroupCardAndRejectsMalformedNumbers() {
        val uri = qqGroupInviteUri("1107693849")

        assertEquals("mqqapi", uri.scheme)
        assertEquals("card", uri.authority)
        assertEquals("show_pslcard", uri.lastPathSegment)
        assertEquals("1107693849", uri.getQueryParameter("uin"))
        assertEquals("group", uri.getQueryParameter("card_type"))
        assertEquals("1", uri.getQueryParameter("version"))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            qqGroupInviteUri("not-a-group")
        }
    }
}
