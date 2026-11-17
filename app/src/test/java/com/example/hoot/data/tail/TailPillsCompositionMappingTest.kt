package com.example.hoot.data.tail

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Composition credits from Tail pill-catalog option descriptions (§3 labels
 * endpoint) — the iodine-drops bug fix, 2026-11-17: pills rows now land with
 * canonical nutrientContributions parsed from the description at MAPPING
 * time, zero LLM calls, idempotent across full-backlog re-pulls.
 *
 * Two matching rules (anti-over-credit, catalog stacks):
 *  - WHOLE-ENTRY exact match first — an entry that IS a catalog pill whose
 *    name is a list ("Calcium, Magnesium, Zinc, D3, K2, Copper, Boron") is
 *    credited ONCE, never split;
 *  - otherwise per-item EXACT matches only after the split.
 */
class TailPillsCompositionMappingTest {

    private val units = mapOf(
        "iodine" to "mcg", "calcium" to "mg", "magnesium" to "mg",
        "omega3_epa_dha" to "mg", "vitamin_c" to "mg", "vitamin_d" to "mcg"
    )

    private fun entry(text: String) = TailTextEntry(
        entryId = "e1", habitName = "Took Pills",
        timestampRaw = "2026-11-17 08:00:00",
        timestampMs = 1_758_240_000_000, text = text
    )

    @Test fun `described option credits contributions on the mapped row`() {
        val labels = listOf(TailLabel("l1", "Iodine drops", "Iodine 150mcg"))
        val (rows, _) = supplementEntities(listOf(entry("Iodine drops")), "Took Pills", labels, units)
        assertEquals(1, rows.size)
        assertEquals("Iodine drops", rows[0].label)
        assertEquals("Iodine 150mcg", rows[0].description)
        assertEquals(150.0, JSONObject(rows[0].nutrientContributions).getDouble("iodine"), 1e-9)
    }

    @Test fun `multi-item entry matches options per item exactly`() {
        val labels = listOf(
            TailLabel("l1", "Iodine drops", "Iodine 150mcg"),
            TailLabel("l2", "Vitamin C 1g", "Vitamin C 1g")
        )
        val (rows, _) = supplementEntities(
            listOf(entry("Iodine drops\nVitamin C 1g")), "Took Pills", labels, units
        )
        assertEquals(2, rows.size)
        assertEquals(150.0, JSONObject(rows[0].nutrientContributions).getDouble("iodine"), 1e-9)
        assertEquals(1000.0, JSONObject(rows[1].nutrientContributions).getDouble("vitamin_c"), 1e-9)
    }

    @Test fun `catalog stack entry is credited once, never split`() {
        val stackName = "Calcium, Magnesium, Zinc, D3, K2, Copper, Boron"
        val labels = listOf(
            TailLabel(
                "l1", stackName,
                "Calcium 500mg, Magnesium 200mg, Zinc 5mg, D3 20mcg, K2 200mcg, Copper 1mg, Boron 0.6mg"
            )
        )
        val (rows, _) = supplementEntities(listOf(entry(stackName)), "Took Pills", labels, units)
        // ONE row for the whole stack — pre-fix semantics would have split
        // the name into 7 items and credited the panel 7 times.
        assertEquals(1, rows.size)
        assertEquals(stackName.take(80), rows[0].label)
        val c = JSONObject(rows[0].nutrientContributions)
        assertEquals(500.0, c.getDouble("calcium"), 1e-9)
        assertEquals(200.0, c.getDouble("magnesium"), 1e-9)
        assertEquals(20.0, c.getDouble("vitamin_d"), 1e-9)
    }

    @Test fun `whole entry match tolerates case and punctuation drift`() {
        val labels = listOf(TailLabel("l1", "Omega-3 Sunday Natural", "DHA 350mg, EPA 180mg"))
        val (rows, _) = supplementEntities(
            listOf(entry("omega 3 sunday natural")), "Took Pills", labels, units
        )
        assertEquals(1, rows.size)
        assertEquals(530.0, JSONObject(rows[0].nutrientContributions).getDouble("omega3_epa_dha"), 1e-9)
    }

    @Test fun `split items do not fuzzy-match catalog names`() {
        // "calcium" alone must NOT credit the mineral stack's full panel —
        // per-item credits are exact-only after the whole-entry check.
        val stackName = "Calcium, Magnesium, Zinc, D3, K2, Copper, Boron"
        val labels = listOf(
            TailLabel("l1", stackName, "Calcium 500mg, Magnesium 200mg, Zinc 5mg")
        )
        val (rows, _) = supplementEntities(listOf(entry("calcium")), "Took Pills", labels, units)
        assertEquals(1, rows.size)
        assertEquals("[]", rows[0].nutrientContributions)
    }

    @Test fun `undescribed items keep the legacy unresolved mapping`() {
        val labels = listOf(TailLabel("l1", "Iodine drops", "Iodine 150mcg"))
        val (rows, _) = supplementEntities(
            listOf(entry("Iodine drops\nAshwagandha")), "Took Pills", labels, units
        )
        assertEquals("[]", rows[1].nutrientContributions)
        assertNull(rows[1].description)
    }

    @Test fun `description without parseable nutrients leaves the row unresolved`() {
        val labels = listOf(TailLabel("l1", "Ashwagandha", "Ashwagandha"))
        val (rows, _) = supplementEntities(listOf(entry("Ashwagandha")), "Took Pills", labels, units)
        assertEquals("[]", rows[0].nutrientContributions)
        assertEquals("Ashwagandha", rows[0].description)
    }

    @Test fun `no labels falls back to legacy behaviour`() {
        val (rows, _) = supplementEntities(listOf(entry("Iodine drops")), "Took Pills", emptyList(), units)
        assertEquals("[]", rows[0].nutrientContributions)
        assertNull(rows[0].description)
    }

    @Test fun `re-running the same input is idempotent`() {
        val labels = listOf(TailLabel("l1", "Iodine drops", "Iodine 150mcg"))
        val entries = listOf(entry("Iodine drops"))
        val first = supplementEntities(entries, "Took Pills", labels, units).rows
        val second = supplementEntities(entries, "Took Pills", labels, units).rows
        assertEquals(first, second)
    }

    @Test fun `typed doses still parse alongside composition`() {
        val labels = listOf(TailLabel("l1", "Magnesium Glycinate 150mg", "Magnesium Glycinate 150mg"))
        val (rows, _) = supplementEntities(
            listOf(entry("Magnesium Glycinate 150mg")), "Took Pills", labels, units
        )
        assertEquals(150.0, rows[0].doseAmount!!, 1e-9)
        assertEquals("mg", rows[0].doseUnit)
        assertEquals(150.0, JSONObject(rows[0].nutrientContributions).getDouble("magnesium"), 1e-9)
    }

    @Test fun `multi line submission credits each described line`() {
        // Tail pill-log: one submission = one entry value with '\n'-joined
        // lines (each line = one serving). Both lines credit separately.
        val labels = listOf(
            TailLabel("l1", "Iodine drops", "Iodine 150mcg"),
            TailLabel("l2", "Iodine drops", "Iodine 150mcg") // same name listed once + dupe guard
        )
        val (rows, _) = supplementEntities(
            listOf(entry("Iodine drops\nIodine drops")), "Took Pills", labels, units
        )
        assertEquals(2, rows.size)
        for (row in rows) {
            assertEquals(150.0, JSONObject(row.nutrientContributions).getDouble("iodine"), 1e-9)
        }
    }

    @Test fun `short garbage items never match options`() {
        val labels = listOf(TailLabel("l1", "D3 25µg, K2 20µg", "D3 25mcg, K2 20mcg"))
        val (rows, _) = supplementEntities(listOf(entry("g")), "Took Pills", labels, units)
        assertEquals("[]", rows[0].nutrientContributions)
    }
}
