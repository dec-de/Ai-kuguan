package com.kuguan.app.domain.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderRulesTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 10, 18)
    private fun row(id: String, used: Long? = null, expiry: String? = null, location: String = "A-01") =
        ReminderSource(id, id, location, today.minusDays(400).atStartOfDay(zone).toInstant().toEpochMilli(), used, expiry, quantity = 1.0)

    @Test fun expiryBoundariesAndInvalidDates() {
        val rows = listOf(
            row("past", expiry = "2026-10-17"), row("today", expiry = "2026-10-18"),
            row("d30", expiry = "2026-11-17"), row("d31", expiry = "2026-11-18"),
            row("d60", expiry = "2026-12-17"), row("d61", expiry = "2026-12-18"),
            row("invalid", expiry = "2026-02-30"),
        )
        val found = ReminderRules.evaluate(rows, today, zone).associateBy { it.id }
        assertEquals(ReminderKind.EXPIRED, found["past"]?.kind)
        assertEquals(ReminderKind.EXPIRES_30, found["today"]?.kind)
        assertEquals(ReminderKind.EXPIRES_30, found["d30"]?.kind)
        assertEquals(ReminderKind.EXPIRES_60, found["d31"]?.kind)
        assertEquals(ReminderKind.EXPIRES_60, found["d60"]?.kind)
        assertEquals(ReminderKind.COLD, found["d61"]?.kind)
        assertEquals(ReminderKind.COLD, found["invalid"]?.kind)
    }

    @Test fun coldThresholdNeverUsedDedupAndCap() {
        fun used(days: Long) = today.minusDays(days).atStartOfDay(zone).toInstant().toEpochMilli()
        val rows = listOf(row("never"), row("edge", used(365)), row("old", used(366)), row("recent", used(364))) +
            (1..8).map { row("n$it") } + row("never", location = "B-02")
        val found = ReminderRules.evaluate(rows, today, zone)
        assertEquals(5, found.size)
        assertEquals(1, found.count { it.id == "never" })
        assertEquals("A-01、B-02", found.single { it.id == "never" }.location)
        assertFalse(found.any { it.id == "edge" || it.id == "recent" })
        assertTrue(found.any { it.id == "old" })
    }

    @Test fun leapDayAndLocalDateBoundaries() {
        val leap = LocalDate.of(2028, 2, 29)
        val found = ReminderRules.evaluate(listOf(row("leap", expiry = "2028-02-29")), leap, zone)
        assertEquals(ReminderKind.EXPIRES_30, found.single().kind)
        val almost = ZonedDateTime.of(2026, 10, 18, 19, 59, 59, 0, zone)
        assertEquals(today.atTime(20, 0).atZone(zone), ReminderRules.nextDaily(almost))
        assertEquals(today.plusDays(1).atTime(20, 0).atZone(zone), ReminderRules.nextDaily(almost.plusSeconds(1)))
        assertFalse(ReminderRules.dueForCatchUp(almost, null))
        assertTrue(ReminderRules.dueForCatchUp(almost.plusSeconds(1), null))
        assertFalse(ReminderRules.dueForCatchUp(almost.plusSeconds(1), today.toString()))
        assertFalse(ReminderRules.dueForCatchUp(almost.plusSeconds(1), today.plusDays(1).toString()))
        assertEquals(7_200_000L, ReminderRules.snoozeAt(0L))
    }

    @Test fun timezoneAndDstUseWallClock() {
        val ny = ZoneId.of("America/New_York")
        val beforeSpring = ZonedDateTime.of(2026, 3, 7, 20, 0, 0, 0, ny)
        val next = ReminderRules.nextDaily(beforeSpring)
        assertEquals(20, next.hour)
        assertEquals(LocalDate.of(2026, 3, 8), next.toLocalDate())
        assertEquals(23L, java.time.Duration.between(beforeSpring, next).toHours())
    }

    @Test fun stockThresholdAndZeroBoundary() {
        val base = row("battery", used = today.atStartOfDay(zone).toInstant().toEpochMilli())
        val off = base.copy(quantity = 0.0, restockThreshold = null)
        val zeroOnly = base.copy(quantity = 0.0, restockThreshold = 0.0)
        val low = base.copy(quantity = 1.5, restockThreshold = 1.5)
        val above = base.copy(quantity = 1.5000001, restockThreshold = 1.5)
        assertTrue(ReminderRules.evaluate(listOf(off), today, zone).isEmpty())
        assertEquals(ReminderKind.OUT_OF_STOCK, ReminderRules.evaluate(listOf(zeroOnly), today, zone).single().kind)
        assertEquals(ReminderKind.LOW_STOCK, ReminderRules.evaluate(listOf(low), today, zone).single().kind)
        assertTrue(ReminderRules.evaluate(listOf(above), today, zone).isEmpty())
        assertEquals(null, ReminderRules.stockKind(-1.0, null))
        assertEquals(null, ReminderRules.stockKind(0.0, Double.NaN))
    }

    @Test fun expiryAndStockShareOneCardWithoutLowZeroOverlap() {
        val source = row("battery", expiry = today.minusDays(1).toString())
            .copy(location = "", quantity = 0.0, restockThreshold = 2.0)
        val found = ReminderRules.evaluate(listOf(source), today, zone).single()
        assertEquals(ReminderKind.EXPIRED, found.kind)
        assertEquals(ReminderKind.OUT_OF_STOCK, found.inventoryKind)
        assertEquals(ReminderKind.OUT_OF_STOCK, found.stockKind)
        assertEquals("当前无在架抽屉", found.location)
        assertEquals(ReminderKind.EXPIRED, found.withoutStock()?.kind)
        assertEquals(null, found.withoutStock()?.inventoryKind)
        val afterDismiss = ReminderRules.pageEntries(listOf(found), setOf(found.id)).single()
        assertEquals(ReminderKind.OUT_OF_STOCK, afterDismiss.kind)
        assertEquals(null, afterDismiss.inventoryKind)
        assertEquals(found.id, afterDismiss.id)
        assertTrue(ReminderRules.pageEntries(listOf(found.withoutStock()!!), setOf(found.id)).isEmpty())
    }
}
