package com.example.hoot.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HttpException(val code: Int, message: String) : IOException(message) {
    override fun toString(): String = "HTTP $code: ${message?.take(400)}"
}

data class HttpResponse(val code: Int, val body: String, val headers: Map<String, List<String>>)

/**
 * Minimal HttpURLConnection wrapper — port of Inuit's `data/llm/Http.kt`
 * (no OkHttp dependency needed for hand-rolled JSON clients; the Coil OkHttp
 * bundle is unrelated). Streaming SSE variant is omitted: Hoot only needs
 * non-streaming chat completions + MCP JSON-RPC.
 */
object Http {

    /** Shared daemon scheduler for connection deadlines (see [request]). */
    private val watchdog =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "hoot-http-watchdog").apply { isDaemon = true }
        }

    suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
        connectTimeoutMs: Int = 20_000,
        readTimeoutMs: Int = 180_000,
        totalTimeoutMs: Long = 240_000,
        contentType: String = "application/json"
    ): HttpResponse = withContext(Dispatchers.IO) {
        request("POST", url, headers, body, connectTimeoutMs, readTimeoutMs, totalTimeoutMs, contentType)
    }

    suspend fun get(
        url: String,
        headers: Map<String, String>,
        connectTimeoutMs: Int = 20_000,
        readTimeoutMs: Int = 60_000,
        totalTimeoutMs: Long = 120_000
    ): HttpResponse = withContext(Dispatchers.IO) {
        request("GET", url, headers, null, connectTimeoutMs, readTimeoutMs, totalTimeoutMs)
    }

    /**
     * [readTimeoutMs] only fires when NO bytes arrive at all; a drip-fed or
     * wedged response can outlive it indefinitely. [totalTimeoutMs] is a hard
     * deadline enforced by a watchdog that disconnects the connection — the
     * blocked read then throws immediately and the caller's retry machinery
     * takes over.
     */
    private fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        totalTimeoutMs: Long,
        contentType: String = "application/json"
    ): HttpResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        val deadline = watchdog.schedule({ conn.disconnect() }, totalTimeoutMs, TimeUnit.MILLISECONDS)
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.instanceFollowRedirects = true
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return HttpResponse(code, text, conn.headerFields ?: emptyMap())
        } finally {
            deadline.cancel(false)
            conn.disconnect()
        }
    }
}
