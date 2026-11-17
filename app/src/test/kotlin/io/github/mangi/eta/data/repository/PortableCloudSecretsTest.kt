package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.model.CloudToolsCredentials
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableCloudSecretsTest {
    @Test fun everySingleCloudCredentialFieldRequiresEncryption() {
        val fixtures = listOf(
            "deepSeekApiKey" to CloudToolsCredentials(deepSeekApiKey = "test-key"),
            "bailianApiKey" to CloudToolsCredentials(bailianApiKey = "test-key"),
            "baiduApiKey" to CloudToolsCredentials(baiduApiKey = "test-key"),
            "baiduSecretKey" to CloudToolsCredentials(baiduSecretKey = "test-key"),
            "tencentSecretId" to CloudToolsCredentials(tencentSecretId = "test-id"),
            "tencentSecretKey" to CloudToolsCredentials(tencentSecretKey = "test-key"),
        )
        fixtures.forEach { (field, credentials) ->
            assertTrue("Missing sensitive backup field: $field",
                EtaPortableSecrets(cloudToolsCredentials = credentials).hasCredentials())
        }
    }

    @Test fun incompleteProviderCredentialsAreSensitiveEvenWhenNotConfigured() {
        val fixtures = listOf(
            "baidu" to CloudToolsCredentials(baiduApiKey = "test-key"),
            "baidu" to CloudToolsCredentials(baiduSecretKey = "test-key"),
            "tencent" to CloudToolsCredentials(tencentSecretId = "test-id"),
            "tencent" to CloudToolsCredentials(tencentSecretKey = "test-key"),
        )
        fixtures.forEach { (provider, credentials) ->
            assertFalse(credentials.configured(provider))
            assertTrue(EtaPortableSecrets(cloudToolsCredentials = credentials).hasCredentials())
        }
        assertFalse(EtaPortableSecrets().hasCredentials())
        assertFalse(EtaPortableSecrets(cloudToolsCredentials = CloudToolsCredentials()).hasCredentials())
        assertFalse(EtaPortableSecrets(cloudToolsCredentials = CloudToolsCredentials(
            deepSeekApiKey = " ", bailianApiKey = "\t", baiduApiKey = "\n",
            baiduSecretKey = " ", tencentSecretId = "\t", tencentSecretKey = "\n",
        )).hasCredentials())
    }

    @Test fun portableSerializationPreservesPartialCredentialsAndLegacyDefaults() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val credentials = CloudToolsCredentials(baiduSecretKey = "test-secret")
        val portable = EtaPortableSecrets(cloudToolsCredentials = credentials)
        val restored = json.decodeFromString<EtaPortableSecrets>(json.encodeToString(portable))
        assertEquals(credentials, restored.cloudToolsCredentials)
        assertTrue(restored.hasCredentials())
        assertFalse(requireNotNull(restored.cloudToolsCredentials).configured("baidu"))

        val legacy = json.decodeFromString<EtaPortableSecrets>("{}")
        assertNull(legacy.cloudToolsCredentials)
        assertFalse(legacy.hasCredentials())
    }
}
