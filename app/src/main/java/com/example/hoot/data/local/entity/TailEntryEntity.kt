package com.example.hoot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A generic Tail log entry outside meals/pills: mapped "Water habit" entries
 * and N "Miscellaneous habits" entries (e.g. electrolytes). Stored as raw
 * text exactly as Tail logged it — resolvable/analytics-ready later.
 *
 *  - `kind = "water"`: [amount] holds the numeric value parsed from the text
 *    ("500 ml" → 500 "ml"; "1.5 l" → 1500 "ml"; "2" → 2 null-unit) when the
 *    text contains a parseable number, else null.
 *  - `kind = "misc"`: raw text rows only; [amount]/[unit] stay null.
 *
 * Dedup uses the same stable-key convention as meals/supplements
 * (`tail:<entryId>`, or `tail:<kind>:<habit>:<ts>` fallback), so re-syncs are
 * idempotent upserts.
 */
@Entity(tableName = "tail_entries", indices = [Index("day"), Index("kind")])
data class TailEntryEntity(
    @PrimaryKey val id: String,          // tail:<entryId> or fallback key
    val kind: String,                    // "water" | "misc"
    val habitName: String,               // Tail habit id/name it came from
    val timestamp: Long,                 // epoch millis
    val day: String,                     // "yyyy-MM-dd" local
    val text: String,                    // raw entry text, verbatim
    val amount: Double?,                 // parsed numeric amount (water)
    val unit: String?                    // "ml" when normalized; null when unitless/unknown
)
