package com.kuguan.app.data.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One immutable, versioned snapshot for all three voice service routes. */
data class VoiceServiceConfig(
    val asrUrl: String = DEFAULT_ASR_URL,
    val asrModel: String = DEFAULT_ASR_MODEL,
    val asrKey: String = "",
    val primaryUrl: String = "",
    val primaryModel: String = "deepseek-flash",
    val primaryKey: String = "",
    val fallbackUrl: String = "",
    val fallbackModel: String = "",
    val fallbackKey: String = "",
) {
    val fallbackConfigured: Boolean
        get() = fallbackUrl.isNotBlank() && fallbackModel.isNotBlank() && fallbackKey.isNotBlank()

    fun validate() {
        val missing = buildList {
            if (fallbackUrl.isBlank()) add("地址")
            if (fallbackModel.isBlank()) add("模型")
            if (fallbackKey.isBlank()) add("Key")
        }
        require(missing.size == 3 || missing.isEmpty()) {
            "备用 LLM 还缺少${missing.joinToString("、")}；请填全或全部留空"
        }
    }

    companion object {
        const val DEFAULT_ASR_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
        const val DEFAULT_ASR_MODEL = "qwen-audio-3.1-asr-flash-message"
    }
}

data class VoiceSettingsRead(
    val config: VoiceServiceConfig,
    val needsReentry: Boolean = false,
)

/**
 * The whole JSON snapshot, including URLs/models, is AES-GCM encrypted. A broken file or lost
 * AndroidKeyStore entry is reported to the UI; it never silently becomes an empty valid config.
 */
class VoiceServiceSettings(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))

    @Synchronized
    fun read(): VoiceSettingsRead {
        if (!file.baseFile.exists()) return VoiceSettingsRead(VoiceServiceConfig())
        return try {
            val sealed = file.openRead().use { it.readBytes() }
            require(sealed.size > IV_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed.copyOfRange(0, IV_BYTES)))
            val json = JSONObject(cipher.doFinal(sealed.copyOfRange(IV_BYTES, sealed.size)).toString(Charsets.UTF_8))
            require(json.getInt("version") == VERSION)
            VoiceSettingsRead(VoiceServiceConfig(
                asrUrl = json.getString("asrUrl"), asrModel = json.getString("asrModel"),
                asrKey = json.getString("asrKey"), primaryUrl = json.getString("primaryUrl"),
                primaryModel = json.getString("primaryModel"), primaryKey = json.getString("primaryKey"),
                fallbackUrl = json.getString("fallbackUrl"), fallbackModel = json.getString("fallbackModel"),
                fallbackKey = json.getString("fallbackKey"),
            ))
        } catch (_: Exception) {
            VoiceSettingsRead(VoiceServiceConfig(), needsReentry = true)
        }
    }

    @Synchronized
    fun save(config: VoiceServiceConfig) {
        config.validate()
        val json = JSONObject()
            .put("version", VERSION)
            .put("asrUrl", config.asrUrl).put("asrModel", config.asrModel).put("asrKey", config.asrKey)
            .put("primaryUrl", config.primaryUrl).put("primaryModel", config.primaryModel).put("primaryKey", config.primaryKey)
            .put("fallbackUrl", config.fallbackUrl).put("fallbackModel", config.fallbackModel)
            .put("fallbackKey", config.fallbackKey)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try {
            stream.write(sealed)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    @Synchronized
    fun clear() { file.delete() }

    fun sensitiveValues(): Set<String> = read().config.let { config ->
        setOf(config.asrKey, config.primaryKey, config.fallbackKey).filter { it.isNotBlank() }.toSet()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    companion object {
        private const val VERSION = 1
        private const val IV_BYTES = 12
        private const val KEY_ALIAS = "kuguan_voice_service_v1"
        private const val FILE_NAME = "voice_service_settings.enc"
    }
}
