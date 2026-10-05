package com.zeroagent.app.openrouter

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class OpenRouterClient {
    private val client = OkHttpClient()

    suspend fun createPlan(apiKey: String, model: String, objective: String): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "OpenRouter API key is required" }
        val system = """
            You are the planning component of Zero Agent. Create one legitimate, non-spam content plan.
            Return concise JSON containing title, concept, target, image_prompt, post_text, reply_text and hashtags.
            Do not request passwords, wallet recovery words, private keys or session cookies.
        """.trimIndent()
        val body = JSONObject()
            .put("model", model.ifBlank { "openrouter/auto" })
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", "Objective: $objective")))
            .toString()

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("X-Title", "Zero Agent Android")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("OpenRouter HTTP ${response.code}: $text")
            JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        }
    }
}
