package com.kuguan.app.ui.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kuguan.app.data.db.KuguanDatabase
import com.kuguan.app.data.repo.StorageRepository
import com.kuguan.app.data.repo.TransactionRunner
import com.kuguan.app.ui.theme.KuguanTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RestockSettingUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun editAndPersistThreeRestockModesWithoutChangingInventory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, KuguanDatabase::class.java).build()
        var show by mutableStateOf(true)
        try {
            val repo = StorageRepository(db.itemDao(), db.locationDao(), db.operationDao(),
                TransactionRunner { block -> db.withTransaction { block() } })
            val itemId = runBlocking { repo.put("测试电池", "A-07", 2.5).itemId!! }
            compose.setContent { if (show) KuguanTheme(darkTheme = false) {
                ItemDetailScreen(repo, itemId, onBack = {})
            } }
            compose.onNodeWithText("编辑补货提醒").performScrollTo().performClick()
            compose.onNodeWithText("剩余指定数量时提醒").performScrollTo().performClick()
            compose.onNodeWithTag("restock-threshold-input").performTextInput("2.5")
            compose.onNodeWithText("保存补货提醒").performScrollTo().performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("剩余 2.5 个时提醒").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(2.5, runBlocking { repo.findItem(itemId)!!.restockThreshold!! }, 0.0)
            compose.onNodeWithText("现存 2.5 个，已达到提醒数量 2.5 个").assertExists()

            compose.onNodeWithText("编辑补货提醒").performScrollTo().performClick()
            compose.onNodeWithText("只在用完时提醒").performScrollTo().performClick()
            compose.onNodeWithText("保存补货提醒").performScrollTo().performClick()
            compose.waitUntil(5_000) {
                runBlocking { repo.findItem(itemId)?.restockThreshold == 0.0 }
            }

            compose.onNodeWithText("编辑补货提醒").performScrollTo().performClick()
            compose.onNodeWithText("关闭").performScrollTo().performClick()
            compose.onNodeWithText("保存补货提醒").performScrollTo().performClick()
            compose.waitUntil(5_000) {
                runBlocking { repo.findItem(itemId)?.restockThreshold == null }
            }
            assertEquals(2.5, runBlocking {
                repo.observeLocationsOf(itemId).first().sumOf { it.quantity }
            }, 0.0)
        } finally {
            compose.runOnIdle { show = false }
            compose.waitForIdle()
            db.close()
        }
    }
}
