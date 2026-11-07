package com.example.hoot.data.tail

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tail Bridge relay for the TailCue export.
 *
 * Replaces the old SAF-folder export with a zero-configuration push: Hoot
 * reads the Tail Bridge server's URL + auth token straight from Tail's
 * companion content provider (`/v2/bridge_config`) and POSTs the export
 * JSON to the bridge, which writes it where TailCue's ingest_hoot()
 * looks. No folder picking on either side.
 */
object TailBridgeRelay {

    private const val TAG = "TailBridgeRelay"
    private const val PATH_BRIDGE_CONFIG = "v2/bridge_config"
    private const val CONNECT_TIMEOUT = 5_000
    private const val READ_TIMEOUT = 15_000

    data class BridgeConfig(val url: String, val token: String)

    /**
     * Fetches the bridge connection details from Tail's provider.
     * Null when Tail is missing, the companion API is off, or no bridge
     * is configured — callers treat that as "not connected" (soft skip).
     */
    fun fetchBridgeConfig(context: Context, tailPackage: String): BridgeConfig? = try {
        val authority = if (tailPackage == TailClient.CANONICAL_TAIL_PACKAGE) {
            "com.example.tail.provider"
        } else {
            "$tailPackage.provider"
        }
        val uri = Uri.parse("content://$authority/$PATH_BRIDGE_CONFIG")
        context.contentResolver.query(uri, arrayOf("bridge_url", "bridge_token"), null, null, null)
            ?.use { c ->
                if (c.moveToFirst() && c.count > 0) {
                    val url = c.getString(0)?.takeIf { it.isNotBlank() } ?: return null
                    val token = c.getString(1)?.takeIf { it.isNotBlank() } ?: return null
                    BridgeConfig(url, token)
                } else null
            }
    } catch (e: Exception) {
        Log.w(TAG, "bridge config fetch failed: ${e.message}")
        null
    }

    /**
     * POSTs the export JSON body to the bridge's /api/v1/hoot/export.
     * True on HTTP 2xx.
     */
    suspend fun pushExport(config: BridgeConfig, json: String): Boolean =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val bytes = json.toByteArray(Charsets.UTF_8)
                val url = URL("${config.url.trim().trimEnd('/')}/api/v1/hoot/export")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = CONNECT_TIMEOUT
                    readTimeout = READ_TIMEOUT
                    setRequestProperty("X-App-Auth", config.token)
                    setRequestProperty("User-Agent", "Hoot-Android-App/1.0")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setFixedLengthStreamingMode(bytes.size)
                }
                conn.outputStream.use { it.write(bytes) }
                val code = conn.responseCode
                if (code in 200..299) {
                    Log.i(TAG, "TailCue export pushed via Tail Bridge (HTTP $code)")
                    true
                } else {
                    Log.w(TAG, "Tail Bridge rejected export: HTTP $code")
                    false
                }
            } catch (e: Exception) {
                Log.w(TAG, "Tail Bridge push failed: ${e.message}")
                false
            } finally {
                conn?.disconnect()
            }
        }
}
