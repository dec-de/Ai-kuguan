package com.kuguan.app.data.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppStorageUsageDeviceTest {
    @Test fun caseFilesAreCountedAndClearedWithoutTouchingOtherData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(context.cacheDir, "storage-usage-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val cases = SpeechCaseStore(sandbox)
            val sentinel = File(sandbox, "inventory-sentinel.txt").apply { writeText("keep") }
            cases.startAudio("device-test").apply {
                append(ByteArray(3_200))
                finish()
            }
            cases.record("device-test", "ASR_RESULT", "测试查询")
            cases.record("device-test", "LLM_PRIMARY_RAW", "{\"intent\":\"query\"}")
            val before = AppStorageUsage(context, cases).read()
            assertTrue(before.caseBytes >= 3_244)
            assertTrue(before.dataBytes >= before.caseBytes)
            assertTrue(before.totalBytes >= before.dataBytes)

            cases.clear()
            val after = AppStorageUsage(context, cases).read()
            assertEquals(0, after.caseBytes)
            assertEquals("keep", sentinel.readText())
        } finally { sandbox.deleteRecursively() }
    }
}
