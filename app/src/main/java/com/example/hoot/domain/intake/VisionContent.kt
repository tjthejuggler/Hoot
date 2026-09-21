package com.example.hoot.domain.intake

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Builds the OpenAI-compatible multimodal user content for photo captures —
 * the same wire format Tail's vision pipeline uses:
 *
 * ```
 * [ {"type":"text","text":"Meal description: \"…\""},
 *   {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,…"}} ]
 * ```
 *
 * [encodeJpegDataUrl] downscales (max 1280 px long edge) and JPEG-compresses
 * like Tail's compressAndEncode; returns null when the file can't be read, so
 * the caller can degrade gracefully to text-only analysis.
 */
object VisionContent {

    /** Long-edge cap before JPEG compression (Tail uses the same budget). */
    const val MAX_IMAGE_DIMENSION = 1280

    /** JPEG quality used after downscale (Tail parity). */
    const val JPEG_QUALITY = 85

    /**
     * Multimodal user content array; [description] is the RAW capture text,
     * wrapped in the Tail "Meal description: …" envelope exactly once here.
     * [dataUrl] null → plain text content. Pure JSON assembly — testable
     * with a fake data URL.
     */
    fun userContent(description: String, dataUrl: String?): Any {
        val wrapped = CapturePrompts.mealUserText(description)
        if (dataUrl == null) return wrapped
        return JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", wrapped)
            })
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().put("url", dataUrl))
            })
        }
    }

    /**
     * Loads, downscales (max [MAX_IMAGE_DIMENSION] on the long edge), and
     * JPEG-compresses the image, returning a base64 data URL. Null on any
     * failure — the capture then falls back to text-only.
     */
    fun encodeJpegDataUrl(file: File): String? = runCatching {
        val source = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            ?: return null
        val bitmap = downscale(source)
        val bytes = java.io.ByteArrayOutputStream().use { out ->
            android.graphics.Bitmap.CompressFormat.JPEG.let { fmt ->
                bitmap.compress(fmt, JPEG_QUALITY, out)
            }
            out.toByteArray()
        }
        if (bitmap !== source) bitmap.recycle()
        source.recycle()
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        "data:image/jpeg;base64,$b64"
    }.getOrNull()

    private fun downscale(source: android.graphics.Bitmap): android.graphics.Bitmap {
        val max = maxOf(source.width, source.height)
        if (max <= MAX_IMAGE_DIMENSION) return source
        val scale = MAX_IMAGE_DIMENSION.toFloat() / max
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        return android.graphics.Bitmap.createScaledBitmap(source, w, h, true)
    }
}
