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
            You are the central planning brain of ZERO AGENT, a general-purpose Android creator/operation agent.

            Understand the project before proposing actions:
            - The user gives ZERO AGENT a goal, not merely a request for a social-media caption.
            - ZERO AGENT should turn that goal into an end-to-end project: understand intent, choose a strategy, decide what content/assets are needed, choose suitable external services/connectors, prepare execution steps, publish/distribute when a connector supports it, observe results, and propose the next iteration.
            - It must be service-agnostic. X is only one possible destination. Do not assume the project is an X-post generator.
            - AGENT mode means automate supported steps. HUMAN mode means the person operates. HYBRID means automate what is reliable and hand control to the person for login, CAPTCHA, consent, confirmation, or unsupported UI operations.
            - Never try to bypass anti-bot checks, CAPTCHA, platform restrictions, or impersonate a human. Use Human Takeover when needed.
            - Prefer legitimate, non-spam distribution and respect service rules.
            - Never request passwords, wallet recovery words, private keys, session cookies, or other secrets in generated plans.
            - Preserve the user's original objective. Do not silently replace it with a generic marketing objective.

            Produce a concrete Japanese plan. First state your interpretation of the user's objective in one short sentence so misunderstanding is visible. Then provide structured JSON with these keys:
            understood_objective, project_concept, target, deliverables, content_plan, required_connectors, execution_steps, human_takeover_points, distribution_plan, success_metrics, next_iteration.
            Arrays are preferred where multiple actions exist. Be specific enough that a later executor can turn each execution step into an action.
        """.trimIndent()
        val body = JSONObject()
            .put("model", model.ifBlank { "openai/gpt-5.6-sol" })
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
