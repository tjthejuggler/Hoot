package com.example.hoot.ui.charts

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/**
 * Time-browsing controls for charts (feedback: windows stopped at 90 days with
 * no way to scroll back). Two pieces:
 *
 *  - [chartPan] — a horizontal-drag modifier: swipe right → older data,
 *    swipe left → newer data (threshold-protected, inert when disabled).
 *  - [ChartTimeNavRow] — the ‹ / › affordance plus the small "from – to"
 *    label of the range currently on screen.
 */

/** Compact "Sep 3 – Oct 3" range label (years shown when they differ). */
fun shortRangeLabel(from: String, to: String): String = runCatching {
    fun fmt(d: LocalDate, withYear: Boolean): String {
        val mon = d.month.name.lowercase().replaceFirstChar(Char::uppercase).take(3)
        return if (withYear) "$mon ${d.dayOfMonth}, ${d.year}" else "$mon ${d.dayOfMonth}"
    }
    val f = LocalDate.parse(from)
    val t = LocalDate.parse(to)
    if (f.year != t.year) "${fmt(f, true)} – ${fmt(t, true)}"
    else "${fmt(f, false)} – ${fmt(t, false)}"
}.getOrElse { "$from – $to" }

/**
 * Swipe-to-pan gesture for a chart: an aggregated horizontal drag past
 * ~80 px triggers [onPan] exactly once per gesture (true = go BACK in time,
 * false = forward). No-op while [enabled] is false.
 */
fun Modifier.chartPan(
    enabled: Boolean,
    onPan: (older: Boolean) -> Unit
): Modifier = this.pointerInput(enabled) {
    if (!enabled) return@pointerInput
    var accum = 0f
    detectHorizontalDragGestures(
        onDragEnd = {
            if (accum > 80f) onPan(true) else if (accum < -80f) onPan(false)
            accum = 0f
        },
        onDragCancel = { accum = 0f }
    ) { _, dragAmount -> accum += dragAmount }
}

/**
 * ‹ range-label › navigation row shown under/above a pannable chart.
 * Buttons disable at the data edges; the label names the visible range.
 */
@Composable
fun ChartTimeNavRow(
    label: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Showing $label. Swipe the chart or use arrows to change the time range." },
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, enabled = canGoBack) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Show earlier data"
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        IconButton(onClick = onForward, enabled = canGoForward) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Show later data"
            )
        }
    }
}
