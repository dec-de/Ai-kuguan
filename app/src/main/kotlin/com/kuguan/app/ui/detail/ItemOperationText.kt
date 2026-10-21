package com.kuguan.app.ui.detail

import com.kuguan.app.data.db.OperationEntity
import com.kuguan.app.data.db.OperationType
import com.kuguan.app.domain.query.LookupText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 详情页只展示库存取放；审计、纠错行在全局历史页查看。 */
internal fun inventoryOperations(operations: List<OperationEntity>): List<OperationEntity> =
    operations.filter { it.type == OperationType.PUT || it.type == OperationType.TAKE }
        .sortedByDescending { it.seq }

internal fun operationTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))

internal fun operationAction(operation: OperationEntity): String =
    when (operation.type) {
        OperationType.PUT -> "放入"
        OperationType.TAKE -> "取出"
        else -> operation.type
    } + " " + (operation.quantity?.let { "${LookupText.quantity(it)} 个" } ?: "数量未记录")

internal fun operationLocation(operation: OperationEntity): String =
    operation.locationCode?.takeIf { it.isNotBlank() }?.let { location ->
        if (operation.type == OperationType.TAKE) "从 $location 取出" else "放进 $location"
    } ?: "位置未记录"
