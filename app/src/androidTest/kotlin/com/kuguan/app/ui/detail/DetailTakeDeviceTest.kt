package com.kuguan.app.ui.detail

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kuguan.app.data.db.KuguanDatabase
import com.kuguan.app.data.db.OperationType
import com.kuguan.app.data.repo.StorageRepository
import com.kuguan.app.data.repo.TransactionRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetailTakeDeviceTest {
    @Test fun takeUpdatesSelectedLocationAndCanBeUndone() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, KuguanDatabase::class.java).build()
        try {
            val repo = StorageRepository(db.itemDao(), db.locationDao(), db.operationDao(),
                TransactionRunner { block -> db.withTransaction { block() } })
            val id = repo.put("测试物品", "A-01", 3.0).itemId!!
            repo.put("测试物品", "B-01", 2.0)
            assertFalse(repo.takeFromDetail(id, "A-01", 4.0))
            assertTrue(repo.takeFromDetail(id, "A-01", 2.0))
            assertEquals(1.0, db.locationDao().findLive(id, "A-01")!!.quantity, 0.0)
            assertEquals(2.0, db.locationDao().findLive(id, "B-01")!!.quantity, 0.0)
            val operation = db.operationDao().findLatestActive()!!
            assertEquals(OperationType.TAKE, operation.type)
            assertEquals(2.0, operation.quantity!!, 0.0)
            repo.revokeFromHistory(operation.seq)
            assertEquals(3.0, db.locationDao().findLive(id, "A-01")!!.quantity, 0.0)
        } finally { db.close() }
    }
}
