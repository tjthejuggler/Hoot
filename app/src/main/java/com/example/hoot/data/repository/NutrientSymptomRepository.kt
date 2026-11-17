package com.example.hoot.data.repository

import com.example.hoot.data.local.SymptomSeed
import com.example.hoot.data.local.dao.NutrientSymptomDao
import com.example.hoot.data.local.entity.NutrientSymptomEntity
import com.example.hoot.domain.insights.SymptomCanonicalizer

/**
 * Nutrient ↔ symptom associations (deficiency system, 2026-11-16): curated
 * seed + research-agent rows, plus the lazy seed fill for installs that
 * migrated to v8 (migration can't use DAOs).
 */
class NutrientSymptomRepository(
    private val dao: NutrientSymptomDao
) {
    suspend fun all(): List<NutrientSymptomEntity> = dao.all()

    /**
     * Knowledge-pack import (feedback 2026-11-17): id-keyed upsert of
     * another install's associations. Deterministic ids make re-importing
     * the same pack converge (idempotent).
     */
    suspend fun importAssociations(rows: List<NutrientSymptomEntity>) {
        if (rows.isNotEmpty()) dao.upsertAll(rows)
    }

    suspend fun forNutrient(nutrientId: String): List<NutrientSymptomEntity> =
        dao.forNutrient(nutrientId)

    suspend fun forSymptom(symptomKey: String, kind: String): List<NutrientSymptomEntity> =
        dao.forSymptom(symptomKey, kind)

    /** Research-pass rows for one nutrient (UI "researched" badge). */
    suspend fun researchCountFor(nutrientId: String): Int = dao.researchCountFor(nutrientId)

    /** Persists research results (replaces that nutrient's RESEARCH rows). */
    suspend fun applyResearch(rows: List<NutrientSymptomEntity>) {
        rows.firstOrNull()?.nutrientId?.let { dao.clearResearchFor(it) }
        if (rows.isNotEmpty()) dao.upsertAll(rows)
    }

    /**
     * Manual association management (feedback 2026-11-17): removes ONE
     * nutrient ↔ symptom link (mistaken research row or wrong seed entry).
     * The row is deleted by its deterministic id; missing id is a no-op.
     */
    suspend fun removeAssociation(nutrientId: String, symptomKey: String, kind: String) {
        dao.deleteById("$nutrientId:$kind:$symptomKey")
    }

    /**
     * Manual association add (feedback 2026-11-17): upserts ONE link with
     * source="MANUAL". Matches an existing catalog symptom by display name
     * first (groups instead of forking a near-duplicate key), else derives a
     * snake_case key with the same rule as the research parser. Returns the
     * persisted row (null when the label was blank).
     */
    suspend fun addAssociation(
        nutrientId: String,
        symptomLabel: String,
        kind: String,
        confidence: Double = 0.7
    ): NutrientSymptomEntity? {
        val display = symptomLabel.trim()
        if (display.isEmpty() || nutrientId.isBlank()) return null
        val known = dao.all()
        val key = known.firstOrNull { it.displayName.equals(display, ignoreCase = true) }
            ?.symptomKey
            ?: normalizeSymptomKey(display)
        val row = NutrientSymptomEntity(
            id = "$nutrientId:$kind:$key",
            nutrientId = nutrientId,
            symptomKey = key,
            displayName = display.replaceFirstChar { it.uppercase() },
            kind = kind,
            confidence = confidence.coerceIn(0.05, 1.0),
            source = "MANUAL",
            researchedAt = null
        )
        dao.upsertAll(listOf(row))
        return row
    }

    /** snake_case key from a free-text label (same rule as NutrientResearcher). */
    private fun normalizeSymptomKey(label: String): String = label.trim().lowercase()
        .replace(' ', '_')
        .replace(Regex("[^a-z0-9_]"), "")

    /**
     * Seeds the curated associations when the table is empty — covers v7→v8
     * migrations (raw-SQL migration created the table; SeedCallback only
     * fires on fresh creates). Idempotent via upsert.
     */
    suspend fun ensureSeeded() {
        if (dao.all().isEmpty()) {
            dao.upsertAll(SymptomSeed.seed())
        }
    }

    /**
     * Canonicalization (grouping feedback, 2026-11-16): folds duplicate
     * symptom keys into their dominant group via [SymptomCanonicalizer]
     * (deterministic token rules — literal same-symptom only, same-kind
     * only). Returns the applied renames (empty when already canonical).
     */
    suspend fun canonicalizeKeys(): List<SymptomCanonicalizer.Rename> {
        val rows = dao.all()
        if (rows.isEmpty()) return emptyList()
        val infos = rows.groupBy { it.kind to it.symptomKey }
            .map { (kindKey, group) ->
                SymptomCanonicalizer.KeyInfo(
                    key = kindKey.second,
                    display = group.first().displayName,
                    kind = kindKey.first,
                    nutrientCount = group.map { it.nutrientId }.distinct().size,
                    isSeed = group.any { it.source == "SEED" }
                )
            }
        val renames = SymptomCanonicalizer.plan(infos)
        for (r in renames) dao.renameSymptomKey(r.fromKey, r.toKey, r.kind)
        return renames
    }

    /**
     * One-time repair for over-merged keys (feedback 2026-11-17): the old
     * fuzzy canonicalizer folded DISTINCT symptoms together (dry/rough skin
     * into "Pale skin", bone pain into joint pain). The rename kept each
     * row's original seed displayName, so any row whose display belongs to
     * a DIFFERENT seed symptom than its current key is moved back to that
     * symptom's true key — row-scoped, so legitimately-keyed rows on the
     * same key are untouched. Idempotent.
     */
    suspend fun repairOverMergedKeys(): Int {
        val rows = dao.all()
        if (rows.isEmpty()) return 0
        val keyForDisplay = SymptomSeed.allKeys().entries.associate { (k, v) -> v to k }
        var repaired = 0
        val seen = HashSet<Triple<String, String, String>>()  // fromKey→toKey→display
        for (row in rows) {
            val trueKey = keyForDisplay[row.displayName] ?: continue
            if (trueKey == row.symptomKey) continue
            if (!seen.add(Triple(row.symptomKey, trueKey, row.displayName))) continue
            dao.renameSymptomKeyForDisplay(row.symptomKey, trueKey, row.kind, row.displayName)
            repaired++
        }
        return repaired
    }
}
