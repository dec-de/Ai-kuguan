package com.kuguan.app.data.home

import com.kuguan.app.data.db.OperationEntity
import com.kuguan.app.domain.query.ItemSnapshot
import com.kuguan.app.domain.query.LocationQty
import com.kuguan.app.ui.home.ConfirmAction
import com.kuguan.app.ui.home.ConfirmRow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeResultSafetyTest {
    private val row = ConfirmRow("r", ConfirmAction.PUT, "原话的自由编号", "电池", 2.0,
        committedSeq = 7L)
    private val op = OperationEntity(id = "op", seq = 7L, type = "PUT", itemId = "item",
        locationCode = "原话的自由编号", quantity = 2.0, batchId = "batch", createdAt = 1L)

    @Test fun restoredActionsRequireExactLiveOperation() {
        assertTrue(HomeResultSafety.operationStillMatches(row, "batch", op))
        assertFalse(HomeResultSafety.operationStillMatches(row, "batch", null))
        assertFalse(HomeResultSafety.operationStillMatches(row, "batch", op.copy(revokedAt = 2L)))
        assertFalse(HomeResultSafety.operationStillMatches(row, "batch", op.copy(type = "EDIT")))
        assertFalse(HomeResultSafety.operationStillMatches(row, "batch", op.copy(quantity = 3.0)))
        assertFalse(HomeResultSafety.operationStillMatches(row, "other", op))
        assertFalse(HomeResultSafety.operationStillMatches(row.copy(committedSeq = -7L), "batch", op))
        assertTrue(HomeResultSafety.operationStillMatches(row.copy(committedSeq = null), "batch", null))
    }

    @Test fun queryWarnsWhenQuantityPositionOrIdentityChanges() {
        val snapshot = ItemSnapshot("item", "电池", "配件",
            listOf(LocationQty("抽屉", 2.0)))
        assertFalse(HomeResultSafety.queryItemChanged(snapshot, "电池", "配件", snapshot.locations))
        assertTrue(HomeResultSafety.queryItemChanged(snapshot, "电池", "配件",
            listOf(LocationQty("抽屉", 1.0))))
        assertTrue(HomeResultSafety.queryItemChanged(snapshot, "电池", "配件",
            listOf(LocationQty("箱子", 2.0))))
        assertTrue(HomeResultSafety.queryItemChanged(snapshot, null, null, emptyList()))
    }
}
