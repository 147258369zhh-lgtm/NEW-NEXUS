package io.github.mangi.eta.agent.social

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SocialWebhookTest {
    @Test
    fun verifiesAndParsesBoundedInboundMessage() {
        val secret = "1234567890123456"
        val body = "{\"user_id\":\"u1\",\"message_id\":\"m1\",\"timestamp\":1700000000000,\"text\":\"你好\"}"
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), algorithm)) }
        val signature = mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
        assertTrue(SocialWebhook.verifySignature(secret, body, signature))
        assertFalse(SocialWebhook.verifySignature(secret, body, "bad"))
        assertTrue(SocialWebhook.parse(SocialPlatform.QQ_BOT, body).text == "你好")
    }

    @Test
    fun routerRejectsReplayAndStaleMessages() {
        val secret = "1234567890123456"
        val now = 1_700_000_000_000L
        val body = "{\"user_id\":\"u1\",\"message_id\":\"m1\",\"timestamp\":$now,\"text\":\"hello\"}"
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), algorithm)) }
        val signature = mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
        val config = SocialConnectorConfig(SocialPlatform.QQ_BOT, "https://bot.example/v1", enabled = true, inboundToken = secret)
        val guard = SocialInboundDeduplicator()
        assertEquals(SocialInboundDisposition.ACCEPT, SocialWebhookRouter.route(config, body, signature, guard, now).first)
        assertEquals(SocialInboundDisposition.DUPLICATE, SocialWebhookRouter.route(config, body, signature, guard, now).first)
        val staleBody = body.replace("$now", "${now - 11 * 60 * 1_000L}")
        val staleSignature = mac.doFinal(staleBody.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(SocialInboundDisposition.STALE, SocialWebhookRouter.route(config, staleBody, staleSignature, guard, now).first)
    }
}
