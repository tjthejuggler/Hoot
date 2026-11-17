# ADR: Symptom keys merge only on literal identity

**Date:** 2026-11-17
**Status:** Accepted

## Context
User feedback: the internal LLM/research flow appeared to rewrite "Dry / rough skin" into "Pale skin". Root cause was NOT the LLM itself but the deterministic `SymptomCanonicalizer` invoked after every research pass and at every app start (`NutritionPipeline.start()`). Its fuzzy rules — (b) token Jaccard ≥ 0.45 and (c) shared rare trailing head token (≤ 2 occurrences) — merged merely-similar symptoms. Seed catalog has exactly `dry_skin` and `pale_skin` ending in "skin" → rule (c) merged them, with `pale_skin` winning on nutrient count. The same rule shape threatened `bone_pain`/`joint_pain` (before "muscle_pain" seeded) and rule (b) threatened `dry_eyes`/`dry_skin`.

## Decision
1. `SymptomCanonicalizer.sameSymptom()` now folds keys ONLY on literal identity: one significant-token set is a subset of the other AND both keys share the same trailing head token ("dry_rough_skin" → "dry_skin"). Fuzzy rules (b) and (c) deleted.
2. A tiny curated `LITERAL_SYNONYMS` table handles clinically identical phrasings that share no tokens ("loss_of_appetite" ≡ "poor_appetite", "impaired_wound_healing" ≡ "slow_healing"). It fires only when the canonical target key exists in the same kind group.
3. Similar-but-different symptoms NEVER merge (dry/rough skin vs pale skin: texture vs color). When in doubt, distinct.
4. One-time repair: `NutrientSymptomRepository.repairOverMergedKeys()` + row-scoped DAO `renameSymptomKeyForDisplay` move any row whose seed `displayName` identifies a different symptom than its current key back to the true key (old renames preserved displayName, making the corruption deterministically detectable). Runs at startup before canonicalization; idempotent.
5. `NutrientResearcher` prompts (system + user) now explicitly forbid combining/broadening symptoms, require reusing the exact known key for literally-identical symptoms, and demand separate keys for merely-similar ones.

## Consequences
- Symptom chips stay distinct; grouping only collapses true duplicates.
- Future merge candidates must be added explicitly to `LITERAL_SYNONYMS` (code review), not inferred.
- Regression tests in `SymptomCanonicalizerTest` pin the dry-skin/pale-skin, bone/joint-pain, dry-eyes/dry-skin and head-mismatch cases.