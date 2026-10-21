package com.kuguan.app.data.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kuguan.app.KuguanApp
import com.kuguan.app.MainActivity
import com.kuguan.app.R
import com.kuguan.app.data.db.ReminderSettingsEntity
import com.kuguan.app.domain.reminder.ReminderEntry
import com.kuguan.app.domain.reminder.ReminderKind
import com.kuguan.app.domain.reminder.ReminderRules
import com.kuguan.app.domain.reminder.StockNoticeRules
import com.kuguan.app.domain.reminder.StockNoticeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import java.math.BigDecimal
import java.util.Base64

private val Context.reminderStore by preferencesDataStore("reminders")

/** 单进程串行化发送状态；DataStore 跨进程重启持久化。 */
class ReminderManager(
    private val context: Context,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val store = context.reminderStore
    private val alarm = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val dao get() = (context.applicationContext as KuguanApp).container.database.itemDao()
    private val settingsDao get() = (context.applicationContext as KuguanApp).container.database.reminderSettingsDao()

    /** The Room row is also the idempotent migration marker for the former DataStore setting. */
    suspend fun enabled(): Boolean {
        settingsDao.enabledOrNull()?.let { return it }
        val previous = store.data.first()[ENABLED] ?: true
        settingsDao.insertIfAbsent(ReminderSettingsEntity(enabled = previous))
        return settingsDao.enabledOrNull() ?: previous
    }

    suspend fun setEnabled(value: Boolean) {
        enabled() // migrate once before any explicit user change
        settingsDao.set(ReminderSettingsEntity(enabled = value))
        rebuildSchedules()
    }

    /** Rebuild alarms only after the Room restore transaction has committed. */
    suspend fun onSettingsRestored() {
        store.edit { it.remove(STOCK_STATES); it.remove(SNOOZE_STOCK_IDS) }
        rebuildSchedules()
    }

    private suspend fun rebuildSchedules() {
        val value = enabled()
        if (value) catchUp() else {
            alarm.cancel(pending(ACTION_DAILY, 1))
            alarm.cancel(pending(ACTION_SNOOZE_DELIVER, 2))
            notifications.cancel(NOTIFICATION_ID)
            store.edit { it.remove(SNOOZE_DUE) }
        }
    }

    suspend fun entries(): List<ReminderEntry> {
        val all = allEntries()
        refreshStockState(all)
        return ReminderRules.pageEntries(all, hiddenIds())
    }

    /** Call after any inventory or threshold change to rearm a resolved stock notice. */
    suspend fun refreshStockState() = refreshStockState(allEntries())

    private suspend fun refreshStockState(all: List<ReminderEntry>) = mutex.withLock {
        val old = readStockStates()
        val retained = StockNoticeRules.retainActive(all, old)
        if (retained != old) store.edit { it[STOCK_STATES] = encodeStockStates(retained) }
    }

    private suspend fun allEntries(): List<ReminderEntry> {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMillis()).atZone(zone).toLocalDate()
        return ReminderRules.evaluate(dao.reminderSources(), today, zone)
    }

    private suspend fun visibleEntries(all: List<ReminderEntry>): List<ReminderEntry> {
        val hidden = hiddenIds()
        return all.filterNot { it.id in hidden }
    }

    private suspend fun hiddenIds(): Set<String> {
        val today = Instant.ofEpochMilli(nowMillis()).atZone(ZoneId.systemDefault()).toLocalDate()
        return store.data.first().let { prefs ->
            if (prefs[HIDDEN_DATE] == today.toString()) prefs[HIDDEN_IDS].orEmpty().split('|').toSet() else emptySet()
        }
    }

    suspend fun dismiss(id: String) = mutex.withLock {
        val date = Instant.ofEpochMilli(nowMillis()).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        store.edit { prefs ->
            val existing = if (prefs[HIDDEN_DATE] == date) prefs[HIDDEN_IDS].orEmpty().split('|').filter { it.isNotBlank() } else emptyList()
            prefs[HIDDEN_DATE] = date
            prefs[HIDDEN_IDS] = (existing + id).distinct().joinToString("|")
        }
    }

    fun createChannel() {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "每日收纳提醒", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun scheduleDaily() {
        val now = Instant.ofEpochMilli(nowMillis()).atZone(ZoneId.systemDefault())
        val next = ReminderRules.nextDaily(now).toInstant().toEpochMilli()
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending(ACTION_DAILY, 1))
    }

    suspend fun restoreSnooze() {
        val due = snoozeDue() ?: return
        if (due > nowMillis()) alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pending(ACTION_SNOOZE_DELIVER, 2))
        else deliverSnooze()
    }

    /** 已持久化的稍后时间；供设置状态和端到端验收读取。 */
    internal suspend fun snoozeDue(): Long? = store.data.first()[SNOOZE_DUE]

    /** 前台补发只在 20:00 后运行；没权限/没内容不会占发送名额。 */
    suspend fun catchUp() {
        if (!enabled()) return
        scheduleDaily()
        restoreSnooze()
        if (!hasPermission()) return
        val now = Instant.ofEpochMilli(nowMillis()).atZone(ZoneId.systemDefault())
        val state = store.data.first()
        if (ReminderRules.dueForCatchUp(now, state[LAST_DATE])) deliverDaily()
    }

    suspend fun deliverDaily() = mutex.withLock {
        if (!enabled()) return@withLock
        scheduleDaily()
        val date = Instant.ofEpochMilli(nowMillis()).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val all = allEntries()
        val oldStates = readStockStates()
        val states = StockNoticeRules.retainActive(all, oldStates)
        if (states != oldStates) store.edit { it[STOCK_STATES] = encodeStockStates(states) }
        if (!hasPermission()) return@withLock
        val previous = store.data.first()[LAST_DATE]
        if (previous != null && date <= previous) return@withLock
        val visible = visibleEntries(all)
        val due = StockNoticeRules.dueIds(visible, states, LocalDate.parse(date))
        val current = visible.mapNotNull { entry -> if (entry.stockKind == null || entry.id in due) entry else entry.withoutStock() }
        if (current.isEmpty()) return@withLock
        if (post(current)) store.edit {
            it[LAST_DATE] = date
            it[STOCK_STATES] = encodeStockStates(StockNoticeRules.afterPosted(current, states, LocalDate.parse(date)))
            it[SNOOZE_STOCK_IDS] = current.filter { entry -> entry.stockKind != null }.mapTo(HashSet()) { entry -> entry.id }
        }
    }

    suspend fun snooze() = mutex.withLock {
        val today = Instant.ofEpochMilli(nowMillis()).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        if (!enabled() || store.data.first()[LAST_DATE] != today) return@withLock
        val due = ReminderRules.snoozeAt(nowMillis())
        store.edit { it[SNOOZE_DUE] = due }
        notifications.cancel(NOTIFICATION_ID)
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pending(ACTION_SNOOZE_DELIVER, 2))
    }

    suspend fun deliverSnooze() = mutex.withLock {
        val due = snoozeDue() ?: return@withLock
        if (nowMillis() < due || !enabled() || !hasPermission()) return@withLock
        val stockIds = store.data.first()[SNOOZE_STOCK_IDS].orEmpty()
        val current = visibleEntries(allEntries()).mapNotNull { entry ->
            if (entry.stockKind == null || entry.id in stockIds) entry else entry.withoutStock()
        }
        if (current.isNotEmpty() && post(current)) store.edit { it.remove(SNOOZE_DUE); it.remove(SNOOZE_STOCK_IDS) }
        else if (current.isEmpty()) store.edit { it.remove(SNOOZE_DUE); it.remove(SNOOZE_STOCK_IDS) }
    }

    private fun hasPermission(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private suspend fun readStockStates(): Map<String, StockNoticeState> =
        store.data.first()[STOCK_STATES].orEmpty().mapNotNull { encoded ->
            runCatching {
                val parts = encoded.split('|')
                if (parts.size != 4) return@runCatching null
                val id = String(Base64.getUrlDecoder().decode(parts[0]), Charsets.UTF_8)
                val kind = ReminderKind.valueOf(parts[1])
                if (kind != ReminderKind.LOW_STOCK && kind != ReminderKind.OUT_OF_STOCK) return@runCatching null
                val threshold = parts[2].toDouble()
                if (!threshold.isFinite() || threshold < 0) return@runCatching null
                id to StockNoticeState(kind, threshold, LocalDate.parse(parts[3]))
            }.getOrNull()
        }.toMap()

    private fun encodeStockStates(states: Map<String, StockNoticeState>): Set<String> = states.mapTo(HashSet()) { (id, state) ->
        "${Base64.getUrlEncoder().withoutPadding().encodeToString(id.toByteArray(Charsets.UTF_8))}|${state.kind}|${state.threshold}|${state.sentDate}"
    }

    private fun quantityText(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    private fun reasons(entry: ReminderEntry): String {
        val primary = when (entry.kind) {
            ReminderKind.EXPIRED -> "已过期"
            ReminderKind.EXPIRES_30, ReminderKind.EXPIRES_60 -> "还有 ${entry.days} 天到期"
            ReminderKind.COLD -> if (entry.days > 365) "${entry.days} 天未取用" else "从未取用"
            ReminderKind.LOW_STOCK -> "库存偏低，现存 ${quantityText(entry.quantity)}，提醒数量 ${quantityText(entry.threshold ?: 0.0)}"
            ReminderKind.OUT_OF_STOCK -> "已用完"
        }
        val stock = when (entry.inventoryKind) {
            ReminderKind.LOW_STOCK -> "库存偏低，现存 ${quantityText(entry.quantity)}，提醒数量 ${quantityText(entry.threshold ?: 0.0)}"
            ReminderKind.OUT_OF_STOCK -> "已用完"
            else -> null
        }
        return listOfNotNull(primary, stock).joinToString("；")
    }

    private fun post(entries: List<ReminderEntry>): Boolean = runCatching {
        createChannel()
        val open = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_REMINDERS, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openIntent = PendingIntent.getActivity(context, 3, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val snoozeIntent = pending(ACTION_SNOOZE_REQUEST, 4)
        val lines = entries.take(6).map { entry -> "${entry.location} ${entry.name}：${reasons(entry)}" }
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("今日收纳提醒 · ${entries.size} 条")
            .setContentText(lines.first())
            .setStyle(NotificationCompat.InboxStyle().also { style -> lines.forEach(style::addLine) })
            .setContentIntent(openIntent)
            .addAction(0, "稍后提醒（2 小时）", snoozeIntent)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        notifications.notify(NOTIFICATION_ID, builder.build())
        true
    }.getOrDefault(false)

    private fun pending(action: String, request: Int): PendingIntent = PendingIntent.getBroadcast(
        context, request, Intent(context, ReminderReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_DAILY = "com.kuguan.app.reminder.DAILY"
        const val ACTION_SNOOZE_REQUEST = "com.kuguan.app.reminder.SNOOZE_REQUEST"
        const val ACTION_SNOOZE_DELIVER = "com.kuguan.app.reminder.SNOOZE_DELIVER"
        const val CHANNEL = "daily_reminders"
        const val NOTIFICATION_ID = 2000
        private val mutex = Mutex()
        private val ENABLED = booleanPreferencesKey("enabled")
        private val LAST_DATE = stringPreferencesKey("last_notify_date")
        private val HIDDEN_DATE = stringPreferencesKey("hidden_date")
        private val HIDDEN_IDS = stringPreferencesKey("hidden_ids")
        private val SNOOZE_DUE = longPreferencesKey("snooze_due")
        private val SNOOZE_STOCK_IDS = stringSetPreferencesKey("snooze_stock_ids")
        private val STOCK_STATES = stringSetPreferencesKey("stock_notice_states")
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val manager = ReminderManager(context.applicationContext)
                when (intent.action) {
                    ReminderManager.ACTION_DAILY -> manager.deliverDaily()
                    ReminderManager.ACTION_SNOOZE_REQUEST -> manager.snooze()
                    ReminderManager.ACTION_SNOOZE_DELIVER -> manager.deliverSnooze()
                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                        if (manager.enabled()) {
                            manager.scheduleDaily()
                            manager.restoreSnooze()
                        }
                    }
                }
            } catch (error: Exception) {
                Log.e("ReminderReceiver", "提醒任务失败: ${error.javaClass.simpleName}")
            } finally { pendingResult.finish() }
        }
    }
}
