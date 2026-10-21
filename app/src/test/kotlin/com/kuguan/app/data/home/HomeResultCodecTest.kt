package com.kuguan.app.data.home

import com.kuguan.app.domain.query.ItemBrief
import com.kuguan.app.domain.query.ItemSnapshot
import com.kuguan.app.domain.query.LocationQty
import com.kuguan.app.domain.query.LookupResult
import com.kuguan.app.ui.home.ConfirmAction
import com.kuguan.app.ui.home.ConfirmBatch
import com.kuguan.app.ui.home.ConfirmRow
import com.kuguan.app.ui.home.Field
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HomeResultCodecTest {
    private val at = 1_760_000_000_000L
    private val first = ItemSnapshot("id-a", "7号电池", "工具", listOf(
        LocationQty("客厅抽屉左边", 2.5), LocationQty("备用箱三层", 3.0),
    ))
    private val second = ItemSnapshot("id-b", "七号充电电池", null,
        listOf(LocationQty("床头盒", 4.0)))

    @Test fun completeConfirmationRoundTrip() {
        val batch = ConfirmBatch(
            rows = listOf(
                ConfirmRow("row-1", ConfirmAction.PUT, "客厅抽屉左边", "7号电池", 2.5,
                    committedSeq = 42, corrected = true),
                ConfirmRow("row-2", ConfirmAction.TAKE, "备用箱三层", "螺丝", null,
                    uncertainFields = setOf(Field.Quantity, Field.Item)),
            ),
            batchId = "batch-a", heardText = "放入电池，取出螺丝", notice = "有一行待补录",
        )
        val snapshot = HomeResult.Confirmation(batch, at)
        assertEquals(snapshot, HomeResultCodec.decode(HomeResultCodec.encode(snapshot)!!))
    }

    @Test fun everyLookupKindRoundTripsWithStructureAndOrder() {
        val results = listOf(
            LookupResult.Found(first), LookupResult.Matches(listOf(second, first)),
            LookupResult.NoLocations(first.copy(locations = emptyList())),
            LookupResult.NotFound("七号电池", listOf(ItemBrief("id-b", "七号充电电池"),
                ItemBrief("id-a", "7号电池"))), LookupResult.NoName,
        )
        results.forEach { result ->
            val snapshot = HomeResult.Query(result, at)
            assertEquals(snapshot, HomeResultCodec.decode(HomeResultCodec.encode(snapshot)!!))
        }
    }

    @Test fun previewSequenceIsNeverSavedAndMalformedVersionIsRejected() {
        val preview = HomeResult.Confirmation(ConfirmBatch(listOf(
            ConfirmRow("debug", ConfirmAction.PUT, "A", "预览", 1.0, committedSeq = -1),
        )), at)
        assertNull(HomeResultCodec.encode(preview))
        val valid = HomeResultCodec.encode(HomeResult.Query(LookupResult.NoName, at))!!
        assertThrows(IllegalArgumentException::class.java) {
            HomeResultCodec.decode(valid.replace("\"version\":1", "\"version\":2"))
        }
    }
}
