package com.kuguan.app.data.db

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kuguan.app.data.repo.HistoryRevokeResult
import com.kuguan.app.data.repo.StorageRepository
import com.kuguan.app.data.repo.TransactionRunner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 真 SQLite 验证撤销事务、历史门禁和回收站位置恢复。 */
@RunWith(AndroidJUnit4::class)
class M4RoomHistoryTest {
    @Test
    fun independentItemCanBeRevokedOutOfOrderInRoom() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, KuguanDatabase::class.java).build()
        try {
            val repo = StorageRepository(
                db.itemDao(), db.locationDao(), db.operationDao(),
                TransactionRunner { block -> db.withTransaction { block() } },
            )
            val a = repo.put("A物品", "同一抽屉", 2.0)
            val b = repo.put("B物品", "同一抽屉", 3.0)

            assertTrue(repo.revokeFromHistory(a.seq) is HistoryRevokeResult.Revoked)
            assertNotNull(db.operationDao().findBySeq(a.seq)!!.revokedAt)
            assertNull(db.locationDao().findLive(a.itemId!!, "同一抽屉"))
            assertNull(db.itemDao().findLiveById(a.itemId!!))
            assertNull(db.operationDao().findBySeq(b.seq)!!.revokedAt)
            assertEquals(3.0, db.locationDao().findLive(b.itemId!!, "同一抽屉")!!.quantity, 0.001)
        } finally {
            db.close()
        }
    }

    @Test
    fun revokeAndRestorePreserveRealRoomState() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, KuguanDatabase::class.java).build()
        try {
            val repo = StorageRepository(
                db.itemDao(), db.locationDao(), db.operationDao(),
                TransactionRunner { block -> db.withTransaction { block() } },
            )
            val first = repo.put("电池", "A-01", 8.0, batchId = "first")
            val second = repo.take("电池", "A-01", 3.0, batchId = "second")!!
            val id = first.itemId!!
            assertEquals("电池", db.operationDao().observeRecentWithNames(10).first().first().itemName)

            val denied = repo.revokeFromHistory(first.seq)
            assertTrue(denied is HistoryRevokeResult.Rejected)
            assertNull(db.operationDao().findBySeq(first.seq)!!.revokedAt)
            assertEquals(5.0, db.locationDao().findLive(id, "A-01")!!.quantity, 0.001)
            assertTrue(repo.revokeFromHistory(second.seq) is HistoryRevokeResult.Revoked)
            assertNull(db.itemDao().findById(id)!!.lastUsedAt)
            assertEquals(8.0, db.locationDao().findLive(id, "A-01")!!.quantity, 0.001)

            repo.put("电池", "B-02", 4.0)
            repo.take("电池", "B-02", 4.0)
            repo.softDeleteItem(id)
            assertEquals(1, db.itemDao().observeDeleted().first().size)
            repo.restoreItem(id)
            assertNotNull(db.itemDao().findLiveById(id))
            assertEquals(8.0, db.locationDao().findLive(id, "A-01")!!.quantity, 0.001)
            assertNull(db.locationDao().findLive(id, "B-02"))
            assertEquals(1, db.locationDao().findLiveByItem(id).size)
            assertEquals(4, db.operationDao().count())
        } finally {
            db.close()
        }
    }
}
