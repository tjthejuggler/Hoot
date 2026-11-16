package com.example.hoot.domain.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SymptomCanonicalizer: fold same-meaning keys ("dry_itchy_skin" →
 * "dry_skin"), never merge distinct symptoms, never cross kinds.
 */
class SymptomCanonicalizerTest {

    private fun info(
        key: String, nutrients: Int = 1, seed: Boolean = false,
        kind: String = "DEFICIENCY"
    ) = SymptomCanonicalizer.KeyInfo(key, key, kind, nutrients, seed)

    @Test fun `dry itchy skin folds into dry skin group`() {
        val plan = SymptomCanonicalizer.plan(
            listOf(
                info("dry_skin", nutrients = 3, seed = true),
                info("dry_itchy_skin", nutrients = 1)
            )
        )
        assertEquals(1, plan.size)
        assertEquals("dry_skin", plan.first().toKey)
        assertEquals("dry_itchy_skin", plan.first().fromKey)
    }

    @Test fun `loss of appetite folds into poor appetite`() {
        // Head token "appetite" is rare + shared → rule (c).
        val plan = SymptomCanonicalizer.plan(
            listOf(info("poor_appetite", nutrients = 2), info("loss_of_appetite"))
        )
        assertEquals("poor_appetite", plan.first().toKey)
    }

    @Test fun `impaired wound healing folds into slow healing`() {
        val plan = SymptomCanonicalizer.plan(
            listOf(info("slow_healing", nutrients = 3), info("impaired_wound_healing"))
        )
        assertTrue(plan.any { it.fromKey == "impaired_wound_healing" && it.toKey == "slow_healing" })
    }

    @Test fun `distinct symptoms never merge`() {
        val plan = SymptomCanonicalizer.plan(
            listOf(
                info("fatigue", nutrients = 10, seed = true),
                info("headache", nutrients = 4),
                info("hair_loss", nutrients = 6),
                info("muscle_cramps", nutrients = 3),
                info("night_blindness", nutrients = 1),
                info("easy_bruising", nutrients = 2)
            )
        )
        assertTrue(plan.isEmpty())
    }

    @Test fun `deficiency and excess never merge across kinds`() {
        val plan = SymptomCanonicalizer.plan(
            listOf(
                info("diarrhea", kind = "DEFICIENCY"),
                info("diarrhea_related", kind = "EXCESS")
            )
        )
        assertTrue(plan.isEmpty())
    }

    @Test fun `dominant key wins and absorbs several`() {
        val plan = SymptomCanonicalizer.plan(
            listOf(
                info("dry_skin", nutrients = 4, seed = true),
                info("dry_itchy_skin"),
                info("rough_dry_skin"),
                info("scaly_skin")
            )
        )
        // scaly_skin shares only "skin" (1 token < 2, head differs) → stays.
        assertEquals(setOf("dry_itchy_skin", "rough_dry_skin"), plan.map { it.fromKey }.toSet())
        assertTrue(plan.all { it.toKey == "dry_skin" })
    }

    @Test fun `plan is idempotent`() {
        val once = SymptomCanonicalizer.plan(
            listOf(info("dry_skin", 3), info("dry_itchy_skin"))
        )
        val applied = once.map { info(it.toKey, 4) }
        assertTrue(SymptomCanonicalizer.plan(applied).isEmpty())
    }

    @Test fun `shared head only merges when rare`() {
        // "pain" head appears 3× (bone, joint, muscle) → not rare → no merge.
        val plan = SymptomCanonicalizer.plan(
            listOf(
                info("bone_pain", 2), info("joint_pain", 2), info("muscle_pain", 2),
                info("joint_stiffness", 1)
            )
        )
        assertFalse(plan.any { it.toKey == "joint_pain" && it.fromKey == "joint_stiffness" })
        assertFalse(plan.any { it.fromKey == "bone_pain" })
    }
}
