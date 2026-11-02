package com.kuguan.app.ui.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
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
class ItemDetailUiTest {
    // Room 观察任务可能晚于 Compose 空闲信号结束；内存库由仪器测试进程回收。
    @get:Rule val compose = createComposeRule()

    @Test fun detailsOpenInReadingModeAndCanEdit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, KuguanDatabase::class.java).build()
        var show by mutableStateOf(true)
        try {
            val repo = StorageRepository(db.itemDao(), db.locationDao(), db.operationDao(),
                TransactionRunner { block -> db.withTransaction { block() } })
            val id = runBlocking { repo.put("指甲钳", "A-01").itemId!! }
            compose.setContent { if (show) KuguanTheme(darkTheme = false) {
                ItemDetailScreen(repo, id, onBack = {})
            } }
            compose.onNodeWithText("编辑资料").assertExists().performClick()
            compose.onNodeWithText("保存").assertExists()
            compose.onNodeWithText("取消编辑").performClick()
            compose.onNodeWithText("编辑资料").assertExists()
        } finally {
            compose.runOnIdle { show = false }
            compose.waitForIdle()
        }
    }

    @Test fun takeFromSelectedLocationUpdatesInventoryAndHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, KuguanDatabase::class.java).build()
        var show by mutableStateOf(true)
        try {
            val repo = StorageRepository(db.itemDao(), db.locationDao(), db.operationDao(),
                TransactionRunner { block -> db.withTransaction { block() } })
            val id = runBlocking { repo.put("测试电池", "A-01", 3.0).itemId!! }
            compose.setContent { if (show) KuguanTheme(darkTheme = false) {
                ItemDetailScreen(repo, id, onBack = {})
            } }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("取出").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("取出").performClick()
            compose.onNodeWithText("取出数量").performTextClearance()
            compose.onNodeWithText("取出数量").performTextInput("4")
            compose.onNodeWithText("确认取出").performClick()
            compose.onNodeWithText("取出数量不能超过当前位置库存").assertExists()
            compose.onNodeWithText("取出数量").performTextClearance()
            compose.onNodeWithText("取出数量").performTextInput("2")
            compose.onNodeWithText("确认取出").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("已从 A-01 取出 2 个").fetchSemanticsNodes().isNotEmpty() }
            org.junit.Assert.assertEquals(1.0, runBlocking { db.locationDao().findLive(id, "A-01")!!.quantity }, 0.0)
            org.junit.Assert.assertEquals(com.kuguan.app.data.db.OperationType.TAKE,
                runBlocking { db.operationDao().findLatestActive()!!.type })
        } finally {
            compose.runOnIdle { show = false }
            compose.waitForIdle()
        }
    }

    @Test fun detailsShowFullPutTakeTimelineAndRevocation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, KuguanDatabase::class.java).build()
        var show by mutableStateOf(true)
        try {
            val repo = StorageRepository(db.itemDao(), db.locationDao(), db.operationDao(),
                TransactionRunner { block -> db.withTransaction { block() } })
            val put = runBlocking { repo.put("牛奶", "冰箱-上层", 3.0) }
            val take = runBlocking { repo.take("牛奶", "冰箱-上层", 1.0)!! }
            compose.setContent { if (show) KuguanTheme(darkTheme = false) {
                ItemDetailScreen(repo, put.itemId!!, onBack = {})
            } }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("取出 1 个").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("取出 1 个").assertExists()
            compose.onNodeWithText("从 冰箱-上层 取出").assertExists()
            compose.onNodeWithText("放入 3 个").assertExists()
            compose.onNodeWithText("放进 冰箱-上层").assertExists()
            org.junit.Assert.assertTrue(
                compose.onAllNodesWithText(operationTime(take.createdAt))
                    .fetchSemanticsNodes().isNotEmpty()
            )
            runBlocking { repo.revokeFromHistory(take.seq) }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("取出 1 个 · 已撤销").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("取出 1 个 · 已撤销").assertExists()
        } finally {
            compose.runOnIdle { show = false }
            compose.waitForIdle()
        }
    }
}
