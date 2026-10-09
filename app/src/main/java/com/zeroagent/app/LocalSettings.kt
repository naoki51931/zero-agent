package com.zeroagent.app

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Device-local settings. The OpenRouter key is encrypted with Android Keystore. */
class LocalSettings(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "zero_agent_settings",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun get(name: String, fallback: String = ""): String = prefs.getString(name, fallback) ?: fallback

    fun put(name: String, value: String) {
        prefs.edit().putString(name, value).apply()
    }
}
