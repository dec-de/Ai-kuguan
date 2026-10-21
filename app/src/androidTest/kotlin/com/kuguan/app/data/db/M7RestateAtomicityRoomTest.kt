package com.kuguan.app.data.db

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kuguan.app.data.repo.StorageRepository
import com.kuguan.app.data.repo.TransactionRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** 真 Room 事务：替换 TAKE 写入失败时，撤销旧 TAKE 也必须回滚。 */
@RunWith(AndroidJUnit4::class)
class M7RestateAtomicityRoomTest {
    private suspend fun verifyRejectedReplacement(newName: String, newLocation: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, KuguanDatabase::class.java).build()
        try {
            val repo = StorageRepository(db.itemDao(), db.locationDao(), db.operationDao(),
                TransactionRunner { block -> db.withTransaction { block() } })
            val put = repo.put("电池", "A-07", 5.0, batchId = "first")
            val oldTake = repo.take("电池", "A-07", 2.0, batchId = "second")!!
            val beforeItems = db.itemDao().exportAll()
            val beforeLocations = db.locationDao().exportAll()
            val beforeOperations = db.operationDao().exportAll()
            assertEquals(3.0, db.locationDao().findLive(put.itemId!!, "A-07")!!.quantity, 0.0)

            assertNull(repo.restateRow(oldTake.seq, newName, newLocation, 2.0))

            assertEquals(beforeItems, db.itemDao().exportAll())
            assertEquals(beforeLocations, db.locationDao().exportAll())
            assertEquals(beforeOperations, db.operationDao().exportAll())
            assertNull(db.operationDao().findBySeq(oldTake.seq)!!.revokedAt)
            assertEquals(3.0, db.locationDao().findLive(put.itemId, "A-07")!!.quantity, 0.0)
            assertEquals(oldTake.createdAt, db.itemDao().findById(put.itemId)!!.lastUsedAt)
        } finally { db.close() }
    }

    @Test fun missingTargetLocationRollsBackOldTake() = runBlocking {
        verifyRejectedReplacement("电池", "B-02")
    }

    @Test fun missingTargetItemRollsBackOldTake() = runBlocking {
        verifyRejectedReplacement("不存在的物品", "A-07")
    }
}
