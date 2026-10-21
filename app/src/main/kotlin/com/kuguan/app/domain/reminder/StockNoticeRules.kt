package com.kuguan.app.domain.reminder

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Notification history, not inventory truth. Only a successfully posted notice enters this map. */
data class StockNoticeState(val kind: ReminderKind, val threshold: Double, val sentDate: LocalDate)

object StockNoticeRules {
    private const val REPEAT_DAYS = 7L

    fun retainActive(entries: List<ReminderEntry>, previous: Map<String, StockNoticeState>): Map<String, StockNoticeState> {
        val active = entries.associateBy { it.id }
        return previous.filter { (id, state) ->
            val entry = active[id]
            entry?.stockKind == state.kind && entry.threshold == state.threshold
        }
    }

    fun dueIds(entries: List<ReminderEntry>, previous: Map<String, StockNoticeState>, today: LocalDate): Set<String> =
        entries.asSequence().filter { it.stockKind != null }.filter { entry ->
            val state = previous[entry.id]
            state == null || state.kind != entry.stockKind || state.threshold != entry.threshold ||
                ChronoUnit.DAYS.between(state.sentDate, today) >= REPEAT_DAYS
        }.mapTo(LinkedHashSet()) { it.id }

    fun afterPosted(
        delivered: List<ReminderEntry>,
        previous: Map<String, StockNoticeState>,
        today: LocalDate,
    ): Map<String, StockNoticeState> = previous.toMutableMap().also { state ->
        delivered.forEach { entry ->
            val kind = entry.stockKind ?: return@forEach
            val threshold = entry.threshold ?: return@forEach
            state[entry.id] = StockNoticeState(kind, threshold, today)
        }
    }
}
