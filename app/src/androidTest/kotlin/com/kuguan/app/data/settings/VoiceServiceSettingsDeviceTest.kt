package com.kuguan.app.data.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VoiceServiceSettingsDeviceTest {
    @Test fun encryptedSnapshotSurvivesNewInstanceAndReportsCorruption() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = File(app.cacheDir, "voice-settings-test-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = isolated
        }
        try {
            val settings = VoiceServiceSettings(context)
            val config = VoiceServiceConfig(asrKey = "SENTINEL_ASR_KEY_123456",
                primaryUrl = "https://api.example.invalid", primaryKey = "SENTINEL_LLM_KEY_123456",
                fallbackUrl = "https://backup.example.invalid", fallbackModel = "test",
                fallbackKey = "SENTINEL_FALLBACK_KEY_123456")
            settings.save(config)
            val raw = File(isolated, "voice_service_settings.enc").readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(raw.contains("SENTINEL_ASR_KEY_123456"))
            assertFalse(raw.contains("SENTINEL_LLM_KEY_123456"))
            assertFalse(raw.contains("SENTINEL_FALLBACK_KEY_123456"))
            assertEquals(config, VoiceServiceSettings(context).read().config)
            assertEquals(3, settings.sensitiveValues().size)

            File(isolated, "voice_service_settings.enc").writeText("corrupted")
            assertTrue(VoiceServiceSettings(context).read().needsReentry)
            settings.clear()
            assertFalse(settings.read().needsReentry)
            assertEquals("", settings.read().config.asrKey)
        } finally {
            isolated.deleteRecursively()
        }
    }
}
