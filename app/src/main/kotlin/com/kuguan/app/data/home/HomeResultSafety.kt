package com.kuguan.app.data.home

import com.kuguan.app.data.db.OperationEntity
import com.kuguan.app.domain.query.ItemSnapshot
import com.kuguan.app.domain.query.LocationQty
import com.kuguan.app.ui.home.ConfirmRow

/** A restored row may only keep actions when its exact stock operation is still active. */
object HomeResultSafety {
    fun operationStillMatches(row: ConfirmRow, batchId: String?, op: OperationEntity?): Boolean {
        val seq = row.committedSeq ?: return true
        return seq > 0 && op != null && op.seq == seq && op.revokedAt == null &&
            op.type == row.action.name && op.locationCode == row.locationCode &&
            op.quantity == row.quantity && op.batchId == batchId
    }

    fun queryItemChanged(saved: ItemSnapshot, currentName: String?, currentCategory: String?,
                         currentLocations: List<LocationQty>): Boolean =
        currentName != saved.name || currentCategory != saved.category ||
            currentLocations != saved.locations
}
