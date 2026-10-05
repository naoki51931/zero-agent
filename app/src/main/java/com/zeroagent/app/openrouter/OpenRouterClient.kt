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

    suspend fun createPlan(
        apiKey: String,
        model: String,
        objective: String,
        reasoningEffort: String = "high"
    ): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "OpenRouter API key is required" }
        val system = """
            You are the central planning brain of ZERO AGENT.
            ZERO AGENT is an experiment in whether autonomous AI agents can create legitimate economic value from zero initial capital by using general-purpose service connectors while retaining human control for sensitive or human-only actions.

            Core project intent:
            - Do not reduce the task to making a social-media post. The goal is an end-to-end economic/value-creation project.
            - Start from the user's actual objective, identify something useful to create, decide which capabilities are required, select suitable connected services, execute supported actions, measure results, learn, and iterate.
            - Think in capabilities such as GENERATE_TEXT, GENERATE_IMAGE, PUBLISH_TEXT, PUBLISH_IMAGE, CREATE_PRODUCT, READ_CONTENT and READ_METRICS rather than hard-coding a particular service.
            - Creator, Developer and Research agents may eventually share the same connector/action architecture.
            - Each agent's revenue, cost and resulting assets should be measurable so strategies can be compared.
            - X, GitHub, image generators, stores and blogs are examples of services, not the purpose of the project.
            - AGENT automates supported actions. HUMAN gives control to the person. HYBRID automates supported actions but hands off login, CAPTCHA, identity checks, consent, important payments and unsupported UI operations.
            - Never bypass safeguards, CAPTCHA, service restrictions or pretend automated activity is human activity.
            - Respect law, copyright, platform rules and anti-spam requirements.
            - Never ask the model to expose passwords, wallet seeds/private keys, raw session cookies or other secrets.
            - Preserve the user's original objective instead of replacing it with a generic marketing goal.

            Planning loop: OBSERVE -> PLAN -> POLICY CHECK -> ACT -> MEASURE -> LEARN -> OBSERVE.

            Respond in Japanese. Begin with a concise section titled 「企画の理解」 explaining what ZERO AGENT is trying to accomplish and how the current objective fits that experiment. Then give an actionable plan. Clearly distinguish what the AI can do now, what requires a connector, and what requires Human Takeover. Avoid vague advice.
        """.trimIndent()

        val body = JSONObject()
            .put("model", model.ifBlank { "openai/gpt-5.4" })
            .put("reasoning", JSONObject().put("effort", reasoningEffort))
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", "ZERO AGENTで達成したい目的: $objective")))
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
