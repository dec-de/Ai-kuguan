package com.kuguan.app.domain.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StockNoticeRulesTest {
    private val today = LocalDate.of(2026, 10, 20)
    private fun stock(kind: ReminderKind = ReminderKind.LOW_STOCK, threshold: Double = 2.0) =
        ReminderEntry("battery", "电池", "A-01", kind, 0, quantity = if (kind == ReminderKind.OUT_OF_STOCK) 0.0 else 1.0, threshold = threshold)

    @Test fun initialUpgradeSevenDayRepeatAndRecovery() {
        val low = stock()
        assertEquals(setOf(low.id), StockNoticeRules.dueIds(listOf(low), emptyMap(), today))
        val posted = StockNoticeRules.afterPosted(listOf(low), emptyMap(), today)
        assertTrue(StockNoticeRules.dueIds(listOf(low), posted, today.plusDays(6)).isEmpty())
        assertEquals(setOf(low.id), StockNoticeRules.dueIds(listOf(low), posted, today.plusDays(7)))
        val zero = stock(ReminderKind.OUT_OF_STOCK)
        assertEquals(setOf(zero.id), StockNoticeRules.dueIds(listOf(zero), posted, today.plusDays(1)))
        assertTrue(StockNoticeRules.retainActive(emptyList(), posted).isEmpty())
        assertEquals(setOf(low.id), StockNoticeRules.dueIds(listOf(low), StockNoticeRules.retainActive(emptyList(), posted), today.plusDays(2)))
    }

    @Test fun thresholdChangeRearmsAndUnpostedStateDoesNotAdvance() {
        val low = stock()
        val posted = StockNoticeRules.afterPosted(listOf(low), emptyMap(), today)
        val changed = stock(threshold = 3.0)
        assertEquals(setOf(low.id), StockNoticeRules.dueIds(listOf(changed), posted, today))
        assertTrue(StockNoticeRules.retainActive(listOf(changed), posted).isEmpty())
        assertEquals(posted, StockNoticeRules.afterPosted(emptyList(), posted, today.plusDays(1)))
        assertFalse(StockNoticeRules.dueIds(listOf(low), posted, today.plusDays(1)).contains(low.id))
    }
}
