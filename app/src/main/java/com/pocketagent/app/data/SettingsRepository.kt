package com.pocketagent.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** LLM 配置,加密存储在本地,绝不外传。 */
data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
) {
    val isReady: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "pocketagent_settings",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun getLlmConfig(): LlmConfig = LlmConfig(
        baseUrl = prefs.getString(KEY_BASE_URL, "")?.trim() ?: "",
        apiKey = prefs.getString(KEY_API_KEY, "")?.trim() ?: "",
        model = prefs.getString(KEY_MODEL, "")?.trim() ?: "",
    )

    fun saveLlmConfig(config: LlmConfig) {
        prefs.edit()
            .putString(KEY_BASE_URL, config.baseUrl.trim())
            .putString(KEY_API_KEY, config.apiKey.trim())
            .putString(KEY_MODEL, config.model.trim())
            .apply()
    }

    companion object {
        private const val KEY_BASE_URL = "llm_base_url"
        private const val KEY_API_KEY = "llm_api_key"
        private const val KEY_MODEL = "llm_model"
    }
}
