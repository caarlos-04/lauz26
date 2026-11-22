package com.lauzhack.twin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lauzhack.twin.model.Attribution
import com.lauzhack.twin.resources.Res
import com.lauzhack.twin.resources.driver_other
import com.lauzhack.twin.resources.waterfall_end
import com.lauzhack.twin.resources.waterfall_start
import com.lauzhack.twin.ui.i18n.formatChf
import com.lauzhack.twin.ui.theme.LocalTwinColors
import com.lauzhack.twin.ui.theme.Space
import org.jetbrains.compose.resources.stringResource
import com.lauzhack.twin.ui.InspectRequest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * P8 waterfall (#16): start net worth + each attribution item = end net worth, as horizontal floating bars.
 * Shows the [maxItems] largest items and groups the rest; the numbers are the engine's, only summed here.
 */
@Composable
fun Waterfall(attribution: Attribution, label: @Composable (String) -> String, maxItems: Int = 7, modifier: Modifier = Modifier,
              explore: ((String?) -> InspectRequest)? = null) {   // a bar opens in the Explore panel: an item key, or null for the totals
    val sorted = attribution.items.sortedByDescending { abs(it.chf) }
    val shown = sorted.take(maxItems)
    val rest = sorted.drop(maxItems).sumOf { it.chf }
    val otherLabel = stringResource(Res.string.driver_other)
    val steps = shown.map { Triple(label(it.key), it.chf, it.key) } + (if (abs(rest) >= 0.5) listOf(Triple(otherLabel, rest, null)) else emptyList())

    // Running totals: each step floats from the previous total to the new one.
    val bars = buildList {
        add(Bar(stringResource(Res.string.waterfall_start), 0.0, attribution.startNetWorth, total = true))
        var acc = attribution.startNetWorth
        steps.forEach { (name, chf, key) ->
            add(Bar(name, acc, acc + chf, total = false, request = key?.let { k -> explore?.invoke(k) }))
            acc += chf
        }
        add(Bar(stringResource(Res.string.waterfall_end), 0.0, attribution.endNetWorth, total = true, request = explore?.invoke(null)))
    }
    val lo = min(0.0, bars.minOf { min(it.from, it.to) })
    val hi = max(0.0, bars.maxOf { max(it.from, it.to) })
    val span = (hi - lo).takeIf { it > 0 } ?: 1.0

    val twin = LocalTwinColors.current
    val totalColor = MaterialTheme.colorScheme.secondary
    BoxWithConstraints(modifier) {
        // On a phone the amounts need a wider column to fit in one line ("+1’065’397 CHF").
        val amountWeight = if (maxWidth < 440.dp) 0.36f else 0.2f
        val labelWeight = if (maxWidth < 440.dp) 0.34f else 0.38f
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            bars.forEach { bar ->
                val delta = bar.to - bar.from
                val color = when {
                    bar.total -> totalColor
                    delta >= 0 -> twin.positive
                    else -> twin.negative
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text(
                        bar.label, style = if (bar.total) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(labelWeight),
                    )
                    BoxWithConstraints(Modifier.weight(1f - labelWeight - amountWeight).height(18.dp)) {
                        val w = maxWidth
                        val left = ((min(bar.from, bar.to) - lo) / span).toFloat()
                        val size = (abs(delta) / span).toFloat().coerceAtLeast(0.004f)
                        // Zero line.
                        Box(Modifier.offset(x = w * ((0.0 - lo) / span).toFloat()).width(1.dp).height(18.dp).background(MaterialTheme.colorScheme.outline))
                        Box(Modifier.offset(x = w * left).width(w * size).height(18.dp).background(color, RoundedCornerShape(3.dp)))
                    }
                    Box(Modifier.weight(amountWeight), contentAlignment = Alignment.CenterEnd) {
                        Text(
                            formatChf(if (bar.total) bar.to else delta, signed = !bar.total),
                            style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End, maxLines = 1,
                            color = if (bar.total) Color.Unspecified else color, modifier = Modifier.explorable(bar.request),
                        )
                    }
                }
            }
        }
    }
}

private data class Bar(val label: String, val from: Double, val to: Double, val total: Boolean, val request: InspectRequest? = null)
