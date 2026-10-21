package com.kuguan.app.data.reminder

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kuguan.app.KuguanApp
import com.kuguan.app.R
import com.kuguan.app.data.db.ItemEntity
import com.kuguan.app.data.db.LocationRecordEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.time.LocalDate
import java.util.UUID

/** 真正的通知操作 PendingIntent → Receiver → DataStore / AlarmManager / 通知链路。 */
@RunWith(AndroidJUnit4::class)
class M5SnoozeIntegrationTest {
    @Test fun actionCancelsAndSchedulesThenRedeliversAtDueTime() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val manager = ReminderManager(context)
        val wasEnabled = manager.enabled()
        val db = (context.applicationContext as KuguanApp).container.database
        val id = "m5-snooze-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        try {
            manager.setEnabled(true)
            db.itemDao().upsert(ItemEntity(id, "稍后提醒验收样本", expiryDate = LocalDate.now().minusDays(1).toString(),
                createdAt = now, updatedAt = now))
            db.locationDao().upsert(LocationRecordEntity("$id-location", id, "T-01", createdAt = now, updatedAt = now))

            // 首次运行由正式每日逻辑生成；同日重复运行时 LAST_DATE 已持久化，补一个同操作的通知。
            manager.deliverDaily()
            if (active(notifications) == null) {
                manager.createChannel()
                val action = PendingIntent.getBroadcast(context, 4,
                    Intent(context, ReminderReceiver::class.java).setAction(ReminderManager.ACTION_SNOOZE_REQUEST),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                notifications.notify(ReminderManager.NOTIFICATION_ID,
                    Notification.Builder(context, ReminderManager.CHANNEL)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle("今日收纳提醒 · 测试")
                        .addAction(Notification.Action.Builder(null, "稍后提醒（2 小时）", action).build())
                        .build())
            }
            val notification = requireNotNull(awaitActive(notifications))
            val snoozeAction = notification.actions.single { it.title.toString().contains("稍后提醒") }
            val before = System.currentTimeMillis()
            snoozeAction.actionIntent.send()
            val due = awaitDue(manager)
            val after = System.currentTimeMillis()

            assertTrue("操作后当前提醒应撤销", awaitInactive(notifications))
            assertTrue("稍后时间应在两小时之后", due in (before + TWO_HOURS)..(after + TWO_HOURS))
            assertEquals("新实例应从 DataStore 读回稍后时间", due, ReminderManager(context).snoozeDue())
            val alarmDump = readAlarmDump(instrumentation.uiAutomation.executeShellCommand("dumpsys alarm"))
            val scheduled = snoozeAlarm(alarmDump)
            assertNotNull("AlarmManager 应保存稍后广播：$alarmDump", scheduled)
            assertEquals(due, scheduled!!.groupValues[1].toLong())

            manager.deliverSnooze()
            assertNull("截止时间前不得重发", active(notifications))
            assertEquals(due, ReminderManager(context).snoozeDue())

            ReminderManager(context) { due + 1 }.deliverSnooze()
            val redelivered = requireNotNull(awaitActive(notifications))
            assertTrue(redelivered.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("稍后提醒验收样本"))
            assertNull("重发后清除持久化稍后时间", ReminderManager(context).snoozeDue())
            notifications.cancel(ReminderManager.NOTIFICATION_ID)
            assertTrue("测试通知应清除", awaitInactive(notifications))
            ReminderManager(context) { due + 1 }.deliverSnooze()
            assertNull("重复广播不得再次展示", active(notifications))

            manager.setEnabled(false)
            assertNull(ReminderManager(context).snoozeDue())
            assertFalse("关闭后不应再有待执行的稍后闹钟",
                snoozeAlarm(readAlarmDump(instrumentation.uiAutomation.executeShellCommand("dumpsys alarm"))) != null)
        } finally {
            manager.setEnabled(false)
            db.openHelper.writableDatabase.execSQL("DELETE FROM items WHERE id = ?", arrayOf(id))
            notifications.cancel(ReminderManager.NOTIFICATION_ID)
            manager.setEnabled(wasEnabled)
        }
    }

    private fun active(manager: NotificationManager): Notification? = manager.activeNotifications
        .firstOrNull { it.id == ReminderManager.NOTIFICATION_ID }?.notification

    /** 通知入队与 activeNotifications 刷新是异步的，等待系统状态后再断言。 */
    private suspend fun awaitActive(manager: NotificationManager): Notification? {
        repeat(100) {
            active(manager)?.let { return it }
            delay(50)
        }
        return null
    }

    private suspend fun awaitInactive(manager: NotificationManager): Boolean {
        repeat(100) {
            if (active(manager) == null) return true
            delay(50)
        }
        return false
    }

    private suspend fun awaitDue(manager: ReminderManager): Long {
        repeat(100) {
            manager.snoozeDue()?.let { return it }
            delay(50)
        }
        error("通知操作没有写入稍后时间")
    }

    private fun readAlarmDump(descriptor: ParcelFileDescriptor): String = descriptor.use {
        FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText() }
    }

    private fun snoozeAlarm(dump: String): MatchResult? = Regex(
        "RTC_WAKEUP #[^\\n]*origWhen (\\d+)[^\\n]*\\n\\s*tag=\\*walarm\\*:${Regex.escape(ReminderManager.ACTION_SNOOZE_DELIVER)}"
    ).find(dump)

    private companion object { const val TWO_HOURS = 2 * 60 * 60 * 1000L }
}
