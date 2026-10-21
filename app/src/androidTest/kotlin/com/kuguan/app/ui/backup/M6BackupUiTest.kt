package com.kuguan.app.ui.backup

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kuguan.app.data.db.KuguanDatabase
import com.kuguan.app.ui.theme.KuguanTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class M6BackupUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun backupActionsAreVisibleInPortrait() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, KuguanDatabase::class.java).build()
        var show by mutableStateOf(true)
        try {
            compose.setContent { if (show) KuguanTheme { BackupScreen(PaddingValues(), db) } }
            compose.onNodeWithText("备份与恢复").assertExists()
            compose.onNodeWithText("应用存储").assertExists()
            compose.onNodeWithText("导出排查数据").assertExists()
            compose.onNodeWithText("清除异常排查数据").assertExists()
            compose.onNodeWithText("导出文件").assertExists()
            compose.onNodeWithText("导入文件").assertExists()
            compose.onNodeWithText("坚果云 WebDAV").assertExists()
            compose.onNodeWithText("自建服务器").assertExists()
        } finally {
            compose.runOnIdle { show = false }
            db.close()
        }
    }
}
