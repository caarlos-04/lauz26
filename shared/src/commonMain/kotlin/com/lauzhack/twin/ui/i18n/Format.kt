package com.lauzhack.twin.ui.i18n

import kotlin.math.abs
import kotlin.math.roundToLong

// Number formatting in commonMain (no String.format available). Swiss style: 12'345 CHF.

fun formatChf(value: Double, signed: Boolean = false): String {
    val rounded = value.roundToLong()
    val digits = abs(rounded).toString().reversed().chunked(3).joinToString("’").reversed()
    val sign = when {
        rounded < 0 -> "−"
        signed && rounded > 0 -> "+"
        else -> ""
    }
    return "$sign$digits CHF"
}

/** Compact form for chart axes: 12k CHF, 1.2M CHF. */
fun formatChfShort(value: Double): String {
    val a = abs(value)
    val sign = if (value < 0) "−" else ""
    return when {
        a >= 1_000_000 -> "$sign${(a / 100_000).roundToLong() / 10.0}M"
        a >= 1_000 -> "$sign${(a / 1_000).roundToLong()}k"
        else -> "$sign${a.roundToLong()}"
    }
}

fun formatPercent(p: Double): String = "${(p * 100).roundToLong()} %"

/** An annual rate with decimals ("8.5 %", "1.17 %"): [formatPercent] rounds to whole percents. */
fun formatRate(p: Double, decimals: Int = 1): String {
    var scale = 1L
    repeat(decimals) { scale *= 10 }
    val units = (p * 100 * scale).roundToLong()
    val sign = if (units < 0) "−" else ""
    val whole = abs(units) / scale
    return if (decimals == 0) "$sign$whole %" else "$sign$whole.${(abs(units) % scale).toString().padStart(decimals, '0')} %"
}
