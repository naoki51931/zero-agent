package com.zeroagent.app

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Password-protected external backup. The Android document picker controls file location. */
object SettingsBackup {
    private val names = listOf("apiKey", "objective", "model", "provider", "reasoning", "mode")
    private const val iterations = 210_000
    private const val maxBytes = 1024 * 1024

    fun export(context: Context, uri: Uri, password: String, settings: LocalSettings) {
        require(password.length >= 12) { "バックアップ用パスワードは12文字以上にしてください" }
        val values = JSONObject()
        names.forEach { values.put(it, settings.get(it)) }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val key = derive(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(values.toString().toByteArray(Charsets.UTF_8))
        val envelope = JSONObject()
            .put("format", "zero-agent-settings-v1")
            .put("kdf", "PBKDF2WithHmacSHA256")
            .put("iterations", iterations)
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
        context.contentResolver.openOutputStream(uri, "wt")?.use {
            it.write(envelope.toString().toByteArray(Charsets.UTF_8))
        } ?: error("保存先を開けません")
    }

    fun restore(context: Context, uri: Uri, password: String, settings: LocalSettings) {
        require(password.isNotEmpty()) { "バックアップ用パスワードを入力してください" }
        val bytes = context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().also { data -> require(data.size <= maxBytes) { "ファイルが大きすぎます" } }
        } ?: error("バックアップを開けません")
        val envelope = JSONObject(String(bytes, Charsets.UTF_8))
        require(envelope.getString("format") == "zero-agent-settings-v1") { "対応していないバックアップ形式です" }
        require(envelope.getInt("iterations") == iterations) { "対応していない暗号化設定です" }
        val salt = Base64.decode(envelope.getString("salt"), Base64.DEFAULT)
        val iv = Base64.decode(envelope.getString("iv"), Base64.DEFAULT)
        require(salt.size == 16 && iv.size == 12) { "バックアップの形式が不正です" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, derive(password, salt), GCMParameterSpec(128, iv))
        val decoded = cipher.doFinal(Base64.decode(envelope.getString("data"), Base64.DEFAULT))
        val values = JSONObject(String(decoded, Charsets.UTF_8))
        // Validate all fields before modifying any local setting.
        val restored = names.associateWith { values.getString(it) }
        restored.forEach { (name, value) -> settings.put(name, value) }
    }

    private fun derive(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally { spec.clearPassword() }
    }
}
