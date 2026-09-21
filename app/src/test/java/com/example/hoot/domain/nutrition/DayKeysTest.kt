package com.example.hoot.domain.nutrition

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/** Timezone-safe local day keys — the single day-key source across the app. */
class DayKeysTest {

    private val rome = ZoneId.of("Europe/Rome")
    private val utc = ZoneId.of("UTC")

    @Test fun `epoch maps to the local day not the UTC day`() {
        // 2026-09-19 01:30 local Rome (UTC+2) = 2026-09-18 23:30 UTC.
        val ts = java.time.LocalDateTime.of(2026, 9, 19, 1, 30)
            .atZone(rome).toInstant().toEpochMilli()
        assertEquals("2026-09-19", DayKeys.fromEpoch(ts, rome))
        // Sanity: UTC would say the 18th — proving zone matters.
        assertEquals("2026-09-18", DayKeys.fromEpoch(ts, utc))
    }

    @Test fun `late-evening entry stays on its local day`() {
        val ts = java.time.LocalDateTime.of(2026, 9, 19, 23, 59)
            .atZone(rome).toInstant().toEpochMilli()
        assertEquals("2026-09-19", DayKeys.fromEpoch(ts, rome))
    }

    @Test fun `todayKey uses the given zone`() {
        // Fixed zone: the key equals that zone's current date by definition.
        assertEquals(java.time.LocalDate.now(rome).toString(), DayKeys.todayKey(rome))
    }

    @Test fun `minus and plus days are inverse and parse-safe`() {
        assertEquals("2026-09-12", DayKeys.minusDays("2026-09-19", 7))
        assertEquals("2026-09-19", DayKeys.plusDays("2026-09-12", 7))
        // Month boundary.
        assertEquals("2026-08-31", DayKeys.minusDays("2026-09-01", 1))
        // Unparseable input passes through unchanged (never throws).
        assertEquals("junk", DayKeys.minusDays("junk", 3))
    }

    @Test fun `day boundaries match the sync layer convention (local midnight roll)`() {
        // Just before/after local midnight.
        val before = java.time.LocalDateTime.of(2026, 9, 19, 23, 59, 59)
            .atZone(rome).toInstant().toEpochMilli()
        val after = java.time.LocalDateTime.of(2026, 9, 20, 0, 0, 1)
            .atZone(rome).toInstant().toEpochMilli()
        assertEquals("2026-09-19", DayKeys.fromEpoch(before, rome))
        assertEquals("2026-09-20", DayKeys.fromEpoch(after, rome))
    }
}
