package com.example.hoot.domain.nutrition

import com.example.hoot.data.local.entity.TailEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Water ledger math ([WaterIntake.liters]) — the source that was missing from
 * the intake ledger (Home showed "0 L" while the user logged water daily).
 */
class WaterIntakeTest {

    private fun row(
        id: String,
        day: String = "2026-09-20",
        amount: Double?,
        unit: String?,
        kind: String = WaterIntake.KIND_WATER
    ) = TailEntryEntity(
        id = id, kind = kind, habitName = "Water",
        timestamp = 1_758_240_000_000, day = day,
        text = amount?.toString() ?: "drank water",
        amount = amount, unit = unit
    )

    @Test fun `milliliter rows sum to liters`() {
        val liters = WaterIntake.liters(
            listOf(
                row("a", amount = 500.0, unit = "ml"),
                row("b", amount = 250.0, unit = "ml")
            )
        )
        assertEquals(0.75, liters, 1e-9)
    }

    @Test fun `unitless rows count as 250 ml glasses in auto mode when small`() {
        val liters = WaterIntake.liters(
            listOf(
                row("a", amount = 2.0, unit = null),      // "2" → 2 glasses
                row("b", amount = 3.0, unit = null)       // "3 glasses"
            )
        )
        assertEquals(5 * 0.25, liters, 1e-9)
    }

    @Test fun `unitless rows read as ml in auto mode when large`() {
        // Tail logs raw ml: "2500" = 2.5 L (feedback 2026-09).
        val liters = WaterIntake.liters(
            listOf(row("a", amount = 2500.0, unit = null))
        )
        assertEquals(2.5, liters, 1e-9)
    }

    @Test fun `ml mode forces bare numbers to milliliters`() {
        val liters = WaterIntake.liters(
            listOf(row("a", amount = 2.0, unit = null)),
            unitMode = "ml"
        )
        assertEquals(0.002, liters, 1e-9)
    }

    @Test fun `liter mode scales bare numbers by 1000`() {
        val liters = WaterIntake.liters(
            listOf(row("a", amount = 2.5, unit = null)),
            unitMode = "l"
        )
        assertEquals(2.5, liters, 1e-9)
    }

    @Test fun `glass mode keeps the legacy 250 ml interpretation`() {
        val liters = WaterIntake.liters(
            listOf(row("a", amount = 2500.0, unit = null)),
            unitMode = "glass"
        )
        assertEquals(2500 * 0.25, liters, 1e-9)
    }

    @Test fun `oz rows convert via the ounce factor`() {
        val liters = WaterIntake.liters(
            listOf(row("a", amount = 16.0, unit = "oz"))
        )
        assertEquals(16 * 29.5735 / 1000.0, liters, 1e-9)
    }

    @Test fun `explicit text units win over the mode`() {
        val liters = WaterIntake.liters(
            listOf(
                row("a", amount = 1.5, unit = "l"),
                row("b", amount = 250.0, unit = "ml")
            ),
            unitMode = "glass"
        )
        assertEquals(1.75, liters, 1e-9)
    }

    @Test fun `rows without any number contribute nothing`() {
        val liters = WaterIntake.liters(
            listOf(row("a", amount = null, unit = null))  // "drank water"
        )
        assertEquals(0.0, liters, 1e-9)
    }

    @Test fun `mixed tail and quick-add rows combine`() {
        val liters = WaterIntake.liters(
            listOf(
                row("tail-1", amount = 1500.0, unit = "ml"),  // "1.5 l" Tail entry
                row("local-1", amount = 250.0, unit = "ml")   // in-app +250 ml
            )
        )
        assertEquals(1.75, liters, 1e-9)
    }

    @Test fun `non-water rows are ignored`() {
        val liters = WaterIntake.liters(
            listOf(
                row("m1", amount = 999.0, unit = "ml", kind = "misc")
            )
        )
        assertEquals(0.0, liters, 1e-9)
    }

    @Test fun `empty day is zero`() {
        assertEquals(0.0, WaterIntake.liters(emptyList()), 1e-9)
    }

    @Test fun `kind constant matches the sync + capture convention`() {
        assertEquals("water", WaterIntake.KIND_WATER)
        assertEquals("water", com.example.hoot.data.tail.KIND_WATER)
        assertEquals("water", com.example.hoot.data.intake.IntakeCaptureService.KIND_LOCAL_WATER)
    }
}
