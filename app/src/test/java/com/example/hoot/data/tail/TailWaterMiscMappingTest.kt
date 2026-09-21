package com.example.hoot.data.tail

import com.example.hoot.domain.nutrition.parseWaterAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v5 water/misc habit ingestion: parseable water amounts + raw misc rows. */
class TailWaterMiscMappingTest {

    private fun entry(entryId: String?, tsRaw: String, tsMs: Long, text: String) =
        TailTextEntry(
            entryId = entryId, habitName = "Water",
            timestampRaw = tsRaw, timestampMs = tsMs, text = text
        )

    // ── parseWaterAmount (2026-09: keeps the TEXT's own unit; conversion
    //    happens at read time via WaterIntake.liters + the unit mode) ──────

    @Test fun `milliliter amounts parse as-is`() {
        assertEquals(500.0 to "ml", parseWaterAmount("500 ml"))
        assertEquals(250.0 to "ml", parseWaterAmount("250ml"))
    }

    @Test fun `liter amounts keep their unit`() {
        assertEquals(1.5 to "l", parseWaterAmount("1.5 l"))
        assertEquals(2.0 to "l", parseWaterAmount("2 liters"))
    }

    @Test fun `ounce amounts keep their unit`() {
        assertEquals(16.0 to "oz", parseWaterAmount("16 oz"))
        assertEquals(8.0 to "oz", parseWaterAmount("8 fl oz"))
    }

    @Test fun `unitless numbers parse with null unit`() {
        assertEquals(2.0 to null, parseWaterAmount("2"))
        assertEquals(3.0 to null, parseWaterAmount("3 glasses"))
        assertEquals(2500.0 to null, parseWaterAmount("2500"))   // Tail raw ml
    }

    @Test fun `comma decimal amounts parse`() {
        assertEquals(1.5 to "l", parseWaterAmount("1,5 l"))
    }

    @Test fun `text without numbers yields null`() {
        assertNull(parseWaterAmount("drank water"))
        assertNull(parseWaterAmount(""))
    }

    // ── waterEntities ─────────────────────────────────────────────────────

    @Test fun `water rows keep raw text and parsed amount`() {
        val (rows, _) = waterEntities(
            listOf(entry("e1", "2026-09-19 08:00:00", 1_758_240_000_000, "500 ml")),
            "Water"
        )
        assertEquals(1, rows.size)
        assertEquals("water", rows[0].kind)
        assertEquals("500 ml", rows[0].text)
        assertEquals(500.0, rows[0].amount!!, 1e-9)
        assertEquals("ml", rows[0].unit)
        assertEquals("tail:e1", rows[0].id)
    }

    @Test fun `water rows with unparseable text store amount null`() {
        val (rows, _) = waterEntities(
            listOf(entry("e1", "2026-09-19 08:00:00", 1_758_240_000_000, "drank water")),
            "Water"
        )
        assertNull(rows[0].amount)
        assertNull(rows[0].unit)
    }

    // ── counter-habit rows (the user's actual water habit shape) ─────────

    @Test fun `counter value rows become unitless amounts`() {
        val counter = entry("c1", "2026-09-20 00:00:00", 1_758_240_000_000, "").copy(value = 2500.0)
        val (rows, _) = waterEntities(listOf(counter), "Water")
        assertEquals(1, rows.size)
        assertEquals(2500.0, rows[0].amount!!, 1e-9)
        assertNull(rows[0].unit)   // water-unit mode interprets bare ml
        assertEquals("Water count: 2500", rows[0].text)
    }

    @Test fun `counter zero rows contribute nothing`() {
        val counter = entry("c1", "2026-09-20 00:00:00", 1_758_240_000_000, "").copy(value = 0.0)
        val (rows, _) = waterEntities(listOf(counter), "Water")
        assertNull(rows[0].amount)
    }

    @Test fun `text rows win over value when both present`() {
        val both = entry("b1", "2026-09-20 08:00:00", 1_758_240_000_000, "250 ml").copy(value = 2500.0)
        val (rows, _) = waterEntities(listOf(both), "Water")
        assertEquals(250.0, rows[0].amount!!, 1e-9)
        assertEquals("ml", rows[0].unit)
    }

    @Test fun `invalid timestamps are skipped and cursor is max ts`() {
        val (rows, maxTs) = waterEntities(
            listOf(
                entry("e1", "2026-09-19 08:00:00", 0, "500 ml"),
                entry("e2", "2026-09-19 09:00:00", 1_758_243_600_000, "250 ml")
            ),
            "Water"
        )
        assertEquals(1, rows.size)
        assertEquals(1_758_243_600_000L, maxTs)
    }

    // ── miscEntities ──────────────────────────────────────────────────────

    @Test fun `misc rows are raw text with null amount`() {
        val (rows, _) = miscEntities(
            listOf(entry("e1", "2026-09-19 08:00:00", 1_758_240_000_000, "electrolytes")),
            "Electrolytes"
        )
        assertEquals(1, rows.size)
        assertEquals("misc", rows[0].kind)
        assertEquals("electrolytes", rows[0].text)
        assertNull(rows[0].amount)
        assertNull(rows[0].unit)
        assertEquals("Electrolytes", rows[0].habitName)
    }

    @Test fun `timestamp fallback keys are stable and kind-scoped`() {
        val (rows, _) = miscEntities(
            listOf(entry(null, "2026-09-19 08:00:00", 1_758_240_000_000, "electrolytes")),
            "Electrolytes"
        )
        assertEquals("tail:misc:electrolytes:2026-09-19 08:00:00", rows[0].id)
    }

    @Test fun `re-running the same input is idempotent`() {
        val entries = listOf(entry("e1", "2026-09-19 08:00:00", 1_758_240_000_000, "500 ml"))
        assertEquals(waterEntities(entries, "Water"), waterEntities(entries, "Water"))
        assertEquals(miscEntities(entries, "Water"), miscEntities(entries, "Water"))
    }
}
