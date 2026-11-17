package com.example.hoot.domain.insights

/**
 * Symptom-key canonicalization (grouping feedback, 2026-11-16; tightened
 * 2026-11-17 after the dry-skin→pale-skin overmerge). Pure JVM.
 *
 * Research passes invent their own snake_case keys ("dry_itchy_skin") that
 * duplicate an existing canonical group ("dry_skin" — "Dry / rough skin").
 * This object computes an ULTRA-CONSERVATIVE rename plan that folds a key
 * ONLY when it is literally the same symptom as the canonical key:
 *
 *  (a) one key's significant-token set (stop words removed) is a subset of
 *      the other's AND both end in the same head token — "dry_rough_skin"
 *      and "dry_itchy_skin" fold into "dry_skin", or
 *  (b) the pair is listed in [LITERAL_SYNONYMS] — a tiny curated table of
 *      clinically identical phrasings ("loss_of_appetite" ≡ "poor_appetite").
 *
 * Distinct symptoms NEVER merge. The old fuzzy rules (Jaccard similarity,
 * shared rare head token) were removed because they merged merely-similar
 * symptoms: "dry_skin" ("Dry / rough skin") got folded into "pale_skin"
 * because both end in "skin" — but dry/rough skin (texture) and pale skin
 * (color) are different symptoms. Similar is NOT same.
 *
 * Canonical target = the key carried by MORE nutrients (seed keys win
 * ties via earlier first-seen). Only same-`kind` keys ever merge — a
 * deficiency symptom never merges with an excess one.
 */
object SymptomCanonicalizer {

    private val STOP_TOKENS = setOf("of", "the", "and", "with", "on", "in", "to", "a")

    /**
     * Curated pairs of literally-identical symptoms whose keys share no
     * token subset ("loss of appetite" ≡ "poor appetite"). Direction is
     * fixed: the left key folds INTO the right (canonical) key. Anything
     * not in this table and not a token subset stays a distinct symptom.
     */
    private val LITERAL_SYNONYMS = mapOf(
        "loss_of_appetite" to "poor_appetite",
        "reduced_appetite" to "poor_appetite",
        "impaired_wound_healing" to "slow_healing",
        "slow_wound_healing" to "slow_healing"
    )

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
            // Curated literal synonyms first, direction fixed by the table
            // (only fires when the canonical key actually exists in-group).
            for (info in group) {
                val target = LITERAL_SYNONYMS[info.key]
                if (target != null && target != info.key &&
                    group.any { it.key == target && it.key != LITERAL_SYNONYMS[it.key] }
                ) {
                    renames += Rename(info.key, target, info.kind)
                }
            }
            val foldedSources = renames.map { it.fromKey }.toSet()
            // Rank: more nutrients first; seed beats research; then name.
            val ranked = group.filter { it.key !in foldedSources }
                .sortedWith(
                    compareByDescending<KeyInfo> { it.nutrientCount }
                        .thenByDescending { it.isSeed }
                        .thenBy { it.key }
                )
            val remaining = ranked.toMutableList()
            while (remaining.isNotEmpty()) {
                val canonical = remaining.removeAt(0)
                val absorbed = remaining.filter { sameSymptom(canonical, it) }
                if (absorbed.isNotEmpty()) {
                    absorbed.forEach { renames += Rename(it.key, canonical.key, it.kind) }
                    remaining.removeAll(absorbed.toSet())
                }
            }
        }
        return renames
    }

    /**
     * Rule (a): literal same-symptom test — one token set is a subset of
     * the other AND both keys share the same trailing head token. Anything
     * else (shared body part alone, similar qualifiers, fuzzy overlap) is
     * a DISTINCT symptom and never merges: "dry_skin" vs "pale_skin" share
     * only "skin" → no merge; "dry_rough_skin" vs "dry_skin" → merge.
     */
    internal fun sameSymptom(a: KeyInfo, b: KeyInfo): Boolean {
        if (a.key == b.key) return true
        val ta = tokens(a.key); val tb = tokens(b.key)
        if (ta.isEmpty() || tb.isEmpty()) return false
        val shared = ta intersect tb
        if (shared != ta && shared != tb) return false        // not a literal subset
        val headA = a.key.substringAfterLast('_')
        val headB = b.key.substringAfterLast('_')
        return headA == headB && headA.isNotBlank()           // same head token
    }
}
