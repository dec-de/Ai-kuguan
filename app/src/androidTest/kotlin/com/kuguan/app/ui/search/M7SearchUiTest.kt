package com.kuguan.app.ui.search

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kuguan.app.data.db.KuguanDatabase
import com.kuguan.app.data.repo.StorageRepository
import com.kuguan.app.data.repo.TransactionRunner
import com.kuguan.app.ui.theme.KuguanTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class M7SearchUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun localEmptyAndNoMatchStatesAreReadable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, KuguanDatabase::class.java).build()
        var show by mutableStateOf(true)
        try {
            val repo = StorageRepository(db.itemDao(), db.locationDao(), db.operationDao(),
                TransactionRunner { block -> db.withTransaction { block() } })
            compose.setContent { if (show) KuguanTheme(darkTheme = false) {
                SearchScreen(repo, PaddingValues(), onBack = {}, onOpenItem = {})
            } }
            compose.onNodeWithText("输入关键词开始搜索").assertExists()
            compose.onNodeWithText("物品名或别名").performTextInput("完全不存在")
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("没有匹配的物品").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("没有匹配的物品").assertExists()
        } finally {
            compose.runOnIdle { show = false }
            db.close()
        }
    }
}
