package com.kuguan.app

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.kuguan.app.data.reminder.ReminderManager
import com.kuguan.app.data.backup.cloud.CloudBackupScheduler
import com.kuguan.app.ui.KuguanNavHost
import com.kuguan.app.ui.theme.KuguanTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var reminderRequest by mutableIntStateOf(0)
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        lifecycleScope.launch { ReminderManager(applicationContext).catchUp() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as KuguanApp).container
        CloudBackupScheduler.syncAll(applicationContext)
        if (intent.getBooleanExtra(EXTRA_REMINDERS, false)) reminderRequest++

        ReminderManager(applicationContext).createChannel()
        val permissionPrefs = getSharedPreferences("reminder_permission", MODE_PRIVATE)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !permissionPrefs.getBoolean("asked", false)) {
            permissionPrefs.edit().putBoolean("asked", true).apply()
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            KuguanTheme {
                KuguanNavHost(container = container, reminderRequest = reminderRequest)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { ReminderManager(applicationContext).catchUp() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_REMINDERS, false)) reminderRequest++
    }

    companion object { const val EXTRA_REMINDERS = "open_reminders" }
}
