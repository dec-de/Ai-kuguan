package com.kuguan.app.data.repo

import com.kuguan.app.data.db.OperationEntity
import com.kuguan.app.data.db.OperationType
import com.kuguan.app.ui.history.groupHistory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M4HistoryTest {
    @Test
    fun `先放A再放B可单独撤销A且B不变`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        val a = repo.put("A物品", "同一抽屉", 2.0)
        val b = repo.put("B物品", "同一抽屉", 3.0)

        assertTrue(repo.revokeFromHistory(a.seq) is HistoryRevokeResult.Revoked)
        assertNotNull(db.operationDao.findBySeq(a.seq)!!.revokedAt)
        assertNull(db.locationDao.findLive(a.itemId!!, "同一抽屉"))
        assertNull(db.itemDao.findLiveById(a.itemId!!))
        assertNull(db.operationDao.findBySeq(b.seq)!!.revokedAt)
        assertEquals(3.0, db.locationDao.findLive(b.itemId!!, "同一抽屉")!!.quantity, 0.001)
        assertNotNull(db.itemDao.findLiveById(b.itemId!!))
        assertTrue(repo.revokeFromHistory(a.seq) is HistoryRevokeResult.Rejected)
    }

    @Test
    fun `同一物品不同位置的后续放入不阻挡撤销`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        val first = repo.put("电池", "左侧", 2.0)
        val second = repo.put("电池", "右侧", 4.0)

        assertTrue(repo.revokeFromHistory(first.seq) is HistoryRevokeResult.Revoked)
        assertNull(db.locationDao.findLive(first.itemId!!, "左侧"))
        assertEquals(4.0, db.locationDao.findLive(second.itemId!!, "右侧")!!.quantity, 0.001)
        assertNull(db.operationDao.findBySeq(second.seq)!!.revokedAt)
    }

    @Test
    fun `批号聚合且空批号各自独立，改写新行仍入原批`() {
        fun op(seq: Long, batch: String?) = OperationEntity(
            id = "op$seq", seq = seq, type = OperationType.PUT,
            batchId = batch, createdAt = seq,
        )
        val groups = groupHistory(listOf(op(1, "batch"), op(2, null), op(3, "other"),
            op(4, "batch").copy(revokedAt = 5), op(5, null), op(6, "batch")))
        assertEquals(listOf(6L, 5L, 3L, 2L), groups.map { it.latestSeq })
        assertEquals(listOf(6L, 4L, 1L), groups.first().operations.map { it.seq })
        assertEquals(4, groups.size)
    }

    @Test
    fun `跨批拒绝不修改库存，依次撤销后最后使用时间重算`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        val put = repo.put("电池", "A-07", 10.0, batchId = "one")
        val firstTake = repo.take("电池", "A-07", 2.0, batchId = "one")!!
        db.operationDao.forceAgeOperation(firstTake.seq, 60_000)
        val latestTake = repo.take("电池", "A-07", 3.0, batchId = "two")!!
        val itemId = put.itemId!!
        val before = db.itemDao.findById(itemId)!!.lastUsedAt
        val rejected = repo.revokeFromHistory(firstTake.seq)
        assertTrue(rejected is HistoryRevokeResult.Rejected)
        assertTrue((rejected as HistoryRevokeResult.Rejected).reason.contains("后续关联操作"))
        assertNull(db.operationDao.findBySeq(firstTake.seq)!!.revokedAt)
        assertEquals(5.0, db.locationDao.findLive(itemId, "A-07")!!.quantity, 0.001)
        assertEquals(before, db.itemDao.findById(itemId)!!.lastUsedAt)

        assertTrue(repo.revokeFromHistory(latestTake.seq) is HistoryRevokeResult.Revoked)
        assertEquals(firstTake.createdAt - 60_000, db.itemDao.findById(itemId)!!.lastUsedAt)
        assertTrue(repo.revokeFromHistory(firstTake.seq) is HistoryRevokeResult.Revoked)
        assertNull(db.itemDao.findById(itemId)!!.lastUsedAt)
        assertEquals(10.0, db.locationDao.findLive(itemId, "A-07")!!.quantity, 0.001)
        assertTrue(repo.revokeFromHistory(put.seq) is HistoryRevokeResult.Revoked)
        assertTrue(repo.revokeFromHistory(put.seq) is HistoryRevokeResult.Rejected)
        assertFalse(db.operationDao.exportAll().isEmpty())
    }

    @Test
    fun `审计操作不能作为库存操作撤销，最新库存仍可撤`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        val put = repo.put("电池", "A-07", 2.0)
        val item = db.itemDao.findById(put.itemId!!)!!
        val edit = repo.editItem(item.id, "新电池", emptyList(), null, null)
        assertTrue(edit is EditResult.Saved)
        val editOp = (edit as EditResult.Saved).operation
        val rejected = repo.revokeFromHistory(editOp.seq)
        assertTrue(rejected is HistoryRevokeResult.Rejected)
        assertTrue((rejected as HistoryRevokeResult.Rejected).reason.contains("审计"))
        assertTrue(repo.revokeFromHistory(put.seq) is HistoryRevokeResult.Revoked)
        assertNull(db.operationDao.findBySeq(editOp.seq)!!.revokedAt)
    }

    @Test
    fun `回收站仅恢复整件删除时仍在架的位置`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        val old = repo.put("工具", "A-01", 3.0)
        repo.take("工具", "A-01", 3.0)
        repo.put("工具", "B-02", 7.0)
        val itemId = old.itemId!!
        val oldDeletedAt = db.locationDao.findAnyAtLocation(itemId, "A-01")!!.deletedAt
        assertNotNull(oldDeletedAt)
        repo.softDeleteItem(itemId)
        val itemDeletedAt = db.itemDao.findById(itemId)!!.deletedAt
        assertTrue(itemDeletedAt!! > oldDeletedAt!!)
        repo.softDeleteItem(itemId) // 重复删除不得改变恢复标记
        assertEquals(itemDeletedAt, db.itemDao.findById(itemId)!!.deletedAt)

        assertEquals(RestoreResult.Restored, repo.restoreItem(itemId))
        assertTrue(repo.restoreItem(itemId) is RestoreResult.Rejected) // 重复恢复不得复活旧位置
        assertNotNull(db.itemDao.findLiveById(itemId))
        assertNull(db.locationDao.findLive(itemId, "A-01"))
        assertEquals(7.0, db.locationDao.findLive(itemId, "B-02")!!.quantity, 0.001)
        assertEquals(oldDeletedAt, db.locationDao.findAnyAtLocation(itemId, "A-01")!!.deletedAt)
    }

    @Test
    fun `同名新档案存在时拒绝恢复且不改变回收站`() = runTest {
        val db = FakeDb()
        val repo = db.repository()
        val old = repo.put("手电筒", "A-01")
        repo.softDeleteItem(old.itemId!!)
        repo.put("手电筒", "B-02")
        val denied = repo.restoreItem(old.itemId!!)
        assertTrue(denied is RestoreResult.Rejected)
        assertTrue((denied as RestoreResult.Rejected).reason.contains("同名"))
        assertNull(db.itemDao.findLiveById(old.itemId!!))
        assertNull(db.locationDao.findLive(old.itemId!!, "A-01"))
    }
}
