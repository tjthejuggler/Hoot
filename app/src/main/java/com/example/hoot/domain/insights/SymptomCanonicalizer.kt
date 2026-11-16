package com.example.hoot.domain.insights

/**
 * Symptom-key canonicalization (grouping feedback, 2026-11-16). Pure JVM.
 *
 * Research passes invent their own snake_case keys ("dry_itchy_skin") that
 * duplicate an existing canonical group ("dry_skin" — "Dry / rough skin").
 * This object computes a conservative RENAME plan that folds duplicates
 * into the dominant key, using two deterministic rules:
 *
 *  (a) identical significant-token sets (stop words removed), or
 *  (b) ≥ 2 shared significant tokens AND Jaccard ≥ 0.45, or
 *  (c) same trailing head token where that head occurs in ≤ 2 distinct
 *      symptom keys ("loss_of_appetite" + "poor_appetite" — head
 *      "appetite" is rare and distinctive).
 *
 * Canonical target = the key carried by MORE nutrients (seed keys win
 * ties via earlier first-seen). Only same-`kind` keys ever merge — a
 * deficiency symptom never merges with an excess one.
 */
object SymptomCanonicalizer {

    private val STOP_TOKENS = setOf("of", "the", "and", "with", "on", "in", "to", "a")

    data class KeyInfo(
        val key: String,
        val display: String,
        val kind: String,
        val nutrientCount: Int,
        val isSeed: Boolean
    )

    data class Rename(val fromKey: String, val toKey: String, val kind: String)

    fun tokens(key: String): Set<String> =
        key.split('_', '/', ' ').filter { it.isNotBlank() && it !in STOP_TOKENS }.toSet()

    /**
     * Full merge plan over a catalog snapshot. Idempotent: applying it and
     * re-running yields an empty plan.
     */
    fun plan(infos: List<KeyInfo>): List<Rename> {
        val renames = ArrayList<Rename>()
        val byKind = infos.groupBy { it.kind }
        for ((_, group) in byKind) {
            // Rank: more nutrients first; seed beats research; then name.
            val ranked = group.sortedWith(
                compareByDescending<KeyInfo> { it.nutrientCount }
                    .thenByDescending { it.isSeed }
                    .thenBy { it.key }
            )
            val remaining = ranked.toMutableList()
            while (remaining.isNotEmpty()) {
                val canonical = remaining.removeAt(0)
                val absorbed = remaining.filter { sameSymptom(canonical, it, group) }
                if (absorbed.isNotEmpty()) {
                    absorbed.forEach { renames += Rename(it.key, canonical.key, it.kind) }
                    remaining.removeAll(absorbed.toSet())
                }
            }
        }
        return renames
    }

    /** Rules (a)/(b)/(c) between a canonical candidate and another key. */
    internal fun sameSymptom(a: KeyInfo, b: KeyInfo, group: List<KeyInfo>): Boolean {
        if (a.key == b.key) return true
        val ta = tokens(a.key); val tb = tokens(b.key)
        if (ta.isEmpty() || tb.isEmpty()) return false
        val shared = ta intersect tb
        val union = ta union tb
        if (shared == ta || shared == tb) return true                       // (a)
        if (shared.size >= 2 && shared.size.toDouble() / union.size >= 0.45) return true   // (b)
        // (c) rare shared head token.
        val headA = a.key.substringAfterLast('_')
        val headB = b.key.substringAfterLast('_')
        if (headA == headB && headA.isNotBlank()) {
            val headRarity = group.count { it.key.substringAfterLast('_') == headA }
            if (headRarity <= 2) return true
        }
        return false
    }
}
