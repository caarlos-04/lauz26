package com.lauzhack.twin.ui.components

import com.lauzhack.twin.ui.GoalStage
import com.lauzhack.twin.ui.goalStatuses
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Icon
import com.lauzhack.twin.ui.theme.TwinIcons
import com.lauzhack.twin.resources.action_chat_now
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lauzhack.twin.model.NOTHING
import com.lauzhack.twin.ui.Catalog
import com.lauzhack.twin.ui.depthOf
import com.lauzhack.twin.ui.parentOf
import com.lauzhack.twin.engine.isOver
import com.lauzhack.twin.resources.map_next
import com.lauzhack.twin.resources.resolution_shock
import com.lauzhack.twin.model.GameSession
import com.lauzhack.twin.resources.Res
import com.lauzhack.twin.resources.map_explored
import com.lauzhack.twin.resources.map_go
import com.lauzhack.twin.resources.map_here
import com.lauzhack.twin.resources.map_legend_path
import com.lauzhack.twin.resources.map_legend_visited
import com.lauzhack.twin.resources.map_other
import com.lauzhack.twin.resources.map_played
import com.lauzhack.twin.resources.map_quiet
import com.lauzhack.twin.resources.map_start
import com.lauzhack.twin.resources.map_when
import com.lauzhack.twin.resources.map_year
import com.lauzhack.twin.resources.state_cash
import com.lauzhack.twin.resources.state_net_worth
import com.lauzhack.twin.ui.LifeTree
import com.lauzhack.twin.ui.NodeKey
import com.lauzhack.twin.ui.Figure
import com.lauzhack.twin.ui.InspectRequest
import com.lauzhack.twin.ui.Scope
import com.lauzhack.twin.ui.cash
import com.lauzhack.twin.ui.current
import com.lauzhack.twin.ui.i18n.formatChf
import com.lauzhack.twin.ui.i18n.formatChfShort
import com.lauzhack.twin.ui.i18n.formatPercent
import com.lauzhack.twin.ui.i18n.goalLabel
import com.lauzhack.twin.ui.i18n.localized
import com.lauzhack.twin.ui.netWorth
import com.lauzhack.twin.ui.progress
import com.lauzhack.twin.ui.theme.LocalTwinColors
import org.jetbrains.compose.resources.stringResource
import kotlin.math.hypot

/** Position of a node in the map: column = decisions taken ([depth]), row = [row] (leaves 0, 1, 2...; parents in between). */
data class MapPoint(val key: NodeKey, val depth: Int, val row: Float)

/**
 * Lays out the tree of [keys] (every key's prefixes must be in it): one column per turn, one row per leaf in key
 * order, each parent centred on its children. Pure, so it is tested without a UI.
 */
fun layoutLifeMap(keys: Set<NodeKey>): Map<NodeKey, MapPoint> {
    val children = keys.filter { it.isNotEmpty() }.groupBy { parentOf(it)!! }.mapValues { (_, v) -> v.sorted() }
    val out = mutableMapOf<NodeKey, MapPoint>()
    var nextLeaf = 0
    fun place(key: NodeKey): Float {
        val kids = children[key].orEmpty()
        val row = if (kids.isEmpty()) (nextLeaf++).toFloat() else kids.map { place(it) }.average().toFloat()
        out[key] = MapPoint(key, depthOf(key), row)
        return row
    }
    if ("" in keys) place("")
    return out
}

/**
 * Map of the lives the user decided: a tree that grows to the right, one column per decision. Every dot is a moment
 * the user lived (a node exists only because a decision was taken); the highlighted line is the life you are in.
 * Tapping a dot shows it below, with the way to go there, to decide something else at that moment or to go on.
 */
@Composable
fun LifeMap(
    tree: LifeTree,
    playedKey: NodeKey?,
    monthsPerTurn: Int,
    catalog: Catalog,
    onOpen: (NodeKey) -> Unit,
    onDecideAt: (NodeKey) -> Unit,
    onChat: ((NodeKey) -> Unit)? = null,
    explain: (@Composable (NodeKey) -> Unit)? = null,
) {
    var selected by remember(tree.root) { mutableStateOf(tree.cursor) }
    if (selected !in tree.nodes) selected = tree.cursor
    val points = remember(tree.nodes.keys) { layoutLifeMap(tree.nodes.keys) }
    val maxDepth = points.values.maxOf { it.depth }.coerceAtLeast(1)
    val rows = (points.values.maxOf { it.row } + 1).coerceAtLeast(1f)

    val twin = LocalTwinColors.current
    val pathColor = twin.chosen
    val lineColor = MaterialTheme.colorScheme.outline
    val faint = MaterialTheme.colorScheme.outlineVariant
    val visitedColor = MaterialTheme.colorScheme.secondary
    val surface = MaterialTheme.colorScheme.surface
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = textColor)

    val onPath: (NodeKey) -> Boolean = { it.isEmpty() || tree.cursor == it || tree.cursor.startsWith("$it/") }
    val yearLabels = (0..maxDepth).map { d -> if (d == 0) stringResource(Res.string.map_start) else stringResource(Res.string.map_year, d * monthsPerTurn / 12) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(Res.string.map_explored, tree.lives.size, tree.decisionCount), style = MaterialTheme.typography.titleSmall)
        Legend(pathColor, visitedColor)

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val labelWidth = 92.dp
            val minColumn = 44.dp
            val width = maxOf(maxWidth, minColumn * maxDepth + labelWidth + 24.dp)
            val rowHeight = 22.dp
            val header = 24.dp
            val height = header + rowHeight * rows + 8.dp
            Canvas(
                Modifier.horizontalScroll(rememberScrollState()).width(width).height(height)
                    .pointerInput(points, width) {
                        detectTapGestures { tap ->
                            val col = (size.width - labelWidth.toPx() - 16.dp.toPx()) / maxDepth
                            val hit = points.values.minByOrNull { p ->
                                hypot(tap.x - (12.dp.toPx() + p.depth * col), tap.y - (header.toPx() + (p.row + 0.5f) * rowHeight.toPx()))
                            }
                            if (hit != null) selected = hit.key
                        }
                    },
            ) {
                val col = (size.width - labelWidth.toPx() - 16.dp.toPx()) / maxDepth
                fun at(p: MapPoint) = Offset(12.dp.toPx() + p.depth * col, header.toPx() + (p.row + 0.5f) * rowHeight.toPx())

                // Year headers.
                yearLabels.forEachIndexed { d, label ->
                    val layout = measurer.measure(label, labelStyle)
                    val x = (12.dp.toPx() + d * col - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
                    drawText(layout, topLeft = Offset(x, 0f))
                }
                // Edges first, the current path last (on top).
                points.values.filter { it.key.isNotEmpty() }.sortedBy { onPath(it.key) }.forEach { p ->
                    val parent = points.getValue(parentOf(p.key)!!)
                    val from = at(parent)
                    val to = at(p)
                    val mid = Offset(from.x + (to.x - from.x) / 2, to.y)
                    val (color, width, dash) = when {
                        onPath(p.key) -> Triple(pathColor, 3.dp.toPx(), null)
                        else -> Triple(lineColor, 1.5.dp.toPx(), null as PathEffect?)
                    }
                    // Elbow: across half the column, then down to the child's row, then across.
                    drawLine(color, from, Offset(mid.x, from.y), width, StrokeCap.Round, dash)
                    drawLine(color, Offset(mid.x, from.y), mid, width, StrokeCap.Round, dash)
                    drawLine(color, mid, to, width, StrokeCap.Round, dash)
                }
                // Nodes.
                points.values.forEach { p ->
                    val c = at(p)
                    val r = 5.dp.toPx()
                    when {
                        onPath(p.key) -> drawCircle(pathColor, r, c)
                        tree.nodes[p.key]?.isOver == true -> drawCircle(visitedColor, r, c)
                        else -> {
                            drawCircle(surface, r, c)
                            drawCircle(visitedColor, r, c, style = Stroke(1.5.dp.toPx()))
                        }
                    }
                    if (p.key == tree.cursor) drawCircle(pathColor, r + 4.dp.toPx(), c, style = Stroke(2.dp.toPx()))
                    if (p.key == selected) drawCircle(textColor, r + 7.dp.toPx(), c, style = Stroke(1.dp.toPx()))
                }
                // Leaf labels: final net worth of the lives you lived, "?" for the others.
                points.values.filter { tree.nodes[it.key]?.isOver == true }.forEach { p ->
                    val s = tree.nodes.getValue(p.key)
                    val goals = s.goalStatuses()
                    val text = formatChfShort(s.current.netWorth) + if (goals.isEmpty()) "" else " · ${goals.count { it.stage == GoalStage.REACHED }}/${goals.size}"
                    val style = if (onPath(p.key)) labelStyle.copy(color = pathColor) else labelStyle
                    val layout = measurer.measure(text, style)
                    val c = at(p)
                    drawText(layout, topLeft = Offset(c.x + 16.dp.toPx(), c.y - layout.size.height / 2f))
                }
            }
        }

        val node = tree.nodes[selected]
        if (node != null) {
            NodeDetail(
                key = selected,
                session = node,
                isCursor = selected == tree.cursor,
                isPlayed = selected == playedKey,
                monthsPerTurn = monthsPerTurn,
                catalog = catalog,
                onOpen = { onOpen(selected) },
                onDecideAgain = parentOf(selected)?.let { parent -> { onDecideAt(parent) } },
                onDecideNext = if (node.isOver) null else ({ onDecideAt(selected) }),
                onChat = onChat?.takeIf { selected.isNotEmpty() }?.let { chat -> { chat(selected) } },
                explain = explain?.takeIf { selected.isNotEmpty() }?.let { e -> { e(selected) } },
            )
        }
    }
}

@Composable
private fun Legend(path: Color, visited: Color) {
    @Composable
    fun item(color: Color, hollow: Boolean, label: String) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Canvas(Modifier.size(10.dp)) {
                if (hollow) drawCircle(color, size.minDimension / 2, style = Stroke(1.5.dp.toPx()))
                else drawCircle(color, size.minDimension / 2)
            }
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        item(path, false, stringResource(Res.string.map_legend_path))
        item(visited, false, stringResource(Res.string.map_legend_visited))
    }
}

/** The selected moment: what was decided, and where the money and the goals stand. */
@Composable
private fun NodeDetail(
    key: NodeKey,
    session: GameSession,
    isCursor: Boolean,
    isPlayed: Boolean,
    monthsPerTurn: Int,
    catalog: Catalog,
    onOpen: () -> Unit,
    onDecideAgain: (() -> Unit)?,     // back to the moment before this one, to take another action there
    onDecideNext: (() -> Unit)?,      // the dilemma at this moment (null at the end of the game)
    onChat: (() -> Unit)?,
    explain: (@Composable () -> Unit)?,
) {
    val state = session.current
    SurfaceCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Overline(
                if (key.isEmpty()) stringResource(Res.string.map_start)
                else stringResource(Res.string.map_when, depthOf(key) * monthsPerTurn / 12, state.person.age),
            )
            if (isCursor) Pill(stringResource(Res.string.map_here), color = LocalTwinColors.current.chosen)
            if (isPlayed) Pill(stringResource(Res.string.map_played))
        }
        val turn = session.turns.lastOrNull()
        if (turn != null) {
            if (turn.actionId == NOTHING) {
                Text(stringResource(Res.string.map_quiet), style = MaterialTheme.typography.titleMedium)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(catalog.title(turn.actionId)?.let { localized(it) } ?: turn.actionId, style = MaterialTheme.typography.titleMedium,
                         modifier = Modifier.weight(1f, fill = false))
                    if (catalog.action(turn.actionId)?.uncertain?.isNotEmpty() == true) LuckBadge(turn.luck)
                }
            }
            catalog.shock(turn.shockId)?.let { shock ->
                Text(stringResource(Res.string.resolution_shock, localized(shock.title)), style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.tertiary)
            }
        }
        LabeledValue(stringResource(Res.string.state_net_worth), formatChf(state.netWorth), explore = InspectRequest(Figure.NetWorth, key, Scope.TURN))
        LabeledValue(stringResource(Res.string.state_cash), formatChf(state.cash), if (state.cash < 0) signColor(-1.0) else Color.Unspecified,
                     explore = InspectRequest(Figure.Cash, key, Scope.TURN))
        session.goalStatuses().forEach { GoalRow(it) }
        explain?.invoke()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!isCursor) {
                Button(onClick = onOpen, shape = MaterialTheme.shapes.small) { Text(stringResource(Res.string.map_go)) }
            }
            if (onDecideNext != null) {
                OutlinedButton(onClick = onDecideNext, shape = MaterialTheme.shapes.small) { Text(stringResource(Res.string.map_next)) }
            }
            if (onDecideAgain != null) {
                OutlinedButton(onClick = onDecideAgain, shape = MaterialTheme.shapes.small) { Text(stringResource(Res.string.map_other)) }
            }
            if (onChat != null) {
                OutlinedButton(onClick = onChat, shape = MaterialTheme.shapes.small) {
                    Icon(TwinIcons.Chat, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(Res.string.action_chat_now))
                }
            }
        }
    }
}
