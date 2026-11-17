package io.github.mangi.eta.fork.meeting

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingVerificationEvidenceTest {
    @Test fun onlyAnExplicitFailureRequestsRework() {
        assertTrue(meetingVerificationReportsFailure("""{"status":"fail"}"""))
        assertFalse(meetingVerificationReportsFailure("""{"status":"pass","evidence":["checked"]}"""))
        assertFalse(meetingVerificationReportsFailure("""{"status":"blocked"}"""))
        assertFalse(meetingVerificationReportsFailure("invalid"))
    }
}
