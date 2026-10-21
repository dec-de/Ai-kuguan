package com.kuguan.app.data.backup.cloud

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.kuguan.app.data.backup.BackupService
import com.kuguan.app.data.db.KuguanDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

class CloudBackupCoordinator(private val context: Context) {
    private val settings = CloudBackupSettings(context)
    private val service = BackupService(KuguanDatabase.get(context), context.applicationContext)

    fun store(destination: CloudDestination, config: DestinationSettings = settings.get(destination)): CloudBackupStore {
        return when (destination) {
            CloudDestination.NUTSTORE -> {
                require(config.account.isNotBlank() && config.secret.isNotBlank()) { "请先填写坚果云账号和应用密码" }
                NutstoreWebDavStore(config.account, config.secret)
            }
            CloudDestination.SELF_HOSTED -> {
                require(config.secret.isNotBlank()) { "请先填写自建服务器访问令牌" }
                SelfHostedBackupStore(config.serverUrl, config.secret)
            }
        }
    }

    suspend fun backupNow(destination: CloudDestination): CloudBackupEntry = lock.withLock {
        withContext(Dispatchers.IO) {
            val dir = File(context.noBackupFilesDir, "cloud-pending/${destination.name.lowercase()}").apply { mkdirs() }
            val pending = dir.listFiles { file -> file.extension == "json" }?.sortedBy { it.lastModified() }?.firstOrNull()
                ?: createPending(dir)
            val id = UUID.fromString(pending.nameWithoutExtension)
            try {
                val result = store(destination).upload(id, pending, sha256(pending))
                settings.success(destination, result.sizeBytes)
                pending.delete()
                runCatching { store(destination).prune() }
                result
            } catch (e: Exception) {
                settings.failure(destination, e.message ?: "备份失败")
                throw e
            }
        }
    }

    private suspend fun createPending(dir: File): File {
        val id = UUID.randomUUID()
        val temp = File(dir, "$id.building")
        val target = File(dir, "$id.json")
        try {
            temp.outputStream().use { service.exportTo(it, id.toString()) }
            require(temp.length() <= 500_000_000) { "备份文件超过 500 MB" }
            require(temp.renameTo(target)) { "无法保存待上传备份" }
            return target
        } finally { temp.delete() }
    }

    suspend fun list(destination: CloudDestination): List<CloudBackupEntry> = store(destination).list()

    suspend fun download(destination: CloudDestination, entry: CloudBackupEntry): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "cloud-restore").apply { mkdirs() }
        val target = File(dir, "${destination.name}-${entry.id}.json")
        if (target.exists()) target.delete()
        val actual = store(destination).download(entry.id, target)
        require(actual.id == entry.id && actual.sha256 == entry.sha256 && actual.sizeBytes == entry.sizeBytes) { "云端备份校验失败" }
        target
    }

    fun discardPending(destination: CloudDestination) {
        File(context.noBackupFilesDir, "cloud-pending/${destination.name.lowercase()}")
            .listFiles()?.forEach { if (it.extension == "json" || it.extension == "building") it.delete() }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object { private val lock = Mutex() }
}

object CloudBackupScheduler {
    fun sync(context: Context, destination: CloudDestination) {
        val settings = CloudBackupSettings(context).get(destination)
        val manager = WorkManager.getInstance(context)
        val name = "cloud-backup-${destination.name.lowercase()}"
        if (!settings.enabled) { manager.cancelUniqueWork(name); return }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<CloudBackupWorker>(settings.intervalMinutes, TimeUnit.MINUTES)
            .setInputData(workDataOf("destination" to destination.name, "periodic" to true))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        manager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun syncAll(context: Context) { CloudDestination.entries.forEach { sync(context, it) } }

    fun backupNow(context: Context, destination: CloudDestination) {
        val config = CloudBackupSettings(context).get(destination)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (config.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<CloudBackupWorker>()
            .setInputData(workDataOf("destination" to destination.name))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("cloud-backup-now-${destination.name.lowercase()}", ExistingWorkPolicy.KEEP, request)
    }
}

class CloudBackupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val destination = runCatching { CloudDestination.valueOf(inputData.getString("destination") ?: "") }.getOrNull()
            ?: return Result.failure()
        if (!CloudBackupSettings(applicationContext).get(destination).enabled && inputData.getBoolean("periodic", false)) return Result.success()
        return try {
            val notifications = applicationContext.getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel("cloud_backup", "云备份", NotificationManager.IMPORTANCE_LOW))
            val notification = NotificationCompat.Builder(applicationContext, "cloud_backup")
                .setSmallIcon(com.kuguan.app.R.mipmap.ic_launcher)
                .setContentTitle("收纳助手正在备份")
                .setContentText(if (destination == CloudDestination.NUTSTORE) "坚果云" else "自建服务器")
                .setOngoing(true).build()
            setForeground(ForegroundInfo(1003 + destination.ordinal, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            CloudBackupCoordinator(applicationContext).backupNow(destination)
            Result.success()
        } catch (e: Exception) {
            val fatal = when ((e as? CloudBackupException)?.category) {
                CloudBackupException.Category.AUTH,
                CloudBackupException.Category.CAPACITY,
                CloudBackupException.Category.CONFLICT,
                CloudBackupException.Category.CORRUPT,
                CloudBackupException.Category.UNSUPPORTED -> true
                else -> false
            }
            if (fatal) {
                CloudBackupCoordinator(applicationContext).discardPending(destination)
                Result.failure()
            } else Result.retry()
        }
    }
}
