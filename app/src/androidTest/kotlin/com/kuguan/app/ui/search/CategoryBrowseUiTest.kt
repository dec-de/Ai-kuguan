package com.kuguan.app.ui.search

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kuguan.app.data.db.KuguanDatabase
import com.kuguan.app.data.repo.StorageRepository
import com.kuguan.app.data.repo.TransactionRunner
import com.kuguan.app.ui.theme.KuguanTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CategoryBrowseUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun onlyOccupiedCategoriesShowAndSearchClearsFilter() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, KuguanDatabase::class.java).build()
        var show by mutableStateOf(true)
        try {
            val repo = StorageRepository(db.itemDao(), db.locationDao(), db.operationDao(),
                TransactionRunner { block -> db.withTransaction { block() } })
            runBlocking {
                repo.put("电池", "A-01")
                repo.put("螺丝", "B-02")
            }
            compose.setContent { if (show) KuguanTheme(darkTheme = false) {
                SearchScreen(repo, PaddingValues(), onBack = {}, onOpenItem = {})
            } }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("按类别查看").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("厨房餐具").assertDoesNotExist()
            compose.onAllNodesWithText("工具五金")[0].performClick()
            compose.onNodeWithText("螺丝").assertExists()
            compose.onNodeWithText("电池").assertDoesNotExist()
            compose.onNodeWithText("物品名或别名").performTextInput("电池")
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("电池").fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            compose.runOnIdle { show = false }
            db.close()
        }
    }
}
