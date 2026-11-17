package io.github.mangi.eta.agent.model

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class GgufModelMetadataTest {
    private fun fixture(name: String = "MiniCPM4-0.5B-QAT-Int4", template: String = "<|im_start|>{{message}}<|im_end|>",
        extra: (DataOutputStream.() -> Unit)? = null): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            fun int(value: Int) = output.writeInt(Integer.reverseBytes(value))
            fun long(value: Long) = output.writeLong(java.lang.Long.reverseBytes(value))
            fun string(value: String) { val text = value.toByteArray(); long(text.size.toLong()); output.write(text) }
            output.writeBytes("GGUF"); int(3); long(1); long(if (extra == null) 5 else 6)
            for ((key, value) in mapOf("general.architecture" to "minicpm", "general.name" to name,
                "tokenizer.ggml.model" to "llama", "tokenizer.chat_template" to template)) {
                string(key); int(8); string(value)
            }
            string("tokenizer.ggml.tokens"); int(9); int(8); long(2); string("<s>"); string("<|im_end|>")
            if (extra != null) { string("tokenizer.ggml.scores"); extra(output) }
        }
        return bytes.toByteArray()
    }

    private fun <T> inspect(bytes: ByteArray, action: (GgufModelMetadata) -> T): T {
        val file = Files.createTempFile("gguf-metadata", ".gguf").toFile()
        return try { file.writeBytes(bytes); action(GgufModelMetadata.inspect(file)) } finally { file.delete() }
    }

    @Test fun readsMiniCpmMetadataAndSkipsVocabularyArraysWithoutReadingTensors() {
        val bytes = fixture(extra = {
            writeInt(Integer.reverseBytes(9)); writeInt(Integer.reverseBytes(6))
            writeLong(java.lang.Long.reverseBytes(2)); writeInt(0); writeInt(0)
        })
        inspect(bytes) { metadata ->
            assertTrue(metadata.isMiniCpm); assertEquals("minicpm", metadata.architecture)
            assertEquals(2L, metadata.vocabularySize)
            assertEquals("llama", metadata.tokenizer); metadata.validateTextRuntime()
        }
    }

    @Test fun rejectsMissingChatTemplateAndMultimodalMiniCpmInsteadOfClaimingTextCompatibility() {
        for (bytes in listOf(fixture(template = ""), fixture(name = "MiniCPM-V-2.6"),
            fixture(name = "MiniCPM-o-2.6"), fixture(name = "MiniCPM-ASR"))) {
            assertThrows(IllegalArgumentException::class.java) { inspect(bytes) { it.validateTextRuntime() } }
        }
    }

    @Test fun rejectsTruncatedAndOversizedMetadataArraysBeforeAllocatingThem() {
        assertThrows(IllegalArgumentException::class.java) { inspect(fixture().copyOf(20)) {} }
        val oversized = fixture(extra = {
            writeInt(Integer.reverseBytes(9)); writeInt(Integer.reverseBytes(8))
            writeLong(java.lang.Long.reverseBytes(Long.MAX_VALUE))
        })
        assertThrows(IllegalArgumentException::class.java) { inspect(oversized) {} }
    }
}
