package com.example.hoot.data.tail

import org.junit.Assert.assertEquals
import org.junit.Test

/** Multi-item pills ingestion: per-item rows + stable idempotent dedup keys. */
class TailPillsMappingTest {

    private fun entry(entryId: String?, tsRaw: String, tsMs: Long, text: String) =
        TailTextEntry(entryId = entryId, habitName = "Took Pills", timestampRaw = tsRaw, timestampMs = tsMs, text = text)

    @Test fun `one entry with three items produces three rows`() {
        val (rows, maxTs) = supplementEntities(
            listOf(entry("e1", "2026-09-19 08:00:00", 1_758_240_000_000, "iron\nvitamin D\nfish oil")),
            "Took Pills"
        )
        assertEquals(3, rows.size)
        assertEquals(listOf("iron", "vitamin D", "fish oil"), rows.map { it.label })
        assertEquals(1_758_240_000_000L, maxTs)
    }

    @Test fun `item zero keeps the legacy entry key so re-sync overwrites in place`() {
        val (rows, _) = supplementEntities(
            listOf(entry("e1", "2026-09-19 08:00:00", 1_758_240_000_000, "iron, zinc")),
            "Took Pills"
        )
        assertEquals("tail:e1", rows[0].id)
        assertEquals("tail:e1#1", rows[1].id)
    }

    @Test fun `timestamp fallback keys stay stable when tail ships no ids`() {
        val (rows, _) = supplementEntities(
            listOf(entry(null, "2026-09-19 08:00:00", 1_758_240_000_000, "iron, zinc")),
            "Took Pills"
        )
        assertEquals("tail:pills:took pills:2026-09-19 08:00:00", rows[0].id)
        assertEquals("tail:pills:took pills:2026-09-19 08:00:00#1", rows[1].id)
    }

    @Test fun `re-running the same input is idempotent`() {
        val entries = listOf(entry("e1", "2026-09-19 08:00:00", 1_758_240_000_000, "iron, zinc, B-12"))
        val first = supplementEntities(entries, "Took Pills").first
        val second = supplementEntities(entries, "Took Pills").first
        assertEquals(first.map { it.id }, second.map { it.id })
        assertEquals(first, second)
    }

    @Test fun `provenance fields are shared across items of one entry`() {
        val ts = 1_758_240_000_000
        val (rows, _) = supplementEntities(
            listOf(entry("e1", "2026-09-19 08:00:00", ts, "iron, zinc")),
            "Took Pills"
        )
        val expectedDay = java.time.Instant.ofEpochMilli(ts)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
        for (row in rows) {
            assertEquals("Took Pills", row.tailHabitName)
            assertEquals(ts.toLong(), row.timestamp)
            assertEquals(expectedDay, row.day)
            assertEquals("tail", row.source)
            assertEquals("[]", row.nutrientContributions)
        }
    }

    @Test fun `comma-in-number doses do not create phantom items`() {
        val (rows, _) = supplementEntities(
            listOf(entry("e1", "2026-09-19 08:00:00", 1_758_240_000_000, "Vitamin D 1,000 IU, Omega-3")),
            "Took Pills"
        )
        assertEquals(2, rows.size)
        assertEquals("Vitamin D 1,000 IU", rows[0].label)
        assertEquals("Omega-3", rows[1].label)
    }

    @Test fun `blank and invalid entries yield no rows`() {
        val (rows, maxTs) = supplementEntities(
            listOf(
                entry("e1", "2026-09-19 08:00:00", 0, "iron"),   // invalid timestamp
                entry("e2", "2026-09-19 09:00:00", 1_758_243_600_000, "  \n ")
            ),
            "Took Pills"
        )
        assertEquals(0, rows.size)
        assertEquals(1_758_243_600_000L, maxTs)
    }
}
