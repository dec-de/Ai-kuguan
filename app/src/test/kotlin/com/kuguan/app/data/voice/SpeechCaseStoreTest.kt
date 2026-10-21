package com.kuguan.app.data.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

class SpeechCaseStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun wavAndRawStagesStayInOneSessionAndExport() {
        val store = SpeechCaseStore(temp.root)
        val capture = store.startAudio("session-1")
        capture.append(byteArrayOf(1, 2, 3, 4))
        capture.append(byteArrayOf(5, 6))
        capture.finish()
        capture.finish()
        store.record("session-1", "ASR_RESULT", "一号抽屉\n录音 😀")
        store.record("session-1", "LLM_PRIMARY_RAW", "{\"items\":[]}")

        val wav = File(store.directory, "session-1.wav").readBytes()
        assertEquals(50, wav.size)
        assertEquals("RIFF", String(wav.copyOfRange(0, 4)))
        assertEquals("WAVE", String(wav.copyOfRange(8, 12)))
        assertEquals(16_000, leInt(wav, 24))
        assertEquals(32_000, leInt(wav, 28))
        assertEquals(6, leInt(wav, 40))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), wav.copyOfRange(44, 50))

        val lines = File(store.directory, "session-1.jsonl").readLines()
        assertEquals(2, lines.size)
        val first = Json.parseToJsonElement(lines[0]).jsonObject
        assertEquals("session-1", first.getValue("sessionId").jsonPrimitive.content)
        assertEquals("一号抽屉\n录音 😀", first.getValue("content").jsonPrimitive.content)
        assertEquals("LLM_PRIMARY_RAW", Json.parseToJsonElement(lines[1]).jsonObject
            .getValue("stage").jsonPrimitive.content)

        val archive = ByteArrayOutputStream()
        store.exportTo(archive)
        val names = mutableSetOf<String>()
        ZipInputStream(archive.toByteArray().inputStream()).use { zip ->
            while (true) {
                names += zip.nextEntry?.name ?: break
            }
        }
        assertEquals(setOf("session-1.wav", "session-1.jsonl"), names)
    }

    @Test fun clearOnlyRemovesEvidenceAndRecordingCanResume() {
        val store = SpeechCaseStore(temp.root)
        val inventory = File(temp.root, "kuguan.db").apply { writeText("valuable inventory") }
        val other = File(temp.root, "other.txt").apply { writeText("keep") }
        store.startAudio("one").finish()
        store.record("one", "ASR_RESULT", "text")
        File(temp.root, "speech-diagnostics.jsonl").writeText("old")
        assertTrue(store.sizeBytes() > 44)
        store.clear()
        assertEquals(0, store.sizeBytes())
        assertEquals("valuable inventory", inventory.readText())
        assertEquals("keep", other.readText())
        assertFalse(store.directory.exists())
        store.startAudio("two").finish()
        assertEquals(44, File(store.directory, "two.wav").length())
    }

    @Test fun unsafeSessionIdCannotEscapeEvidenceDirectory() {
        val store = SpeechCaseStore(temp.root)
        val rejected = runCatching { store.record("../outside", "ASR_RESULT", "x") }.isFailure
        assertTrue(rejected)
        assertFalse(File(temp.root.parentFile, "outside.jsonl").exists())
    }

    @Test fun latestFailedLlmRestoresExactTranscriptWithoutFallingBackToOlderCase() {
        val store = SpeechCaseStore(temp.root)
        val exact = "往 A113 放两个螺丝\n再放一个胶带 "
        store.record("failed", "ASR_RESULT", exact)
        store.record("failed", "LLM_PRIMARY_ERROR", "timeout")
        store.record("failed", "LLM_FALLBACK_ERROR", "timeout")
        store.record("failed", "PLAN", "kind=UNKNOWN source=UNRESOLVED rows=0")
        val failed = File(store.directory, "failed.jsonl")
        assertTrue(failed.setLastModified(1_000L))
        assertEquals(RetryableSpeech("failed", exact), store.latestRetryable())

        store.record("newer", "ASR_RESULT", "新的一条")
        store.record("newer", "LLM_PRIMARY_RAW", "{}")
        store.record("newer", "PLAN", "kind=PUT source=LLM rows=1")
        assertTrue(File(store.directory, "newer.jsonl").setLastModified(2_000L))
        assertEquals(null, store.latestRetryable())
    }

    @Test fun retryRecoveryRejectsIncompleteWrittenDismissedAndMalformedCases() {
        val store = SpeechCaseStore(temp.root)
        store.record("case", "ASR_RESULT", "文稿")
        store.record("case", "LLM_PRIMARY_ERROR", "timeout")
        assertEquals(null, store.latestRetryable())
        store.record("case", "PLAN", "kind=UNKNOWN source=UNRESOLVED rows=0")
        assertEquals(RetryableSpeech("case", "文稿"), store.latestRetryable())
        store.record("case", "WRITE_RESULT", "written=1")
        assertEquals(null, store.latestRetryable())
        assertTrue(File(store.directory, "case.jsonl").setLastModified(1_000L))

        store.record("retry", "RETRY_OF", "case")
        store.record("retry", "RETRY_TEXT", "  保留原文  ")
        store.record("retry", "LLM_PRIMARY_ERROR", "timeout")
        store.record("retry", "PLAN", "kind=UNKNOWN source=UNRESOLVED rows=0")
        File(store.directory, "retry.jsonl").appendText("not-json\n")
        assertTrue(File(store.directory, "retry.jsonl").setLastModified(3_000L))
        assertEquals(RetryableSpeech("retry", "  保留原文  "), store.latestRetryable())
        store.record("retry", "RETRY_DISMISSED", "user_cancelled")
        assertEquals(null, store.latestRetryable())
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8) or
            ((bytes[offset + 2].toInt() and 255) shl 16) or ((bytes[offset + 3].toInt() and 255) shl 24)
}
