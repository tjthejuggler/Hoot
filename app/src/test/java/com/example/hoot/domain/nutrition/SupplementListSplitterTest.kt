package com.example.hoot.domain.nutrition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dose-safe multi-item splitting for pills entries (multi-item bug fix). */
class SupplementListSplitterTest {

    @Test fun `newline separated list`() {
        assertEquals(
            listOf("iron", "vitamin D", "fish oil"),
            SupplementListSplitter.split("iron\nvitamin D\nfish oil")
        )
    }

    @Test fun `comma separated list`() {
        assertEquals(
            listOf("iron", "vitamin C", "zinc"),
            SupplementListSplitter.split("iron, vitamin C, zinc")
        )
    }

    @Test fun `semicolon separated list`() {
        assertEquals(
            listOf("iron", "calcium", "magnesium"),
            SupplementListSplitter.split("iron; calcium; magnesium")
        )
    }

    @Test fun `and separated list`() {
        assertEquals(
            listOf("iron", "vitamin D"),
            SupplementListSplitter.split("iron and vitamin D")
        )
    }

    @Test fun `ampersand and plus separated list`() {
        assertEquals(
            listOf("zinc", "iron", "iodine"),
            SupplementListSplitter.split("zinc & iron + iodine")
        )
    }

    @Test fun `mixed separators`() {
        assertEquals(
            listOf("iron", "vitamin D", "zinc", "fish oil", "magnesium 400 mg"),
            SupplementListSplitter.split("iron, vitamin D\nzinc & fish oil; magnesium 400 mg")
        )
    }

    @Test fun `bulleted list`() {
        assertEquals(
            listOf("iron", "zinc", "calcium"),
            SupplementListSplitter.split("- iron\n* zinc\n• calcium")
        )
    }

    @Test fun `numbered list`() {
        assertEquals(
            listOf("iron", "zinc", "omega-3"),
            SupplementListSplitter.split("1. iron\n2) zinc\n3. omega-3")
        )
    }

    @Test fun `doses stay attached to their item`() {
        val items = SupplementListSplitter.split("Magnesium 400 mg, Vitamin D 2000 IU")
        assertEquals(listOf("Magnesium 400 mg", "Vitamin D 2000 IU"), items)
    }

    @Test fun `hyphenated names are not split`() {
        assertEquals(
            listOf("Vitamin B-12", "Omega-3"),
            SupplementListSplitter.split("Vitamin B-12, Omega-3")
        )
    }

    @Test fun `digit group commas are not split`() {
        val items = SupplementListSplitter.split("Vitamin D 1,000 IU, B12 2,500 mcg")
        assertEquals(listOf("Vitamin D 1,000 IU", "B12 2,500 mcg"), items)
    }

    @Test fun `decimal comma doses are not split`() {
        val items = SupplementListSplitter.split("Magnesium 1,5 mg")
        assertEquals(listOf("Magnesium 1,5 mg"), items)
    }

    @Test fun `single item yields single item`() {
        assertEquals(listOf("iron"), SupplementListSplitter.split("iron"))
        assertEquals(
            listOf("Magnesium 400 mg"),
            SupplementListSplitter.split("Magnesium 400 mg")
        )
    }

    @Test fun `blank text yields nothing`() {
        assertTrue(SupplementListSplitter.split("").isEmpty())
        assertTrue(SupplementListSplitter.split("   \n  ").isEmpty())
    }

    @Test fun `and inside a word does not split`() {
        assertEquals(
            listOf("Bandaid brand iron"),
            SupplementListSplitter.split("Bandaid brand iron")
        )
    }

    @Test fun `trailing punctuation is trimmed`() {
        assertEquals(
            listOf("iron", "zinc"),
            SupplementListSplitter.split("iron,\nzinc.")
        )
    }
}
