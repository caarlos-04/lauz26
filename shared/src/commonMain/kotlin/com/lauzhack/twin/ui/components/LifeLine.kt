package com.lauzhack.twin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lauzhack.twin.model.BeatKind
import com.lauzhack.twin.ui.Catalog
import com.lauzhack.twin.resources.beat_shock
import com.lauzhack.twin.model.FactCatalog
import com.lauzhack.twin.model.FactKey
import com.lauzhack.twin.model.StoryBeat
import com.lauzhack.twin.resources.Res
import com.lauzhack.twin.resources.beat_cash_negative
import com.lauzhack.twin.resources.beat_cash_recovered
import com.lauzhack.twin.resources.beat_decision
import com.lauzhack.twin.resources.beat_end
import com.lauzhack.twin.resources.beat_goal_missed
import com.lauzhack.twin.resources.beat_goal_reached
import com.lauzhack.twin.resources.beat_low_point
import com.lauzhack.twin.resources.beat_milestone
import com.lauzhack.twin.resources.beat_start
import com.lauzhack.twin.resources.beat_when
import com.lauzhack.twin.resources.beat_work
import com.lauzhack.twin.resources.quiet_node
import com.lauzhack.twin.ui.i18n.formatChf
import com.lauzhack.twin.ui.i18n.goalLabel
import com.lauzhack.twin.ui.i18n.keyString
import com.lauzhack.twin.ui.i18n.localized
import com.lauzhack.twin.ui.theme.LocalTwinColors
import com.lauzhack.twin.ui.theme.Space
import org.jetbrains.compose.resources.stringResource

/**
 * P8 life line (#16): the engine's [StoryBeat]s as a vertical timeline. Structured data only: every text comes from
 * the beat, the event catalogue and the fact labels; nothing is computed here. OUTCOME is shown as the luck badge
 * of its decision.
 */
@Composable
fun LifeLine(beats: List<StoryBeat>, facts: FactCatalog, catalog: Catalog, modifier: Modifier = Modifier) {
    val lucks = beats.filter { it.kind == BeatKind.OUTCOME && it.data.size > 1 }      // luck + at least one drawn number
        .mapNotNull { b -> b.data["luck"]?.toDoubleOrNull()?.let { (b.eventId to b.month) to it } }
        .toMap()
    val rows = beats.filter { it.kind != BeatKind.OUTCOME }
    // Life changes of the same month (new job, degree, milestone...) are told in one row, so the line stays short.
    val texts = rows.map { beatText(it, facts, catalog) }
    val merged = mutableListOf<Pair<StoryBeat, String>>()
    rows.forEachIndexed { i, beat ->
        val text = texts[i] ?: return@forEachIndexed
        val prev = merged.lastOrNull()
        if (prev != null && beat.kind == BeatKind.LIFE_CHANGE && prev.first.kind == BeatKind.LIFE_CHANGE && prev.first.month == beat.month) {
            merged[merged.lastIndex] = prev.first to (prev.second + " · " + text)
        } else merged += beat to text
    }
    Column(modifier) {
        merged.forEachIndexed { i, (beat, text) ->
            BeatRow(
                beat = beat,
                text = text,
                luck = lucks[beat.eventId to beat.month].takeIf { beat.kind == BeatKind.DECISION },
                first = i == 0,
                last = i == merged.lastIndex,
            )
        }
    }
}

@Composable
private fun BeatRow(beat: StoryBeat, text: String, luck: Double?, first: Boolean, last: Boolean) {
    val line = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        // Rail: line above and below a dot coloured by the kind of moment.
        Box(Modifier.width(14.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.width(2.dp).height(10.dp).background(if (first) Color.Transparent else line))
                Box(Modifier.size(12.dp).background(beatColor(beat.kind), CircleShape))
                Box(Modifier.width(2.dp).weight(1f).background(if (last) Color.Transparent else line))
            }
        }
        Column(Modifier.weight(1f).padding(top = 4.dp, bottom = Space.m), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(Res.string.beat_when, beat.month, beat.age),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
                if (luck != null) LuckBadge(luck)
            }
        }
    }
}

@Composable
private fun beatColor(kind: BeatKind): Color {
    val twin = LocalTwinColors.current
    return when (kind) {
        BeatKind.CASH_NEGATIVE, BeatKind.LOW_POINT, BeatKind.GOAL_MISSED, BeatKind.SHOCK -> twin.negative
        BeatKind.CASH_RECOVERED, BeatKind.GOAL_REACHED -> twin.positive
        BeatKind.DECISION -> twin.chosen
        BeatKind.LIFE_CHANGE -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }
}

/** Text of a moment, or null if it is not shown. */
@Composable
private fun beatText(beat: StoryBeat, facts: FactCatalog, catalog: Catalog): String? {
    val d = beat.data
    return when (beat.kind) {
        BeatKind.START -> stringResource(Res.string.beat_start)
        BeatKind.END -> stringResource(Res.string.beat_end)
        BeatKind.QUIET_PERIOD -> stringResource(Res.string.quiet_node)
        BeatKind.DECISION -> stringResource(Res.string.beat_decision, catalog.title(beat.eventId)?.let { localized(it) } ?: return null)
        BeatKind.SHOCK -> stringResource(Res.string.beat_shock, catalog.title(beat.eventId)?.let { localized(it) } ?: return null)
        BeatKind.OUTCOME -> null
        BeatKind.LIFE_CHANGE -> when {
            d["flag"] != null -> stringResource(Res.string.beat_milestone, facts.flags[d["flag"]]?.let { localized(it) } ?: d["flag"]!!)
            d["fact"] != null -> {
                val key = FactKey.entries.firstOrNull { it.name == d["fact"] }
                key?.let { facts.facts[it]?.get(d["value"]) }?.let { localized(it) } ?: d["value"]
            }
            d["employment"] != null -> stringResource(Res.string.beat_work, keyString("employment_${d["employment"]}"))
            else -> null
        }
        BeatKind.CASH_NEGATIVE -> stringResource(Res.string.beat_cash_negative)
        BeatKind.CASH_RECOVERED -> stringResource(Res.string.beat_cash_recovered)
        BeatKind.LOW_POINT -> stringResource(Res.string.beat_low_point, d["cash"]?.toDoubleOrNull()?.let { formatChf(it) } ?: "")
        BeatKind.GOAL_REACHED -> stringResource(Res.string.beat_goal_reached, goalLabel(d["goal"].orEmpty()))
        BeatKind.GOAL_MISSED -> stringResource(Res.string.beat_goal_missed, goalLabel(d["goal"].orEmpty()))
    }
}
