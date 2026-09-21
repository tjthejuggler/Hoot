package com.example.hoot.domain.nutrition

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Single source of truth for local-time day keys ("yyyy-MM-dd").
 *
 * Every day computation in the pipeline (Tail sync ingest, supplement days,
 * intake ledger, score snapshots, Today/History/Insights queries) must go
 * through this helper so timestamps can never land on a different day row
 * than the one the UI reads (bug: UTC-vs-local day-key divergence).
 *
 * Pure JVM — unit-tested in `DayKeysTest.kt`.
 */
object DayKeys {

    /** Today's local day key for [zone] (system zone by default). */
    fun todayKey(zone: ZoneId = ZoneId.systemDefault()): String =
        LocalDate.now(zone).toString()

    /** Epoch millis → local day key. */
    fun fromEpoch(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toString()

    /** Calendar arithmetic on a day key (parse failure → input unchanged). */
    fun minusDays(day: String, days: Long): String =
        day.plusDaysLocal(-days)

    /** Calendar arithmetic on a day key (parse failure → input unchanged). */
    fun plusDays(day: String, days: Long): String =
        day.plusDaysLocal(days)

    private fun String.plusDaysLocal(days: Long): String =
        runCatching { LocalDate.parse(this).plusDays(days).toString() }.getOrElse { this }
}
