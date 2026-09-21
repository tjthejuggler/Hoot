package com.example.hoot.data.remote

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** Endpoint + credentials snapshot handed to [LlmClient] calls. */
data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()
}

/**
 * OpenAI-compatible chat completions client — port of Inuit's LlmClient,
 * trimmed to Hoot's needs (no tool-calling loop: MCP tools are called
 * directly by [McpClient], not handed to the model).
 *
 * - URL normalization: bare host → `/v1/chat/completions`, versioned base
 *   (…/v1, …/v4 etc.) → `/chat/completions`, already-complete left as-is.
 * - `Authorization: Bearer <key>` only when a key is set (blank = local
 *   gateways without auth).
 * - GLM thinking-disable extension (`thinking.type=disabled`) per settings.
 * - Errors are surfaced as exceptions; wrap calls in `runCatching` for
 *   Result-style handling (the resolver never lets them escape).
 */
class LlmClient {

    /** One assistant turn. */
    data class LlmMessage(val role: String, val content: String?)

    suspend fun chat(
        cfg: LlmConfig,
        system: String,
        user: String,
        temperature: Float = 0.7f,
        maxTokens: Int = 4_000,
        disableThinking: Boolean = false
    ): String {
        val started = System.currentTimeMillis()
        val url = normalizeChatUrl(cfg.baseUrl)
        Log.i(TAG, "→ POST ${url.substringAfter("//")} model=${cfg.model} maxTokens=$maxTokens" +
            (if (disableThinking) " thinking=off" else ""))

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", system))
            put(JSONObject().put("role", "user").put("content", user))
        }
        val body = JSONObject().apply {
            put("model", cfg.model)
            put("messages", messages)
            put("temperature", temperature.toDouble())
            put("max_tokens", maxTokens)
            if (disableThinking) {
                // z.ai / GLM extension: skip internal reasoning entirely.
                put("thinking", JSONObject().put("type", "disabled"))
            }
            // Strict-JSON nudges understood by the major OpenAI-compatible providers.
            put("response_format", JSONObject().put("type", "json_object"))
        }
        val headers = mutableMapOf<String, String>()
        if (cfg.apiKey.isNotBlank()) headers["Authorization"] = "Bearer ${cfg.apiKey}"

        val resp = try {
            Http.post(url, headers, body.toString())
        } catch (e: Exception) {
            Log.e(TAG, "network failure after ${System.currentTimeMillis() - started}ms", e)
            throw e
        }
        val latency = System.currentTimeMillis() - started

        if (resp.code !in 200..299) {
            Log.e(TAG, "HTTP ${resp.code} in ${latency}ms: ${resp.body.take(400)}")
            throw LlmException("LLM error ${resp.code}: ${resp.body.take(300)}")
        }

        val root = try {
            JSONObject(resp.body)
        } catch (e: Exception) {
            Log.e(TAG, "non-JSON response in ${latency}ms: ${resp.body.take(300)}")
            throw LlmException("LLM returned non-JSON (HTTP ${resp.code})")
        }
        root.optJSONObject("error")?.let { err ->
            val m = err.optString("message", err.toString()).take(300)
            Log.e(TAG, "API error object in ${latency}ms: $m")
            throw LlmException("LLM error: $m")
        }
        val content = root.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content")
            ?: throw LlmException("No message in LLM response: ${resp.body.take(200)}")
        if (content.isBlank()) {
            Log.e(TAG, "empty content in ${latency}ms finish=" +
                root.optJSONArray("choices")?.optJSONObject(0)?.optString("finish_reason"))
            throw LlmException("Model returned no content" +
                (if (disableThinking) "" else " — try 'Disable deep thinking' in Settings"))
        }
        Log.i(TAG, "← ${latency}ms content=${content.length}ch")
        return content
    }

    /**
     * Requests strict JSON output and extracts the first balanced JSON
     * object/array from the reply. One stricter retry follows a parse
     * failure (ARCHITECTURE.md §10), then the error propagates.
     */
    suspend fun completeJson(
        cfg: LlmConfig,
        system: String,
        user: String,
        temperature: Float = 0.2f,
        maxTokens: Int = 4_000,
        disableThinking: Boolean = false
    ): String {
        val content = chat(cfg, system, user, temperature, maxTokens, disableThinking)
        val extracted = extractJson(content)
        if (isJson(extracted)) return extracted

        val retryUser = "$user\n\nIMPORTANT: reply with ONLY one valid JSON " +
            "object/array — no prose, no markdown fences. Previous reply was not parseable JSON."
        val retry = chat(cfg, system, retryUser, temperature, maxTokens, disableThinking)
        val retryExtracted = extractJson(retry)
        if (isJson(retryExtracted)) return retryExtracted
        throw LlmException("LLM JSON unparseable after retry: ${retry.take(200)}")
    }

    /**
     * Vision-capable chat completion: [userContent] may be a plain String OR a
     * JSONArray of typed content parts (`{"type":"text"|"image_url"}`), the
     * OpenAI multimodal wire format Tail's vision pipeline uses. Everything
     * else (URL normalization, auth, GLM thinking override, JSON nudges) is
     * identical to [chat]. Errors are thrown; callers degrade to text-only.
     */
    suspend fun chatVision(
        cfg: LlmConfig,
        system: String,
        userContent: Any,
        temperature: Float = 0.2f,
        maxTokens: Int = 4_000,
        disableThinking: Boolean = false
    ): String {
        val url = normalizeChatUrl(cfg.baseUrl)
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", system))
            put(JSONObject().put("role", "user").put("content", userContent))
        }
        val body = JSONObject().apply {
            put("model", cfg.model)
            put("messages", messages)
            put("temperature", temperature.toDouble())
            put("max_tokens", maxTokens)
            if (disableThinking) {
                put("thinking", JSONObject().put("type", "disabled"))
            }
            put("response_format", JSONObject().put("type", "json_object"))
        }
        val headers = mutableMapOf<String, String>()
        if (cfg.apiKey.isNotBlank()) headers["Authorization"] = "Bearer ${cfg.apiKey}"
        val resp = Http.post(url, headers, body.toString())
        if (resp.code !in 200..299) {
            throw LlmException("LLM error ${resp.code}: ${resp.body.take(300)}")
        }
        val root = JSONObject(resp.body)
        root.optJSONObject("error")?.let { err ->
            throw LlmException("LLM error: ${err.optString("message", err.toString()).take(300)}")
        }
        val content = root.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content")
            ?: throw LlmException("No message in LLM response: ${resp.body.take(200)}")
        if (content.isBlank()) throw LlmException("Model returned no content")
        return content
    }

    /**
     * GET /models — used by the Settings "Test" button (Inuit-identical
     * reachability check: base URL + optional key, `/v1/models`).
     */
    suspend fun listModels(cfg: LlmConfig): List<String> {
        val headers = mutableMapOf<String, String>()
        if (cfg.apiKey.isNotBlank()) headers["Authorization"] = "Bearer ${cfg.apiKey}"
        val resp = Http.get(normalizeModelsUrl(cfg.baseUrl), headers)
        if (resp.code !in 200..299) {
            Log.e(TAG, "listModels HTTP ${resp.code}: ${resp.body.take(300)}")
            throw LlmException("HTTP ${resp.code}: ${resp.body.take(200)}")
        }
        val arr = JSONObject(resp.body).optJSONArray("data") ?: JSONArray()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val id = arr.optJSONObject(i)?.optString("id") ?: continue
            if (id.isNotBlank()) out.add(id)
        }
        Log.i(TAG, "listModels OK — ${out.size} models")
        return out
    }

    class LlmException(message: String) : Exception(message)

    companion object {
        private const val TAG = "HootLLM"

        /** Inuit-style base-URL normalization for chat completions. */
        fun normalizeChatUrl(base: String): String {
            val t = base.trim().trimEnd('/')
            return when {
                t.isEmpty() -> t
                t.endsWith("/chat/completions") -> t
                Regex("/v\\d+[a-z]*$").containsMatchIn(t) -> "$t/chat/completions"
                else -> "$t/v1/chat/completions"
            }
        }

        /** Inuit-style base-URL normalization for the models endpoint. */
        fun normalizeModelsUrl(base: String): String {
            val t = base.trim().trimEnd('/')
            return when {
                t.isEmpty() -> t
                t.endsWith("/models") -> t
                Regex("/v\\d+[a-z]*$").containsMatchIn(t) -> "$t/models"
                else -> "$t/v1/models"
            }
        }

        /** Strips markdown fences and returns the first balanced JSON object/array. */
        fun extractJson(text: String): String {
            var t = text.trim()
            if (t.startsWith("```")) {
                t = t.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
                val end = t.lastIndexOf("```")
                if (end >= 0) t = t.substring(0, end)
                t = t.trim()
            }
            // Prefer the first '{' or '[' — whichever occurs first.
            val startObj = t.indexOf('{')
            val startArr = t.indexOf('[')
            val start = when {
                startObj < 0 && startArr < 0 -> return t
                startObj < 0 -> startArr
                startArr < 0 -> startObj
                else -> minOf(startObj, startArr)
            }
            val (open, close) = if (t[start] == '[') '[' to ']' else '{' to '}'
            var depth = 0
            var inString = false
            var escaped = false
            for (i in start until t.length) {
                val c = t[i]
                if (escaped) {
                    escaped = false
                    continue
                }
                when {
                    c == '\\' && inString -> escaped = true
                    c == '"' -> inString = !inString
                    !inString && c == open -> depth++
                    !inString && c == close -> {
                        depth--
                        if (depth == 0) return t.substring(start, i + 1)
                    }
                }
            }
            return t.substring(start)
        }

        /** Cheap well-formedness probe (org.json is lenient, so wrap-parse). */
        fun isJson(text: String): Boolean = try {
            val t = text.trim()
            if (t.startsWith("[")) { JSONArray(t); true } else { JSONObject(t); true }
        } catch (_: Exception) {
            false
        }
    }
}
