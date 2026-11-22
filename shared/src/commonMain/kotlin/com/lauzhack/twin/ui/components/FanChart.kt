package com.lauzhack.twin.ui.components

import com.lauzhack.twin.ui.current
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lauzhack.twin.engine.CashBand
import com.lauzhack.twin.resources.Res
import com.lauzhack.twin.resources.chart_assumptions
import com.lauzhack.twin.resources.chart_calibrated
import com.lauzhack.twin.resources.chart_tooltip_when
import com.lauzhack.twin.resources.legend_alternative
import com.lauzhack.twin.resources.legend_band
import com.lauzhack.twin.resources.legend_median
import com.lauzhack.twin.resources.year_axis
import com.lauzhack.twin.ui.i18n.formatChf
import com.lauzhack.twin.ui.i18n.formatChfShort
import com.lauzhack.twin.ui.theme.LocalTwinColors
import org.jetbrains.compose.resources.stringResource

/**
 * Noise fan chart (#12) drawn with Canvas (no chart library):
 * p10-p90 gradient band + median of the chosen path, optional dashed median of the alternative path,
 * a shaded zone for the months that rely only on assumptions (after [calibratedMonths]),
 * x axis in years, and a crosshair + tooltip on hover (desktop) / touch (Android).
 */
@Composable
fun FanChart(
    band: CashBand,
    alternativeMedian: List<Double>?,
    calibratedMonths: Int = 24,
    modifier: Modifier = Modifier,
) {
    val twin = LocalTwinColors.current
    val chosen = twin.chosen
    val alt = twin.alternative
    val scheme = MaterialTheme.colorScheme
    val grid = scheme.outlineVariant
    val zero = scheme.outline
    val shade = scheme.onSurface.copy(alpha = 0.035f)
    val ink = scheme.onSurface
    val muted = scheme.onSurfaceVariant
    val tooltipBg = scheme.inverseSurface
    val tooltipInk = scheme.inverseOnSurface
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 11.sp, color = muted)
    val zoneStyle = TextStyle(fontSize = 11.sp, color = muted, fontWeight = FontWeight.Medium)
    val calibratedLabel = stringResource(Res.string.chart_calibrated)
    val assumptionsLabel = stringResource(Res.string.chart_assumptions)
    val medianLabel = stringResource(Res.string.legend_median)
    val bandLabel = stringResource(Res.string.legend_band)
    val altLabel = stringResource(Res.string.legend_alternative)
    val years = (band.p50.size - 1) / 12
    val yearLabels = (0..years step 2).map { it to stringResource(Res.string.year_axis, it) }
    // Template with placeholders filled per hovered month (resources format args with toString()).
    val whenTemplate = stringResource(Res.string.chart_tooltip_when, "{Y}", "{M}")

    // Draw-in animation each time the data changes.
    val progress = remember { Animatable(0f) }
    LaunchedEffect(band) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    var hoverX by remember { mutableStateOf<Float?>(null) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(
            Modifier.fillMaxWidth().height(240.dp).pointerInput(band) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        hoverX = when (e.type) {
                            PointerEventType.Exit, PointerEventType.Release -> null
                            else -> e.changes.firstOrNull()?.position?.x
                        }
                    }
                }
            },
        ) {
            val n = band.p50.size
            if (n < 2) return@Canvas
            val all = band.p10 + band.p90 + (alternativeMedian ?: emptyList()) + 0.0
            var yMin = all.min()
            var yMax = all.max()
            if (yMax - yMin < 1.0) { yMax += 1.0; yMin -= 1.0 }
            val pad = (yMax - yMin) * 0.08
            yMin -= pad; yMax += pad

            val left = 48.dp.toPx()
            val top = 22.dp.toPx()
            val bottom = 22.dp.toPx()
            val w = size.width - left
            val h = size.height - bottom
            fun x(i: Int) = left + w * i / (n - 1).toFloat()
            fun y(v: Double) = (top + (h - top) * (1 - (v - yMin) / (yMax - yMin))).toFloat()

            // Assumption-only zone and zone labels.
            val xCal = x(calibratedMonths.coerceAtMost(n - 1))
            drawRect(shade, topLeft = Offset(xCal, 0f), size = Size(size.width - xCal, h))
            drawLine(grid, Offset(xCal, 0f), Offset(xCal, h), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
            drawText(measurer, calibratedLabel, Offset(left + 6f, 2f), zoneStyle)
            drawText(measurer, assumptionsLabel, Offset(xCal + 8f, 2f), zoneStyle)

            // Recessive horizontal grid (4 lines) + stronger zero line.
            for (k in 0..3) {
                val v = yMin + pad + (yMax - yMin - 2 * pad) * k / 3
                val yy = y(v)
                drawLine(grid, Offset(left, yy), Offset(size.width, yy), strokeWidth = 1f)
                drawText(measurer, formatChfShort(v), Offset(0f, yy - 8f), axisStyle)
            }
            if (yMin < 0 && yMax > 0) drawLine(zero, Offset(left, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 1.dp.toPx())

            // X axis labels in years.
            yearLabels.forEach { (yr, label) ->
                val m = yr * 12
                if (m < n) drawText(measurer, label, Offset(x(m) - 8f, h + 4f), axisStyle)
            }

            // Data, revealed left to right.
            clipRect(right = left + w * progress.value + 1f) {
                val bandPath = Path().apply {
                    moveTo(x(0), y(band.p90[0]))
                    for (i in 1 until n) lineTo(x(i), y(band.p90[i]))
                    for (i in n - 1 downTo 0) lineTo(x(i), y(band.p10[i]))
                    close()
                }
                drawPath(bandPath, Brush.verticalGradient(listOf(chosen.copy(alpha = 0.30f), chosen.copy(alpha = 0.08f)), startY = top, endY = h))

                fun line(values: List<Double>, color: Color, dashed: Boolean) {
                    val p = Path().apply {
                        moveTo(x(0), y(values[0]))
                        for (i in 1 until values.size) lineTo(x(i), y(values[i]))
                    }
                    drawPath(
                        p, color,
                        style = Stroke(
                            width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null,
                        ),
                    )
                }
                alternativeMedian?.let { line(it, alt, dashed = true) }
                line(band.p50, chosen, dashed = false)
            }

            // Hover: crosshair, markers and tooltip.
            val hx = hoverX
            if (hx != null && hx >= left && progress.value >= 1f) {
                val i = (((hx - left) / w) * (n - 1)).toInt().coerceIn(0, n - 1)
                val cx = x(i)
                drawLine(ink.copy(alpha = 0.35f), Offset(cx, top), Offset(cx, h), strokeWidth = 1f)
                val surface = tooltipInk
                fun marker(v: Double, color: Color) {
                    drawCircle(surface, radius = 6.dp.toPx(), center = Offset(cx, y(v)))
                    drawCircle(color, radius = 4.dp.toPx(), center = Offset(cx, y(v)))
                }
                alternativeMedian?.getOrNull(i)?.let { marker(it, alt) }
                marker(band.p50[i], chosen)

                val lines = buildList {
                    add(whenTemplate.replace("{Y}", "${i / 12}").replace("{M}", "$i"))
                    add("$medianLabel: ${formatChf(band.p50[i])}")
                    add("$bandLabel: ${formatChf(band.p10[i])} – ${formatChf(band.p90[i])}")
                    alternativeMedian?.getOrNull(i)?.let { add("$altLabel: ${formatChf(it)}") }
                }
                val layouts = lines.mapIndexed { k, s ->
                    measurer.measure(s, TextStyle(fontSize = 12.sp, color = tooltipInk, fontWeight = if (k == 0) FontWeight.SemiBold else FontWeight.Normal))
                }
                val boxW = layouts.maxOf { it.size.width } + 24f
                val boxH = layouts.sumOf { it.size.height } + 16f + 4f * (layouts.size - 1)
                val bx = if (cx + 12f + boxW < size.width) cx + 12f else cx - 12f - boxW
                val by = top
                drawRoundRect(tooltipBg, Offset(bx, by), Size(boxW, boxH), CornerRadius(10f, 10f))
                var ty = by + 8f
                layouts.forEach { l ->
                    drawText(l, topLeft = Offset(bx + 12f, ty))
                    ty += l.size.height + 4f
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LegendItem(chosen.copy(alpha = 0.3f), bandLabel, swatch = true)
            LegendItem(chosen, medianLabel)
            if (alternativeMedian != null) LegendItem(alt, altLabel, dashed = true)
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String, swatch: Boolean = false, dashed: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            swatch -> Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(color))
            dashed -> Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(2) { Box(Modifier.width(6.dp).height(2.dp).background(color)) }
            }
            else -> Box(Modifier.width(14.dp).height(2.dp).background(color))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
