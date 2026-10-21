package com.kuguan.app.ui.config

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kuguan.app.data.db.KuguanDatabase
import com.kuguan.app.data.settings.VoiceConnectionTester
import com.kuguan.app.data.settings.VoiceServiceSettings
import com.kuguan.app.ui.backup.BackupScreen
import com.kuguan.app.ui.backup.BackupSection
import com.kuguan.app.ui.theme.KuguanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConfigCenterUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun directoryOpensEachSection() {
        var opened = ""
        compose.setContent {
            KuguanTheme {
                ConfigDirectoryScreen(PaddingValues(),
                    onVoiceAi = { opened = "voice" },
                    onBackup = { opened = "backup" },
                    onStorage = { opened = "storage" })
            }
        }
        compose.onNodeWithText("语音与 AI").performClick()
        compose.runOnIdle { assertEquals("voice", opened) }
        compose.onNodeWithText("备份与恢复").performClick()
        compose.runOnIdle { assertEquals("backup", opened) }
        compose.onNodeWithText("存储与排查").performClick()
        compose.runOnIdle { assertEquals("storage", opened) }
    }

    @Test fun voicePageShowsOptionalFallbackAndUnsavedTestNotice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = VoiceServiceSettings(context)
        settings.clear()
        try {
            compose.setContent {
                KuguanTheme {
                    VoiceAiSettingsScreen(PaddingValues(), settings, VoiceConnectionTester(), {}, {})
                }
            }
            compose.onNodeWithText("阿里云 ASR").assertExists()
            compose.onNodeWithText("DeepSeek 主线路").assertExists()
            compose.onNodeWithText("备用 LLM（非必填）").assertExists()
            compose.onNodeWithText("测试备用线路连接").assertIsNotEnabled()
            compose.onNodeWithText("连接测试使用当前表单值，可能产生少量服务费用；测试成功不会自动保存。").assertExists()
        } finally {
            settings.clear()
        }
    }

    @Test fun backupAndStorageSecondariesKeepOriginalActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, KuguanDatabase::class.java).build()
        var section by mutableStateOf(BackupSection.BACKUP)
        try {
            compose.setContent {
                KuguanTheme { BackupScreen(PaddingValues(), db, section) }
            }
            compose.onNodeWithText("导出文件").assertExists()
            compose.onNodeWithText("导入文件").assertExists()
            compose.onNodeWithText("坚果云 WebDAV").assertExists()
            compose.onNodeWithText("自建服务器").assertExists()
            compose.runOnIdle { section = BackupSection.STORAGE }
            compose.onNodeWithText("应用存储").assertExists()
            compose.onNodeWithText("导出排查数据").assertExists()
            compose.onNodeWithText("清除异常排查数据").assertExists()
        } finally {
            db.close()
        }
    }
}
