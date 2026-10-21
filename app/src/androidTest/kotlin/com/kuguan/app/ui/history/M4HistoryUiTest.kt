package com.kuguan.app.ui.history

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kuguan.app.data.db.KuguanDatabase
import com.kuguan.app.data.repo.StorageRepository
import com.kuguan.app.data.repo.TransactionRunner
import com.kuguan.app.ui.detail.ItemDetailScreen
import com.kuguan.app.ui.theme.KuguanTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 真 Compose + Room：回收站入口可见，点击恢复后列表更新。
 * 内存库在仪器测试进程退出时释放；测试方法结束时 Room 的观察任务可能仍在收尾。
 */
@RunWith(AndroidJUnit4::class)
class M4HistoryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun trashRestoresItemThroughUi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, KuguanDatabase::class.java).build()
        var show by mutableStateOf(true)
        try {
            val repo = StorageRepository(
                db.itemDao(), db.locationDao(), db.operationDao(),
                TransactionRunner { block -> db.withTransaction { block() } },
            )
            runBlocking {
                val op = repo.put("应急电池", "A-07", 2.0)
                repo.softDeleteItem(op.itemId!!)
            }
            compose.setContent {
                if (show) KuguanTheme {
                    HistoryScreen(repo, PaddingValues(), onBack = {}, onOpenItem = {})
                }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("回收站（1）", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("回收站（1）", useUnmergedTree = true)
                .assertExists().performClick()
            compose.onNodeWithText("应急电池").assertExists()
            compose.onNodeWithText("恢复").performClick()
            compose.waitUntil(5_000) {
                runBlocking { db.itemDao().findLiveByName("应急电池") != null }
            }
            compose.onNodeWithText("回收站是空的").assertExists()
            compose.onNodeWithText("历史").performClick()
            compose.onNodeWithText("单条 · 第 1 条").assertExists()
        } finally {
            compose.runOnIdle { show = false }
            compose.waitForIdle()
        }
    }

    @Test
    fun olderIndependentItemCanBeUndoneThroughHistoryUi() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, KuguanDatabase::class.java).build()
        var show by mutableStateOf(true)
        try {
            val repo = StorageRepository(
                db.itemDao(), db.locationDao(), db.operationDao(),
                TransactionRunner { block -> db.withTransaction { block() } },
            )
            runBlocking {
                repo.put("甲", "A-01", batchId = "one")
                repo.put("乙", "B-02", batchId = "two")
            }
            compose.setContent {
                if (show) KuguanTheme {
                    HistoryScreen(repo, PaddingValues(), onBack = {}, onOpenItem = {})
                }
            }
            compose.onNodeWithText("同一批 · 1 条 · 最新第 2 条").assertExists()
            compose.onAllNodesWithText("撤销")[1].performClick()
            compose.onNodeWithText("确认撤销").performClick()
            compose.waitUntil(5_000) {
                runBlocking { db.operationDao().findBySeq(1)!!.revokedAt != null }
            }
            compose.onNodeWithText("第 1 条已撤销").assertExists()
            runBlocking {
                check(db.operationDao().findBySeq(1)!!.revokedAt != null)
                check(db.operationDao().findBySeq(2)!!.revokedAt == null)
            }
        } finally {
            compose.runOnIdle { show = false }
            compose.waitForIdle()
        }
    }

    @Test
    fun detailPageCanMoveItemIntoTrash() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, KuguanDatabase::class.java).build()
        var show by mutableStateOf(true)
        try {
            val repo = StorageRepository(
                db.itemDao(), db.locationDao(), db.operationDao(),
                TransactionRunner { block -> db.withTransaction { block() } },
            )
            val id = runBlocking { repo.put("收纳盒", "C-03").itemId!! }
            compose.setContent {
                if (show) KuguanTheme { ItemDetailScreen(repo, id, onBack = {}) }
            }
            compose.onNodeWithText("移入回收站").performScrollTo().performClick()
            compose.onNodeWithText("移入回收站？").assertExists()
            compose.onAllNodesWithText("移入回收站")[1].performClick()
            compose.waitUntil(5_000) {
                runBlocking { db.itemDao().findById(id)!!.deletedAt != null }
            }
            runBlocking { check(db.locationDao().findLiveByItem(id).isEmpty()) }
        } finally {
            compose.runOnIdle { show = false }
            compose.waitForIdle()
        }
    }
}
