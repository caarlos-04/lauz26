package com.lauzhack.twin.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.model.netWorthChf
import com.lauzhack.twin.resources.Res
import com.lauzhack.twin.resources.fig_debt
import com.lauzhack.twin.resources.fig_investments
import com.lauzhack.twin.resources.state_cash
import com.lauzhack.twin.resources.state_net_worth
import com.lauzhack.twin.resources.year_axis
import com.lauzhack.twin.ui.i18n.formatChf
import com.lauzhack.twin.ui.i18n.formatChfShort
import com.lauzhack.twin.ui.theme.LocalTwinColors
import org.jetbrains.compose.resources.stringResource
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The net worth of a life month by month and what it is made of: cash and investments stacked above zero, debt (and
 * negative cash) below, and the net worth as a line on top (= what is above minus what is below). Hairlines mark the
 * decisions. Hovering shows the month's figures; tapping a month opens it in the Explore panel ([onMonth]).
 *
 * [months]: index = month (GameEngine.months). Drawn with Canvas, like the fan chart: no chart library.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NetWorthChart(
    months: List<FinancialStatement>,
    decisionMonths: List<Int>,
    onMonth: ((Int) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val n = months.size
    if (n < 2) return
    val twin = LocalTwinColors.current
    val scheme = MaterialTheme.colorScheme
    val cashColor = twin.chosen
    val investColor = twin.alternative
    val debtColor = twin.negative
    val ink = scheme.onSurface
    val grid = scheme.outlineVariant
    val zero = scheme.outline
    val tooltipBg = scheme.inverseSurface
    val tooltipInk = scheme.inverseOnSurface
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 11.sp, color = scheme.onSurfaceVariant)
    val cashLabel = stringResource(Res.string.state_cash)
    val investLabel = stringResource(Res.string.fig_investments)
    val debtLabel = stringResource(Res.string.fig_debt)
    val netLabel = stringResource(Res.string.state_net_worth)
    val years = (n - 1) / 12
    val yearLabels = (0..years step 2).map { it to stringResource(Res.string.year_axis, it) }
    val whenLabels = months.map { monthLabel(it) }

    // What is drawn: above zero cash then investments; below zero negative cash then debt.
    val cashUp = months.map { max(it.cashChf, 0.0) }
    val top = months.mapIndexed { i, s -> cashUp[i] + s.investmentsChf }
    val cashDown = months.map { min(it.cashChf, 0.0) }
    val bottom = months.mapIndexed { i, s -> cashDown[i] - (s.loan?.principalChf ?: 0.0) }
    val net = months.map { it.netWorthChf() }
    val hasInvest = months.any { it.investmentsChf > 0.5 }
    val hasDebt = months.any { (it.loan?.principalChf ?: 0.0) > 0.5 }

    var hoverX by remember { mutableStateOf<Float?>(null) }
    val tap by rememberUpdatedState(onMonth)
    val left = 48.dp

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(
            Modifier.fillMaxWidth().height(240.dp)
                .then(if (onMonth != null) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
                .pointerInput(n) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            hoverX = when (e.type) {
                                PointerEventType.Exit -> null
                                else -> e.changes.firstOrNull()?.position?.x
                            }
                        }
                    }
                }
                .pointerInput(n) {
                    detectTapGestures { at ->
                        val l = left.toPx()
                        if (at.x >= l) tap?.invoke((((at.x - l) / (size.width - l - 18.dp.toPx())) * (n - 1) + 0.5f).toInt().coerceIn(0, n - 1))
                    }
                },
        ) {
            // The data with a little air, and round ticks (0, 200k, 400k...) only where they fall inside.
            val lowest = min(0.0, bottom.min())
            val highest = max(max(top.max(), net.max()), lowest + 1.0)
            val step = niceStep(highest - lowest)
            val yMin = if (lowest < 0) lowest - (highest - lowest) * 0.05 else 0.0
            val yMax = highest + (highest - lowest) * 0.06

            val l = left.toPx()
            val t = 10.dp.toPx()
            val h = size.height - 22.dp.toPx()
            val w = size.width - l - 18.dp.toPx()      // room for the last year label
            fun x(i: Int) = l + w * i / (n - 1).toFloat()
            fun y(v: Double) = (t + (h - t) * (1 - (v - yMin) / (yMax - yMin))).toFloat()

            // Recessive grid (4 lines), a stronger zero line and the years.
            var v = ceil(yMin / step) * step
            while (v <= yMax) {
                drawLine(grid, Offset(l, y(v)), Offset(l + w, y(v)), strokeWidth = 1f)
                drawText(measurer, formatChfShort(v), Offset(0f, y(v) - 8f), axisStyle)
                v += step
            }
            yearLabels.forEach { (yr, label) -> if (yr * 12 < n) drawText(measurer, label, Offset(x(yr * 12) - 8f, h + 4f), axisStyle) }

            fun area(upper: List<Double>, lower: List<Double>, color: Color) {
                val p = Path().apply {
                    moveTo(x(0), y(upper[0]))
                    for (i in 1 until n) lineTo(x(i), y(upper[i]))
                    for (i in n - 1 downTo 0) lineTo(x(i), y(lower[i]))
                    close()
                }
                drawPath(p, color)
            }
            val zeros = List(n) { 0.0 }
            area(cashUp, zeros, cashColor.copy(alpha = 0.22f))
            if (hasInvest) area(top, cashUp, investColor.copy(alpha = 0.34f))
            area(zeros, cashDown, cashColor.copy(alpha = 0.22f))
            if (hasDebt) area(cashDown, bottom, debtColor.copy(alpha = 0.34f))
            drawLine(zero, Offset(l, y(0.0)), Offset(l + w, y(0.0)), strokeWidth = 1.dp.toPx())

            decisionMonths.forEach { m ->
                if (m in 1 until n) drawLine(ink.copy(alpha = 0.28f), Offset(x(m), t), Offset(x(m), h), strokeWidth = 1.dp.toPx(),
                                             pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 5f)))
            }

            val line = Path().apply {
                moveTo(x(0), y(net[0]))
                for (i in 1 until n) lineTo(x(i), y(net[i]))
            }
            drawPath(line, ink, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

            // Hover: crosshair, marker and the month's figures.
            val hx = hoverX
            if (hx != null && hx >= l) {
                val i = (((hx - l) / w) * (n - 1) + 0.5f).toInt().coerceIn(0, n - 1)
                val cx = x(i)
                val s = months[i]
                drawLine(ink.copy(alpha = 0.35f), Offset(cx, t), Offset(cx, h), strokeWidth = 1f)
                drawCircle(tooltipInk, radius = 6.dp.toPx(), center = Offset(cx, y(net[i])))
                drawCircle(ink, radius = 4.dp.toPx(), center = Offset(cx, y(net[i])))
                val lines = buildList {
                    add(whenLabels[i])
                    add("$netLabel: ${formatChf(net[i])}")
                    add("$cashLabel: ${formatChf(s.cashChf)}")
                    if (hasInvest) add("$investLabel: ${formatChf(s.investmentsChf)}")
                    if (hasDebt) add("$debtLabel: ${formatChf(s.loan?.principalChf ?: 0.0)}")
                }
                val layouts = lines.mapIndexed { k, text ->
                    measurer.measure(text, TextStyle(fontSize = 12.sp, color = tooltipInk, fontWeight = if (k <= 1) FontWeight.SemiBold else FontWeight.Normal))
                }
                val boxW = layouts.maxOf { it.size.width } + 24f
                val boxH = layouts.sumOf { it.size.height } + 16f + 4f * (layouts.size - 1)
                val bx = if (cx + 12f + boxW < size.width) cx + 12f else cx - 12f - boxW
                drawRoundRect(tooltipBg, Offset(bx, t), Size(boxW, boxH), CornerRadius(10f, 10f))
                var ty = t + 8f
                layouts.forEach { layout ->
                    drawText(layout, topLeft = Offset(bx + 12f, ty))
                    ty += layout.size.height + 4f
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Legend(ink, netLabel, line = true)
            Legend(cashColor.copy(alpha = 0.35f), cashLabel)
            if (hasInvest) Legend(investColor.copy(alpha = 0.5f), investLabel)
            if (hasDebt) Legend(debtColor.copy(alpha = 0.5f), debtLabel)
        }
    }
}

/** A round step (1, 2 or 5 times a power of ten) that splits [range] into about four parts. */
internal fun niceStep(range: Double): Double {
    if (range <= 0) return 1.0
    val raw = range / 4
    val magnitude = 10.0.pow(floor(log10(raw)))
    val unit = raw / magnitude
    return magnitude * (if (unit <= 1.5) 1.0 else if (unit <= 3) 2.0 else if (unit <= 7) 5.0 else 10.0)
}

@Composable
private fun Legend(color: Color, label: String, line: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (line) Box(Modifier.width(14.dp).height(2.dp).background(color))
        else Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
