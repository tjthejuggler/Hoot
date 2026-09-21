package com.example.hoot.ui.charts

import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Custom Compose-Canvas charts (docs/ARCHITECTURE.md §7) — Inuit's
 * `ui/charts/Charts.kt` approach: deterministic, theme-colored, no chart
 * dependency. Every chart is accessibility-labelled via [semantics].
 */

/** Score → semantic color (theme accent trio from ui/theme/Color.kt). */
fun scoreColor(score: Double, high: Color, mid: Color, low: Color): Color = when {
    score >= 75.0 -> high
    score >= 50.0 -> mid
    else -> low
}

/** Animated fraction 0→1 used by all charts (respects "value in [0,1]"). */
@Composable
private fun animateChart(target: Float): Float =
    animateFloatAsState(
        targetValue = target.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 600, easing = EaseOutCubic),
        label = "chart"
    ).value

/** Chart content rect inside a DrawScope (padding + gridlines). */
private fun DrawScope.gridRect(
    gridLineColor: Color,
    extraLeft: Dp = 0.dp,
    extraBottom: Dp = 0.dp
): Rect {
    val padH = 6.dp.toPx()
    val padV = 8.dp.toPx()
    val left = padH + extraLeft.toPx()
    val rect = Rect(
        left = left, top = padV,
        right = (size.width - padH).coerceAtLeast(left + 1f),
        bottom = (size.height - padV - extraBottom.toPx()).coerceAtLeast(padV + 1f)
    )
    for (i in 1..2) {
        val y = rect.top + rect.height * i / 3f
        drawLine(gridLineColor, Offset(rect.left, y), Offset(rect.right, y), 1.dp.toPx())
    }
    return rect
}

/**
 * Line chart with an optional dashed reference line (e.g. RDA) and optional
 * bar underlay (weekly totals). [points] are raw values; nulls create gaps.
 */
@Composable
fun LineChart(
    points: List<Double?>,
    modifier: Modifier = Modifier,
    referenceLine: Double? = null,
    referenceLabel: String = "",
    lineColor: Color,
    referenceColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    barValues: List<Double?>? = null,
    barColor: Color = lineColor.copy(alpha = 0.25f),
    yMaxOverride: Double? = null,
    axisLabels: Boolean = false,
    xLabels: List<String> = emptyList(),
    yLabelFormat: (Double) -> String = { "%.4g".format(it) },
    contentDescriptionText: String = "Line chart"
) {
    val anim = animateChart(1f)
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val nonNull = points.filterNotNull()
    val yMax = yMaxOverride
        ?: (maxOf(nonNull.maxOrNull() ?: 1.0, referenceLine ?: 0.0, 1e-9))
    val desc = buildString {
        append(contentDescriptionText)
        nonNull.takeIf { it.isNotEmpty() }?.let {
            append(". Values from %.4g to %.4g".format(it.min(), it.max()))
        }
        if (referenceLabel.isNotBlank()) append(". Reference: $referenceLabel")
    }
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(
        modifier = modifier.semantics { contentDescription = desc }
    ) {
        val chart = gridRect(
            gridColor,
            extraLeft = if (axisLabels) 34.dp else 0.dp,
            extraBottom = if (axisLabels) 16.dp else 0.dp
        )
        val yToPx = { v: Double ->
            chart.bottom - (v.coerceAtLeast(0.0) / yMax * chart.height).toFloat()
        }

        // Y-axis tick labels (0 / midpoint / max) — charts were previously
        // unlabeled, making intake-vs-target unreadable at a glance.
        if (axisLabels) {
            listOf(0.0, yMax / 2.0, yMax).forEach { v ->
                val measured = textMeasurer.measure(
                    AnnotatedString(yLabelFormat(v)), style = labelStyle
                )
                drawText(
                    measured, color = labelColor,
                    topLeft = Offset(
                        0f,
                        (yToPx(v) - measured.size.height / 2f).coerceIn(
                            0f, (size.height - measured.size.height).coerceAtLeast(0f)
                        )
                    )
                )
            }
        }

        // Optional bar underlay.
        barValues?.let { bars ->
            val n = bars.size.coerceAtLeast(1)
            val slot = chart.width / n
            val barW = slot * 0.55f
            bars.forEachIndexed { i, v ->
                val value = v ?: return@forEachIndexed
                val h = (value / yMax * chart.height * anim).toFloat()
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(chart.left + slot * i + (slot - barW) / 2f, chart.bottom - h),
                    size = Size(barW, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
                )
            }
        }

        // Reference line (dashed) + on-chart target label.
        referenceLine?.let { ref ->
            if (ref in 0.0..yMax) {
                val y = yToPx(ref)
                drawLine(
                    color = referenceColor,
                    start = Offset(chart.left, y),
                    end = Offset(chart.right, y),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                )
                if (referenceLabel.isNotBlank()) {
                    val measured = textMeasurer.measure(
                        AnnotatedString(referenceLabel), style = labelStyle
                    )
                    drawText(
                        measured, color = referenceColor,
                        topLeft = Offset(
                            (chart.right - measured.size.width).coerceAtLeast(0f),
                            (y - measured.size.height - 2.dp.toPx()).coerceAtLeast(0f)
                        )
                    )
                }
            }
        }

        // Data line + soft fill.
        val pts = points.mapIndexedNotNull { i, v ->
            v?.let {
                val x = if (points.size <= 1) chart.center.x
                else chart.left + chart.width * i / (points.size - 1).toFloat()
                Offset(x, yToPx(v))
            }
        }
        if (pts.size >= 2) {
            val path = Path().apply {
                moveTo(pts.first().x, pts.first().y)
                pts.drop(1).forEach { lineTo(it.x, it.y) }
            }
            val fill = Path().apply {
                addPath(path)
                lineTo(pts.last().x, chart.bottom)
                lineTo(pts.first().x, chart.bottom)
                close()
            }
            drawPath(fill, lineColor.copy(alpha = 0.12f))
            drawPath(path, lineColor, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            pts.forEach { p ->
                drawCircle(lineColor, radius = 3.dp.toPx(), center = p)
            }
        }

        // X-axis tick labels: ≤5 evenly spaced (ends included).
        if (axisLabels && xLabels.size == points.size && points.isNotEmpty()) {
            xTickIndices(points.size).forEach { i ->
                val x = if (points.size <= 1) chart.center.x
                else chart.left + chart.width * i / (points.size - 1).toFloat()
                val measured = textMeasurer.measure(AnnotatedString(xLabels[i]), style = labelStyle)
                drawText(
                    measured, color = labelColor,
                    topLeft = Offset(
                        (x - measured.size.width / 2f).coerceIn(
                            0f, (size.width - measured.size.width).coerceAtLeast(0f)
                        ),
                        chart.bottom + 4.dp.toPx()
                    )
                )
            }
        }
    }
}

/** Evenly spaced tick indices for [n] points (ends included, at most [maxTicks]). */
private fun xTickIndices(n: Int, maxTicks: Int = 5): List<Int> =
    if (n <= maxTicks) (0 until n).toList()
    else (0 until maxTicks)
        .map { (it.toDouble() * (n - 1) / (maxTicks - 1)).roundToInt() }
        .distinct()

/**
 * Vertical bar chart (weekly score aggregates etc.). Negative / null = gap.
 */
@Composable
fun BarChart(
    values: List<Double?>,
    modifier: Modifier = Modifier,
    barColor: Color,
    targetValue: Double? = null,
    labels: List<String> = emptyList(),
    valueRange: ClosedFloatingPointRange<Double> = 0.0..100.0,
    contentDescriptionText: String = "Bar chart"
) {
    val anim = animateChart(1f)
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-9)
    val nonNull = values.filterNotNull()
    val desc = buildString {
        append(contentDescriptionText)
        nonNull.takeIf { it.isNotEmpty() }?.let {
            append(". Values from %.4g to %.4g".format(it.min(), it.max()))
        }
    }
    Canvas(modifier = modifier.semantics { contentDescription = desc }) {
        val chart = gridRect(gridColor)
        val n = values.size.coerceAtLeast(1)
        val slot = chart.width / n
        val barW = (slot * 0.6f).coerceAtMost(28.dp.toPx())
        values.forEachIndexed { i, v ->
            val value = v ?: return@forEachIndexed
            val frac = ((value - valueRange.start) / span).coerceIn(0.0, 1.0)
            val h = (frac * chart.height * anim).toFloat()
            drawRoundRect(
                color = barColor,
                topLeft = Offset(chart.left + slot * i + (slot - barW) / 2f, chart.bottom - h),
                size = Size(barW, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx())
            )
        }
        targetValue?.let { t ->
            if (t >= valueRange.start && t <= valueRange.endInclusive) {
                val y = chart.bottom - ((t - valueRange.start) / span * chart.height).toFloat()
                drawLine(
                    color = barColor.copy(alpha = 0.6f),
                    start = Offset(chart.left, y),
                    end = Offset(chart.right, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                )
            }
        }
    }
}

/**
 * Progress ring (score ring): track + sweep arc; center content is provided
 * by the caller via Box overlay.
 */
@Composable
fun ProgressRing(
    progress: Double,                    // 0..1
    modifier: Modifier = Modifier,
    color: Color,
    trackColor: Color = color.copy(alpha = 0.15f),
    strokeWidth: Int = 12,               // dp
    contentDescriptionText: String = "Progress ring"
) {
    val anim = animateChart(progress.toFloat())
    val desc = "$contentDescriptionText: ${(progress * 100).toInt()} percent"
    Canvas(modifier = modifier.semantics { contentDescription = desc }) {
        val stroke = strokeWidth.dp.toPx()
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(
            color = trackColor,
            startAngle = 0f, sweepAngle = 360f, useCenter = false,
            topLeft = Offset(inset, inset), size = arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
        drawArc(
            color = color,
            startAngle = -90f, sweepAngle = 360f * anim, useCenter = false,
            topLeft = Offset(inset, inset), size = arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
    }
}

/** Tiny sparkline (7-day score) — line only, no axes. */
@Composable
fun Sparkline(
    values: List<Double>,
    modifier: Modifier = Modifier,
    color: Color,
    contentDescriptionText: String = "Trend sparkline"
) {
    val anim = animateChart(1f)
    val max = (values.maxOrNull() ?: 100.0).coerceAtLeast(1e-9)
    val desc = if (values.isEmpty()) contentDescriptionText
    else "$contentDescriptionText: ${values.size} points, latest %.0f".format(values.last())
    Canvas(modifier = modifier.semantics { contentDescription = desc }) {
        if (values.isEmpty()) return@Canvas
        val w = size.width
        val h = size.height
        val xOf = { i: Int ->
            if (values.size <= 1) w / 2f else w * i / (values.size - 1).toFloat()
        }
        val yOf = { v: Double -> h - (v / max * h * anim).toFloat() }
        val path = Path().apply {
            moveTo(xOf(0), yOf(values[0]))
            values.drop(1).forEachIndexed { i, v -> lineTo(xOf(i + 1), yOf(v)) }
        }
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(color, radius = 3.dp.toPx(), center = Offset(xOf(values.size - 1), yOf(values.last())))
    }
}

/**
 * Radar chart of %-of-target coverage with LABELED axes (overhaul feedback
 * #2): each spoke carries a short nutrient name + its % value, so the shape
 * is self-explanatory. [axes] order is preserved; values in 0..1 (clamped).
 */
@Composable
fun RadarChart(
    axes: List<Pair<String, Double>>,    // label, coverage 0..1
    modifier: Modifier = Modifier,
    color: Color,
    gridColor: Color = color.copy(alpha = 0.2f),
    contentDescriptionText: String = "Coverage radar"
) {
    val anim = animateChart(1f)
    val n = axes.size
    val avg = if (n == 0) 0.0 else axes.map { it.second }.average()
    val desc = "$contentDescriptionText: $n nutrients, average ${(avg * 100).toInt()} percent. " +
        axes.joinToString(", ") { "${it.first} ${(it.second * 100).toInt()} percent" }
    val textMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.labelSmall
    Canvas(modifier = modifier.semantics { contentDescription = desc }) {
        if (n < 3) return@Canvas
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = min(size.width, size.height) / 2f * 0.72f

        fun vertex(i: Int, frac: Float): Offset {
            val angle = Math.toRadians(-90.0 + 360.0 * i / n)
            return Offset(
                center.x + (radius * frac * kotlin.math.cos(angle)).toFloat(),
                center.y + (radius * frac * kotlin.math.sin(angle)).toFloat()
            )
        }

        // Grid rings (25/50/75/100 %).
        for (ring in listOf(0.25f, 0.5f, 0.75f, 1f)) {
            val poly = Path().apply {
                repeat(n) { i ->
                    val p = vertex(i, ring)
                    if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
                close()
            }
            drawPath(poly, gridColor, style = Stroke(1.dp.toPx()))
        }
        // Spokes.
        repeat(n) { i -> drawLine(gridColor, center, vertex(i, 1f), strokeWidth = 1.dp.toPx()) }

        // Data polygon.
        val data = Path().apply {
            repeat(n) { i ->
                val p = vertex(i, (axes[i].second.coerceIn(0.0, 1.0) * anim).toFloat())
                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }
            close()
        }
        drawPath(data, color.copy(alpha = 0.25f))
        drawPath(data, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        repeat(n) { i ->
            drawCircle(
                color, radius = 3.dp.toPx(),
                center = vertex(i, (axes[i].second.coerceIn(0.0, 1.0) * anim).toFloat())
            )
        }

        // Axis labels: short nutrient name + % value, anchored outside the ring.
        repeat(n) { i ->
            val angle = -Math.PI / 2 + 2 * Math.PI * i / n
            val cos = kotlin.math.cos(angle).toFloat()
            val sin = kotlin.math.sin(angle).toFloat()
            val anchor = Offset(
                center.x + (radius + 14.dp.toPx()) * cos,
                center.y + (radius + 14.dp.toPx()) * sin
            )
            val short = axes[i].first.take(9)
            val text = "$short ${(axes[i].second * 100).toInt()}%"
            val measured = textMeasurer.measure(
                text = androidx.compose.ui.text.AnnotatedString(text),
                style = labelStyle
            )
            val tw = measured.size.width.toFloat()
            val th = measured.size.height.toFloat()
            val topLeft = when {
                cos > 0.4f -> anchor                            // right side
                cos < -0.4f -> Offset(anchor.x - tw, anchor.y - th / 2f)  // left side
                else -> Offset(anchor.x - tw / 2f, anchor.y - th)          // top/bottom
            }
            drawText(measured, topLeft = topLeft, color = labelColor)
        }
    }
}

/** Progress-bar fraction helper for nutrient rows (0..1 of RDA). */
fun intakeFraction(intake: Double, target: Double): Float =
    if (target <= 0) 0f else (intake / target).toFloat().coerceIn(0f, 1f)

/** Over-color blend for >100 % limit-tracker (red-ward as intake → 1.5× cap). */
fun excessBlend(intake: Double, cap: Double, ok: Color, excess: Color): Color {
    if (cap <= 0 || intake <= cap) return ok
    val t = ((intake - cap) / (cap * 0.5)).coerceIn(0.0, 1.0)
    return lerp(ok, excess, t.toFloat())
}
