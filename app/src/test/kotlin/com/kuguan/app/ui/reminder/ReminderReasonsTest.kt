package com.kuguan.app.ui.reminder

import com.kuguan.app.domain.reminder.ReminderEntry
import com.kuguan.app.domain.reminder.ReminderKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderReasonsTest {
    @Test fun expiryAndLowStockAreShownInOneCard() {
        val entry = ReminderEntry("id", "电池", "A-07、B-05", ReminderKind.EXPIRED, -2,
            inventoryKind = ReminderKind.LOW_STOCK, quantity = 2.0, threshold = 2.0)
        assertEquals(listOf("已过期 2 天", "现存 2 个，已达到提醒数量 2 个"), reasons(entry))
    }

    @Test fun zeroStockDoesNotAlsoSayLowStock() {
        val entry = ReminderEntry("id", "电池", "当前无在架抽屉", ReminderKind.OUT_OF_STOCK, 0,
            inventoryKind = ReminderKind.OUT_OF_STOCK, quantity = 0.0, threshold = 2.0)
        assertEquals(listOf("已用完"), reasons(entry))
    }
}
