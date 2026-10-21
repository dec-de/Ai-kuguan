package com.kuguan.app.data.voice

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialRedactorTest {
    @Test fun redactsCurrentKeysAndAuthorization() {
        val secret = "sentinel-key-12345"
        val text = "key=$secret Authorization: Bearer another-secret {\"api_key\":\"third-secret\"}"
        val safe = CredentialRedactor.redact(text, setOf(secret))
        assertFalse(safe.contains(secret))
        assertFalse(safe.contains("another-secret"))
        assertFalse(safe.contains("third-secret"))
        assertTrue(safe.contains("[REDACTED]"))
    }

    @Test fun exportRescansLegacyJsonlWithoutDroppingEvidence() {
        val dir = Files.createTempDirectory("speech-redaction").toFile()
        try {
            val secret = "sentinel-key-12345"
            val store = SpeechCaseStore(dir) { setOf(secret) }
            store.record("case-1", "ASR_RESULT", "电池在抽屉一；$secret")
            File(store.directory, "case-0.jsonl").writeText("{\"content\":\"Bearer $secret\"}\n")
            val output = ByteArrayOutputStream()
            store.exportTo(output)
            val zipText = StringBuilder()
            ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                while (zip.nextEntry != null) zipText.append(zip.readBytes().toString(Charsets.UTF_8))
            }
            assertFalse(zipText.contains(secret))
            assertTrue(zipText.contains("电池在抽屉一"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
