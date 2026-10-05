package com.zeroagent.app.openrouter

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class OpenRouterModel(val id: String, val name: String)

class OpenRouterClient {
    private val client = OkHttpClient()

    suspend fun fetchModels(apiKey: String = ""): List<OpenRouterModel> = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url("https://openrouter.ai/api/v1/models")
            .header("Accept", "application/json")
            .header("X-Title", "Zero Agent Android")
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer ${apiKey.trim()}")
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("モデル一覧取得失敗 HTTP ${response.code}: $text")
            val root = JSONObject(text)
            val data = root.optJSONArray("data") ?: error("OpenRouterのモデル一覧レスポンスを解析できませんでした")
            buildList {
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val id = item.optString("id").trim()
                    if (id.isNotBlank()) add(OpenRouterModel(id, item.optString("name", id)))
                }
            }.distinctBy { it.id }.sortedBy { it.name.lowercase() }
        }
    }

    suspend fun createPlan(apiKey: String, model: String, objective: String, reasoningEffort: String = "high"): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "OpenRouter API key is required" }
        val system = """
            You are the central planning brain of ZERO AGENT.
            ZERO AGENT tests whether autonomous AI agents can create legitimate economic value from zero initial capital using general-purpose service connectors, while retaining human control for sensitive or human-only actions.
            Do not reduce the task to social-media posting. Start from the user's actual objective, identify useful value to create, choose capabilities and connected services, execute supported actions, measure results, learn and iterate.
            Think in capabilities such as GENERATE_TEXT, GENERATE_IMAGE, PUBLISH_TEXT, PUBLISH_IMAGE, CREATE_PRODUCT, READ_CONTENT and READ_METRICS rather than hard-coding a service.
            AGENT automates supported actions. HUMAN gives control to the person. HYBRID automates supported actions but hands off login, CAPTCHA, identity checks, consent, important payments and unsupported UI operations.
            Never bypass safeguards, CAPTCHA, service restrictions or pretend automated activity is human activity. Respect law, copyright, platform rules and anti-spam requirements. Never expose passwords, wallet seeds/private keys or raw session cookies.
            Planning loop: OBSERVE -> PLAN -> POLICY CHECK -> ACT -> MEASURE -> LEARN -> OBSERVE.
            Respond in Japanese. Begin with 「企画の理解」, then give a concrete actionable plan and distinguish AI actions, connector actions and Human Takeover.
        """.trimIndent()
        val body = JSONObject()
            .put("model", model)
            .put("reasoning", JSONObject().put("effort", reasoningEffort))
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", "ZERO AGENTで達成したい目的: $objective")))
            .toString()
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Content-Type", "application/json")
            .header("X-Title", "Zero Agent Android")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("OpenRouter HTTP ${response.code}: $text")
            JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        }
    }
}
