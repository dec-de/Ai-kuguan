package com.kuguan.app.data.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 系统真实 RTC_WAKEUP + setAndAllowWhileIdle 近时刻送达验证；不改设备时钟。 */
@RunWith(AndroidJUnit4::class)
class M5AlarmManagerTest {
    @Test fun inexactAlarmReachesReceiver() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val action = "${context.packageName}.M5_ALARM_PROBE"
        val latch = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == action) latch.countDown()
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        val pending = PendingIntent.getBroadcast(context, 9812, Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarm = context.getSystemService(AlarmManager::class.java)
        try {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3_000, pending)
            assertTrue("30 秒内应收到系统闹钟广播", latch.await(30, TimeUnit.SECONDS))
        } finally {
            alarm.cancel(pending)
            context.unregisterReceiver(receiver)
        }
    }
}
