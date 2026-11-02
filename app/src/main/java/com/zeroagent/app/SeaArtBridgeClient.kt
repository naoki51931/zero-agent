package com.zeroagent.app

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object SeaArtBridgeClient {
    suspend fun generateAndSave(context: Context, draftId: String, prompt: String): Boolean = withContext(Dispatchers.IO) {
        val settings = LocalSettings(context)
        val base = settings.get("seaartBridgeUrl").trimEnd('/')
        val token = settings.get("seaartBridgeToken")
        require(base.startsWith("https://") && token.length >= 24) { "HTTPS Bridge URLとトークンを設定してください" }
        val connection = (URL(base + "/v1/generate").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 260000; doOutput = true
            setRequestProperty("Authorization", "Bearer " + token)
            setRequestProperty("Content-Type", "application/json")
        }
        val result = try {
            connection.outputStream.use { it.write(JSONObject().put("prompt", prompt).toString().toByteArray()) }
            require(connection.responseCode == 200) { "SeaArt Bridge HTTP " + connection.responseCode }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
        val imageUrl = result.getString("image_url")
        require(imageUrl.startsWith("https://")) { "Image URL must use HTTPS" }
        val image = URL(imageUrl).openConnection().apply { connectTimeout = 15000; readTimeout = 60000 }
        val temp = File(context.cacheDir, "seaart_" + draftId + ".png")
        try {
            image.getInputStream().use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= 20_000_000) { "Image exceeds 20 MB" }
                        output.write(buffer, 0, n)
                    }
                }
            }
            CharacterDraftStore(context).attachImage(draftId, Uri.fromFile(temp))
        } finally { temp.delete() }
    }
}
