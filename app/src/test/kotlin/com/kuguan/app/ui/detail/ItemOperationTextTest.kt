package com.kuguan.app.ui.detail

import com.kuguan.app.data.db.OperationEntity
import com.kuguan.app.data.db.OperationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemOperationTextTest {
    private fun op(seq: Long, type: String, quantity: Double? = 2.0,
                   location: String? = "A-01") = OperationEntity(
        id = "$seq", seq = seq, type = type, itemId = "milk",
        locationCode = location, quantity = quantity, createdAt = 0,
    )

    @Test fun inventoryTimelineKeepsAllPutTakeRowsIncludingRevokedInDescendingOrder() {
        val rows = inventoryOperations(listOf(
            op(1, OperationType.PUT), op(2, OperationType.EDIT),
            op(3, OperationType.TAKE).copy(revokedAt = 20),
            op(4, OperationType.CORRECTION), op(5, OperationType.PUT),
        ))
        assertEquals(listOf(5L, 3L, 1L), rows.map { it.seq })
        assertEquals(20L, rows[1].revokedAt)
    }

    @Test fun textShowsActionQuantityLocationAndSecondsWithoutInventingMissingValues() {
        assertEquals("放入 2 个", operationAction(op(1, OperationType.PUT)))
        assertEquals("放进 A-01", operationLocation(op(1, OperationType.PUT)))
        assertEquals("取出 1.5 个", operationAction(op(2, OperationType.TAKE, 1.5)))
        assertEquals("从 A-01 取出", operationLocation(op(2, OperationType.TAKE)))
        assertEquals("放入 数量未记录", operationAction(op(3, OperationType.PUT, null)))
        assertEquals("位置未记录", operationLocation(op(4, OperationType.TAKE, location = null)))
        assertTrue(operationTime(0).matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")))
    }
}
