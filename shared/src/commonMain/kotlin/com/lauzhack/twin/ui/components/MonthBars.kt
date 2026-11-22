package com.lauzhack.twin.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lauzhack.twin.ui.i18n.formatChfShort
import com.lauzhack.twin.ui.theme.LocalTwinColors
import kotlin.math.max
import kotlin.math.min

/**
 * One bar per month of a figure (a balance, or what a flow moved that month): the small chart of the Explore panel.
 * Tapping a bar picks that month (again: back to the whole period). A hairline marks the first month a decision
 * counts, a dot the month something not chosen (a job loss) started. The scale is the largest and smallest value,
 * written at the left; bars below zero hang from the zero line.
 */
@Composable
fun MonthBars(
    values: List<Double>,
    firstMonth: Int,                 // the month of values[0]
    selected: Int?,
    decisionMonths: List<Int>,
    shockMonths: List<Int>,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (values.isEmpty()) return
    val twin = LocalTwinColors.current
    val scheme = MaterialTheme.colorScheme
    val up = scheme.onSurface.copy(alpha = 0.26f)
    val down = twin.negative.copy(alpha = 0.45f)
    val pickedUp = twin.chosen
    val pickedDown = twin.negative
    val zero = scheme.outline
    val mark = twin.chosen.copy(alpha = 0.55f)
    val shock = scheme.tertiary
    val measurer = rememberTextMeasurer()
    val axis = TextStyle(fontSize = 10.sp, color = scheme.onSurfaceVariant)
    val current by rememberUpdatedState(selected)
    val select by rememberUpdatedState(onSelect)
    val n = values.size

    Canvas(
        modifier.fillMaxWidth().height(84.dp).pointerHoverIcon(PointerIcon.Hand).pointerInput(n, firstMonth) {
            detectTapGestures { at ->
                val left = AXIS_DP.dp.toPx()
                if (at.x < left) return@detectTapGestures
                val i = ((at.x - left) / (size.width - left) * n).toInt().coerceIn(0, n - 1)
                val month = firstMonth + i
                select(if (month == current) null else month)
            }
        },
    ) {
        val hi = max(0.0, values.max())
        val lo = min(0.0, values.min())
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val left = AXIS_DP.dp.toPx()
        val top = 8.dp.toPx()
        val h = size.height - top - 4.dp.toPx()
        val w = size.width - left
        val slot = w / n
        val gap = if (slot > 4.dp.toPx()) 1.dp.toPx() else 0f
        fun y(v: Double) = (top + h * (1 - (v - lo) / span)).toFloat()
        val y0 = y(0.0)

        // Scale: the largest value and, when there are negative ones, the smallest.
        drawText(measurer, formatChfShort(hi), Offset(0f, 0f), axis)
        if (lo < 0) drawText(measurer, formatChfShort(lo), Offset(0f, size.height - 13.sp.toPx()), axis)

        values.forEachIndexed { i, v ->
            val picked = firstMonth + i == selected
            val yv = y(v)
            val barTop = min(yv, y0)
            val barH = max(kotlin.math.abs(yv - y0), if (v != 0.0) 1.dp.toPx() else 0f)
            val color = if (v < 0) (if (picked) pickedDown else down) else (if (picked) pickedUp else up)
            drawRoundRect(color, Offset(left + slot * i + gap / 2, barTop), Size(slot - gap, barH), CornerRadius(1.dp.toPx()))
        }
        drawLine(zero, Offset(left, y0), Offset(size.width, y0), strokeWidth = 1.dp.toPx())

        decisionMonths.forEach { m ->
            val x = left + slot * (m - firstMonth)
            drawLine(mark, Offset(x, top), Offset(x, size.height), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f)))
        }
        shockMonths.forEach { m ->
            drawCircle(shock, radius = 3.dp.toPx(), center = Offset(left + slot * (m - firstMonth + 0.5f), 4.dp.toPx()))
        }
    }
}

private const val AXIS_DP = 34
