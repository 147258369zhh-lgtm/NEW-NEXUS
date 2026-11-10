package io.github.mangi.eta.agent.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SocialConnectorPolicyTest {
    @Test
    fun requiresHttpsAndStrongToken() {
        assertEquals("https", SocialConnectorPolicy.validate(SocialConnectorConfig(SocialPlatform.QQ_BOT, "https://bot.example/v1", inboundToken = "1234567890123456")).scheme)
        assertThrows(IllegalArgumentException::class.java) {
            SocialConnectorPolicy.validate(SocialConnectorConfig(SocialPlatform.QQ_BOT, "http://bot.example", inboundToken = "short"))
        }
    }
}
