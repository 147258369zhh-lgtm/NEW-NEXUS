package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechCredentialField
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableSpeechSecretsTest {
    @Test fun everySpeechCredentialFieldRequiresEncryption() {
        SpeechCredentialField.entries.forEach { field ->
            assertTrue("Missing sensitive backup field: $field",
                EtaPortableSecrets(speechCredentials = SpeechCredentials().withValue(field, "test-key")).hasCredentials())
        }
    }
    @Test fun azureOnlyCredentialsRequireEncryptedBackup() {
        assertTrue(EtaPortableSecrets(speechCredentials = SpeechCredentials(azureAsr = "asr-key")).hasCredentials())
        assertTrue(EtaPortableSecrets(speechCredentials = SpeechCredentials(azureTts = "tts-key")).hasCredentials())
        assertFalse(EtaPortableSecrets(speechCredentials = SpeechCredentials()).hasCredentials())
    }
}
