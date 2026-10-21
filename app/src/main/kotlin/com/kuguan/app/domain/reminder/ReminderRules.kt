package com.kuguan.app.domain.reminder

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

data class ReminderSource(
    val id: String,
    val name: String,
    val location: String,
    val createdAt: Long,
    val lastUsedAt: Long?,
    val expiryDate: String?,
    val quantity: Double = 0.0,
    val restockThreshold: Double? = null,
)

enum class ReminderKind { EXPIRED, EXPIRES_30, EXPIRES_60, COLD, LOW_STOCK, OUT_OF_STOCK }

data class ReminderEntry(
    val id: String,
    val name: String,
    val location: String,
    val kind: ReminderKind,
    val days: Long,
    val inventoryKind: ReminderKind? = null,
    val quantity: Double = 0.0,
    val threshold: Double? = null,
) {
    val stockKind: ReminderKind?
        get() = if (kind == ReminderKind.LOW_STOCK || kind == ReminderKind.OUT_OF_STOCK) kind else inventoryKind

    /** Omit a stock-only card when its stock notice is not due; keep other reasons. */
    fun withoutStock(): ReminderEntry? =
        if (kind == ReminderKind.LOW_STOCK || kind == ReminderKind.OUT_OF_STOCK) null else copy(inventoryKind = null)

    fun stockOnly(): ReminderEntry? = stockKind?.let { stock ->
        if (kind == stock) this else copy(kind = stock, days = 0, inventoryKind = null)
    }
}

object ReminderRules {
    /** A daily dismissal suppresses legacy reasons, never an unresolved stock condition. */
    fun pageEntries(entries: List<ReminderEntry>, hiddenIds: Set<String>): List<ReminderEntry> =
        entries.mapNotNull { entry -> if (entry.id in hiddenIds) entry.stockOnly() else entry }

    /** 每个物品只列一次；有效期优先于冷门，冷门至多五件，库存原因独立附加。 */
    fun evaluate(rows: List<ReminderSource>, today: LocalDate, zone: ZoneId): List<ReminderEntry> {
        val unique = rows.groupBy { it.id }.values.map { group ->
            group.first().copy(
                location = group.map { it.location }.filter { it.isNotBlank() }.distinct().joinToString("、"),
                quantity = group.sumOf { it.quantity },
            )
        }
        val expiry = unique.mapNotNull { row ->
            val date = runCatching { row.expiryDate?.let(LocalDate::parse) }.getOrNull() ?: return@mapNotNull null
            val days = ChronoUnit.DAYS.between(today, date)
            val kind = when {
                days < 0 -> ReminderKind.EXPIRED
                days <= 30 -> ReminderKind.EXPIRES_30
                days <= 60 -> ReminderKind.EXPIRES_60
                else -> return@mapNotNull null
            }
            ReminderEntry(row.id, row.name, row.location, kind, days)
        }
        val expiryIds = expiry.mapTo(HashSet()) { it.id }
        val cold = unique.asSequence().filter { it.id !in expiryIds && it.quantity > 0 }.mapNotNull { row ->
            val baselineDay = Instant.ofEpochMilli(row.lastUsedAt ?: row.createdAt).atZone(zone).toLocalDate()
            val days = ChronoUnit.DAYS.between(baselineDay, today)
            if (row.lastUsedAt == null || days > 365) ReminderEntry(row.id, row.name, row.location, ReminderKind.COLD, days) else null
        }.take(5).toList()
        val primary = (expiry + cold).associateBy { it.id }
        return unique.mapNotNull { row ->
            val stockKind = stockKind(row.quantity, row.restockThreshold)
            val location = row.location.ifBlank { "当前无在架抽屉" }
            val existing = primary[row.id]
            when {
                existing != null -> existing.copy(
                    location = location,
                    inventoryKind = stockKind,
                    quantity = row.quantity,
                    threshold = row.restockThreshold,
                )
                stockKind != null -> ReminderEntry(
                    row.id, row.name, location, stockKind, 0,
                    quantity = row.quantity, threshold = row.restockThreshold,
                )
                else -> null
            }
        }
    }

    fun stockKind(quantity: Double, threshold: Double?): ReminderKind? = when {
        threshold == null || !threshold.isFinite() || threshold < 0 || !quantity.isFinite() -> null
        quantity <= 0 -> ReminderKind.OUT_OF_STOCK
        threshold > 0 && quantity <= threshold -> ReminderKind.LOW_STOCK
        else -> null
    }

    fun nextDaily(now: ZonedDateTime): ZonedDateTime {
        val date = if (now.toLocalTime().isBefore(java.time.LocalTime.of(20, 0))) now.toLocalDate() else now.toLocalDate().plusDays(1)
        return LocalDateTime.of(date, java.time.LocalTime.of(20, 0)).atZone(now.zone)
    }

    fun dueForCatchUp(now: ZonedDateTime, lastSentDate: String?): Boolean =
        !now.toLocalTime().isBefore(java.time.LocalTime.of(20, 0)) &&
            (lastSentDate == null || now.toLocalDate().toString() > lastSentDate)

    fun snoozeAt(nowMillis: Long): Long = Math.addExact(nowMillis, 2 * 60 * 60 * 1000L)
}
