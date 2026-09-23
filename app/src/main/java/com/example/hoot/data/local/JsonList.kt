package com.example.hoot.data.local

/**
 * `["a","b"]` → [a, b]; tolerant of null/blank/invalid JSON.
 *
 * Shared single home for the JSON-string-list decoding previously duplicated
 * in AppGraph and three ViewModels (refactor 2026-09-22, P6).
 */
internal fun jsonList(raw: String?): List<String> = runCatching {
    val arr = org.json.JSONArray(raw ?: "[]")
    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
}.getOrDefault(emptyList())
