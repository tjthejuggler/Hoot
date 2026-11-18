# ADR: Supplemental-only Upper Limits and manual ✍️ provenance markers

Date: 2026-11-18
Status: accepted (rev 2 — user feedback round 2)

## Context
1. Magnesium's seed data (RDA 400 mg, UL 350 mg) is NIH-correct, but the
   350 mg UL applies ONLY to supplemental magnesium — food has no UL. The
   score pipeline (ScoreSnapshotter) already suppressed it, but every other
   surface (Targets card, History rows, HomeViewModel EXCESS flag,
   InsightsEngine over-cap counting) rendered/applied the raw number,
   reading as a contradiction next to the higher RDA.
2. The nutrient page had redundant reference cards ("Signs you're too low",
   "If you consistently overshoot") duplicating the Symptoms section's data
   without its confidence bars/editing, an always-visible "Add symptom" box
   hard-wired to the DEFICIENCY kind, and no curated-vs-manual distinction.

## Decision
- Single source of truth: `NutrientKeys.SUPPLEMENTAL_ONLY_UL` (setOf
  "magnesium"). Consumers: ScoreSnapshotter, HomeViewModel (EXCESS flag),
  InsightsEngine (over-cap day counting), TargetsCard (explicit
  "Supplement upper limit … pills and powders only … Food has no upper
  limit, which is why it can sit below the recommended daily amount"),
  HistoryScreen ("(supplements only)" suffix).
- BOTH reference cards removed from the nutrient page; EffectsCard deleted.
  The Symptoms section (confidence bars, ✕ remove, + add per subsection
  with correct DEFICIENCY/EXCESS kind) is the single home for this content.
- Manual adds live under each Symptoms subsection header via a + toggle
  (SubsectionAddHeader) expanding an inline AddAssociationRow.
- ✍️ prefix marks user-added links. Symptoms: source=="MANUAL". Foods: new
  per-nutrient `food_knowledge.manualLinksJson` (DB v9, additive migration,
  DEFAULT '[]'); written by addNutrientAssociation, cleared by
  removeNutrientAssociation, round-tripped by KnowledgePackCodec.

## Consequences
- Every surface now agrees with the score pipeline on supplemental-only ULs;
  no unqualified 350 mg magnesium warnings remain anywhere.
- New installs and migrated v8 installs behave identically (additive column).
- Removing a manual link removes its ✍️ marker; re-adding restores it.
