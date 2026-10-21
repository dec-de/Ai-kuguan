package com.kuguan.app.data.backup.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class CloudDestination { NUTSTORE, SELF_HOSTED }

data class DestinationSettings(
    val enabled: Boolean = false,
    val intervalMinutes: Long = BackupFrequency.DEFAULT_MINUTES,
    val wifiOnly: Boolean = false,
    val serverUrl: String = "",
    val account: String = "",
    val secret: String = "",
    val lastSuccessAt: Long = 0,
    val lastSizeBytes: Long = 0,
    val lastError: String = "",
)

class CloudBackupSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cloud_backup", Context.MODE_PRIVATE)

    fun get(destination: CloudDestination): DestinationSettings {
        val key = destination.name.lowercase()
        return DestinationSettings(
            enabled = prefs.getBoolean("$key.enabled", false),
            intervalMinutes = prefs.getLong("$key.interval", BackupFrequency.DEFAULT_MINUTES),
            wifiOnly = prefs.getBoolean("$key.wifi", false),
            serverUrl = prefs.getString("$key.url", "") ?: "",
            account = prefs.getString("$key.account", "") ?: "",
            secret = decrypt(prefs.getString("$key.secret", null)),
            lastSuccessAt = prefs.getLong("$key.success", 0),
            lastSizeBytes = prefs.getLong("$key.size", 0),
            lastError = prefs.getString("$key.error", "") ?: "",
        )
    }

    fun save(destination: CloudDestination, value: DestinationSettings) {
        BackupFrequency.validate(value.intervalMinutes)
        val key = destination.name.lowercase()
        prefs.edit()
            .putBoolean("$key.enabled", value.enabled)
            .putLong("$key.interval", value.intervalMinutes)
            .putBoolean("$key.wifi", value.wifiOnly)
            .putString("$key.url", value.serverUrl)
            .putString("$key.account", value.account)
            .putString("$key.secret", encrypt(value.secret))
            .apply()
    }

    fun success(destination: CloudDestination, size: Long) {
        val key = destination.name.lowercase()
        prefs.edit().putLong("$key.success", System.currentTimeMillis())
            .putLong("$key.size", size).putString("$key.error", "").apply()
    }

    fun failure(destination: CloudDestination, message: String) {
        prefs.edit().putString("${destination.name.lowercase()}.error", message.take(300)).apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    private fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        return runCatching {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        }.getOrDefault("")
    }

    companion object { private const val KEY_ALIAS = "kuguan_cloud_credentials_v1" }
}

object BackupFrequency {
    const val DEFAULT_MINUTES = 720L
    fun validate(minutes: Long) {
        require(minutes in 15..43_200 && minutes % 15 == 0L) { "频率须为 15 分钟的倍数，范围 15 分钟至 30 天" }
    }
    fun monthlyBytes(sizeBytes: Long, minutes: Long): Double {
        validate(minutes)
        require(sizeBytes >= 0)
        return sizeBytes * 43_200.0 / minutes
    }
}
