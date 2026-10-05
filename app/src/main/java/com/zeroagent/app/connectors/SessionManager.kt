package com.zeroagent.app.connectors

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores service session metadata/tokens after an explicit successful login.
 * Passwords should not be stored here. Prefer OAuth/access+refresh tokens supplied
 * by the service. When a token expires, the connector should refresh it using the
 * service's supported flow or request a new human login.
 */
class SessionManager(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "zero_agent_service_sessions",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveSession(
        serviceId: String,
        accessToken: String,
        refreshToken: String? = null,
        expiresAtEpochMs: Long? = null
    ) {
        prefs.edit()
            .putString("${serviceId}_access", accessToken)
            .putString("${serviceId}_refresh", refreshToken)
            .putLong("${serviceId}_expires", expiresAtEpochMs ?: -1L)
            .apply()
    }

    fun getAccessToken(serviceId: String): String? =
        prefs.getString("${serviceId}_access", null)

    fun getRefreshToken(serviceId: String): String? =
        prefs.getString("${serviceId}_refresh", null)

    fun hasSession(serviceId: String): Boolean =
        !getAccessToken(serviceId).isNullOrBlank()

    fun isExpired(serviceId: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val expires = prefs.getLong("${serviceId}_expires", -1L)
        return expires > 0L && nowEpochMs >= expires
    }

    fun clearSession(serviceId: String) {
        prefs.edit()
            .remove("${serviceId}_access")
            .remove("${serviceId}_refresh")
            .remove("${serviceId}_expires")
            .apply()
    }
}
