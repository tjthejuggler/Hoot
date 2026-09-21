package com.example.hoot.data.remote

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

data class McpServerConfig(
    val name: String,
    val url: String,
    val headers: Map<String, String> = emptyMap()
)

data class McpParseResult(
    val servers: List<McpServerConfig>,
    /** Names of stdio/unsupported servers that were skipped. */
    val skipped: List<String>,
    val error: String? = null
)

/**
 * Parses an MCP servers JSON in the common desktop-client shape:
 * `{"mcpServers": { name: { "type": "streamable-http", "url": …, "headers": {…} } }}`.
 * Only remote HTTP servers are usable on Android; stdio entries are skipped
 * (same rule as Inuit).
 */
object McpConfig {
    fun parse(json: String): McpParseResult {
        try {
            val root = JSONObject(json.trim())
            val serversObj = root.optJSONObject("mcpServers") ?: root.optJSONObject("servers") ?: root
            val servers = ArrayList<McpServerConfig>()
            val skipped = ArrayList<String>()
            for (name in serversObj.keys()) {
                val o = serversObj.optJSONObject(name) ?: continue
                val url = o.optString("url").ifBlank { null }
                if (url == null) {
                    skipped.add(name)
                    continue
                }
                // streamable-http only; type may be omitted (treated as http).
                val type = o.optString("type", "streamable-http").lowercase()
                if (type !in setOf("streamable-http", "http", "streamable_http")) {
                    skipped.add(name)
                    continue
                }
                val headers = HashMap<String, String>()
                val h = o.optJSONObject("headers")
                if (h != null) for (k in h.keys()) headers[k] = h.optString(k)
                servers.add(McpServerConfig(name, url, headers))
            }
            return McpParseResult(servers, skipped)
        } catch (e: Exception) {
            return McpParseResult(emptyList(), emptyList(), e.message ?: "invalid JSON")
        }
    }
}

/** One web-search hit (title/url/snippet) surfaced to the resolver. */
data class WebResult(val title: String, val url: String, val snippet: String)

/**
 * Minimal MCP client for the Streamable HTTP transport (JSON-RPC 2.0 over
 * POST; responses may be plain JSON or SSE-framed). Port of Inuit's
 * McpClient: initialize handshake (session-id header captured), the
 * `notifications/initialized` ping, tools/list and tools/call.
 */
class McpClient(private val cfg: McpServerConfig) {

    private var sessionId: String? = null
    private var nextId = 1

    suspend fun initialize() {
        val params = JSONObject().apply {
            put("protocolVersion", "2025-03-26")
            put("capabilities", JSONObject())
            put("clientInfo", JSONObject().apply {
                put("name", "hoot")
                put("version", "1.0")
            })
        }
        val resp = rpc("initialize", params)
        val header = resp.headers.entries.firstOrNull {
            it.key.equals("mcp-session-id", ignoreCase = true) && it.value.isNotEmpty()
        }
        sessionId = header?.value?.first()
        Log.i(TAG, "initialized '${cfg.name}' (session=${sessionId?.take(8) ?: "none"})")
        // Fire-and-forget initialized notification (202, empty body is fine).
        try {
            Http.post(
                cfg.url,
                baseHeaders(),
                JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("method", "notifications/initialized")
                }.toString()
            )
        } catch (_: Exception) {
            // Some servers don't require it; ignore.
        }
    }

    suspend fun listTools(): List<String> {
        val resp = rpc("tools/list", JSONObject())
        val tools = ArrayList<String>()
        val result = resp.json?.optJSONObject("result") ?: return tools
        val arr = result.optJSONArray("tools") ?: return tools
        for (i in 0 until arr.length()) {
            val name = arr.optJSONObject(i)?.optString("name") ?: continue
            if (name.isNotBlank()) tools.add(name)
        }
        return tools
    }

    /** Calls a tool; returns concatenated text content (truncated for context safety). */
    suspend fun callTool(name: String, argsJson: String): String {
        val params = JSONObject().apply {
            put("name", name)
            put("arguments", JSONObject(argsJson.ifBlank { "{}" }))
        }
        val resp = rpc("tools/call", params)
        val result = resp.json?.optJSONObject("result")
            ?: return (resp.json?.optJSONObject("error")?.optString("message") ?: "tool error").also {
                Log.w(TAG, "'${cfg.name}' call '$name' error: $it")
            }
        if (result.optBoolean("isError", false)) {
            return "tool error: ${contentToText(result.optJSONArray("content")).ifBlank { "unknown" }}"
        }
        return contentToText(result.optJSONArray("content")).ifBlank { "(empty result)" }
    }

    private fun contentToText(content: JSONArray?): String {
        if (content == null) return ""
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val c = content.optJSONObject(i) ?: continue
            when (c.optString("type")) {
                "text" -> sb.append(c.optString("text")).append('\n')
                "image" -> sb.append("[image omitted]").append('\n')
                "resource" -> {
                    val r = c.optJSONObject("resource")
                    if (r != null) sb.append(r.optString("text", "[resource]")).append('\n')
                }
            }
        }
        val text = sb.toString().trim()
        return if (text.length > MAX_TOOL_TEXT) text.take(MAX_TOOL_TEXT) + "\n…[truncated]" else text
    }

    // ── JSON-RPC plumbing ────────────────────────────────────────────────

    private class RpcResult(val json: JSONObject?, val headers: Map<String, List<String>>)

    private fun baseHeaders(): MutableMap<String, String> {
        val h = HashMap<String, String>()
        h["Content-Type"] = "application/json"
        h["Accept"] = "application/json, text/event-stream"
        for ((k, v) in cfg.headers) h[k] = v
        sessionId?.let { h["Mcp-Session-Id"] = it }
        return h
    }

    private suspend fun rpc(method: String, params: JSONObject): RpcResult {
        val id = nextId++
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val resp = Http.post(
            cfg.url, baseHeaders(), body.toString(),
            connectTimeoutMs = 15_000, readTimeoutMs = MCP_READ_TIMEOUT_MS,
            totalTimeoutMs = MCP_TOTAL_TIMEOUT_MS
        )
        if (resp.code !in 200..299) {
            Log.e(TAG, "'${cfg.name}' $method HTTP ${resp.code}: ${resp.body.take(200)}")
            throw McpException("MCP ${cfg.name} $method HTTP ${resp.code}: ${resp.body.take(200)}")
        }
        val json = parseMaybeSse(resp.body)
        if (json?.opt("error") != null) {
            val e = json.optJSONObject("error")
            throw McpException(
                "MCP ${cfg.name} $method: " +
                    (if (e != null) e.optString("message") else json.toString())
            )
        }
        return RpcResult(json, resp.headers)
    }

    /** Handles both plain JSON and SSE-framed responses. */
    private fun parseMaybeSse(body: String): JSONObject? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("{")) {
            var last: JSONObject? = null
            for (line in trimmed.lineSequence()) {
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isBlank() || data == "[DONE]") continue
                try {
                    last = JSONObject(data)
                } catch (_: Exception) {
                    // partial frame — ignore
                }
            }
            return last
        }
        return try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            null
        }
    }

    class McpException(message: String) : Exception(message)

    companion object {
        private const val TAG = "HootMCP"
        private const val MAX_TOOL_TEXT = 12_000
        const val MCP_READ_TIMEOUT_MS = 60_000
        const val MCP_TOTAL_TIMEOUT_MS = 90_000L
    }
}

/**
 * Web-tools facade over the configured MCP servers — Hoot's counterpart of
 * Inuit's McpSession, specialized for `web-search-prime` + `web-reader`.
 *
 * - `searchWeb(query)` → parsed [WebResult]s (JSON in tool text is
 *   preferred; markdown-ish fallback extracts urls).
 * - `readUrl(url)` → page text.
 * - Honors the per-run tool budget ([remaining]); every call that gets
 *   through decrements it; failures are logged, never thrown past
 *   [searchWeb]/[readUrl] (graceful degradation per ARCHITECTURE.md §10).
 */
class McpWebTools(private val budget: Int) {

    private val clients = LinkedHashMap<String, McpClient>()
    private var connected = false
    private var remaining = budget.coerceAtLeast(0)

    val remainingBudget: Int get() = remaining

    /**
     * Connects to all configured streamable-http servers (initialize +
     * tools/list). Failures are logged and skipped; a server only becomes
     * usable when BOTH handshake and tools/list succeed.
     */
    suspend fun connect(mcpJson: String) {
        if (connected) return
        connected = true
        val parsed = McpConfig.parse(mcpJson)
        if (parsed.error != null) Log.w(TAG, "MCP JSON invalid: ${parsed.error}")
        if (parsed.skipped.isNotEmpty())
            Log.w(TAG, "MCP skipped (unsupported transport): ${parsed.skipped.joinToString()}")
        for (server in parsed.servers) {
            try {
                val client = McpClient(server)
                client.initialize()
                client.listTools()
                clients[server.name] = client
                Log.i(TAG, "MCP '${server.name}' ready")
            } catch (e: Exception) {
                Log.e(TAG, "MCP server '${server.name}' unavailable (continuing without it)", e)
            }
        }
    }

    /**
     * web-search-prime: query → results with title/url/snippet. Matches the
     * z.ai tool schema (`query`, `count`); response arrives as tool text —
     * try JSON first, fall back to URL sniffing.
     */
    suspend fun searchWeb(query: String, count: Int = 5): List<WebResult> {
        val client = clientFor("web-search-prime") ?: return emptyList()
        if (!spendBudget("web-search '$query'")) return emptyList()
        return try {
            val text = client.callTool(
                "web-search-prime",
                JSONObject().put("query", query).put("count", count).toString()
            )
            parseSearchResults(text)
        } catch (e: Exception) {
            Log.e(TAG, "web-search failed (graceful skip)", e)
            emptyList()
        }
    }

    /** web-reader: url → page text (budgeted, graceful failure → ""). */
    suspend fun readUrl(url: String): String {
        val client = clientFor("web-reader") ?: return ""
        if (!spendBudget("web-reader '$url'")) return ""
        return try {
            client.callTool("web-reader", JSONObject().put("url", url).toString())
        } catch (e: Exception) {
            Log.e(TAG, "web-reader failed (graceful skip)", e)
            ""
        }
    }

    /** True when a tool call was allowed (budget > 0 and server available). */
    private fun spendBudget(what: String): Boolean {
        if (remaining <= 0) {
            Log.w(TAG, "tool budget exhausted — skipping $what")
            return false
        }
        remaining--
        return true
    }

    private fun clientFor(name: String): McpClient? {
        clients[name]?.let { return it }
        // Tolerate alternate server naming (e.g. "web_search_prime").
        val fuzzy = clients.entries.firstOrNull {
            it.key.replace("-", "_").lowercase() == name.replace("-", "_").lowercase()
        }
        return fuzzy?.value
    }

    companion object {
        private const val TAG = "HootMCP"

        /** JSON array/object of results preferred; naive URL scan as fallback. */
        fun parseSearchResults(text: String): List<WebResult> {
            val jsonCandidate = LlmClient.extractJson(text)
            if (LlmClient.isJson(jsonCandidate)) {
                val out = ArrayList<WebResult>()
                fun push(o: JSONObject) {
                    val url = o.optString("url", o.optString("link")).ifBlank { return }
                    if (!url.startsWith("http")) return
                    out.add(
                        WebResult(
                            title = o.optString("title").ifBlank { url },
                            url = url,
                            snippet = o.optString("snippet", o.optString("content", o.optString("summary")))
                                .take(500)
                        )
                    )
                }
                runCatching {
                    val t = jsonCandidate.trim()
                    if (t.startsWith("[")) {
                        val arr = JSONArray(t)
                        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(::push)
                    } else {
                        val obj = JSONObject(t)
                        (obj.optJSONArray("results") ?: obj.optJSONArray("data")
                            ?: obj.optJSONArray("items"))?.let { arr ->
                            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(::push)
                        }
                        // Single-result shapes: {"url":…} at top level.
                        if (out.isEmpty()) push(obj)
                    }
                }
                if (out.isNotEmpty()) return out.distinctBy { it.url }
            }
            // Fallback: collect URLs from prose/markdown and use the line or title as snippet.
            val out = ArrayList<WebResult>()
            val urlRegex = Regex("https?://[^\\s)\\]>\"]+")
            for (line in text.lineSequence()) {
                val m = urlRegex.find(line) ?: continue
                val url = m.value.trimEnd('.', ',')
                if (out.any { it.url == url }) continue
                val title = line.replace(urlRegex, "").trim(' ', '-', '*', '[', ']', '"').take(120)
                out.add(WebResult(title.ifBlank { url }, url, line.take(300)))
                if (out.size >= 8) break
            }
            return out
        }
    }
}
