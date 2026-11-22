package com.lauzhack.twin.ui.components

import com.lauzhack.twin.ui.GoalStatus
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.PaddingValues
import com.lauzhack.twin.ui.cash
import com.lauzhack.twin.ui.netWorth
import com.lauzhack.twin.ui.monthlyNet
import com.lauzhack.twin.ui.employment
import com.lauzhack.twin.ui.current
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lauzhack.twin.model.Employment
import com.lauzhack.twin.model.FinancialStatement
import com.lauzhack.twin.model.Goal
import com.lauzhack.twin.resources.Res
import com.lauzhack.twin.resources.action_back
import com.lauzhack.twin.resources.app_name
import com.lauzhack.twin.resources.goals_title
import com.lauzhack.twin.resources.lang_toggle
import com.lauzhack.twin.resources.nav_map
import com.lauzhack.twin.resources.nav_year
import com.lauzhack.twin.resources.profile_you
import com.lauzhack.twin.resources.state_age_value
import com.lauzhack.twin.resources.state_cash
import com.lauzhack.twin.resources.state_monthly_net
import com.lauzhack.twin.resources.state_net_worth
import com.lauzhack.twin.resources.theme_dark
import com.lauzhack.twin.resources.theme_light
import com.lauzhack.twin.resources.timeline_title
import com.lauzhack.twin.ui.i18n.formatChf
import com.lauzhack.twin.ui.i18n.formatPercent
import com.lauzhack.twin.ui.i18n.goalLabel
import com.lauzhack.twin.ui.i18n.keyString
import com.lauzhack.twin.ui.theme.LocalTwinColors
import com.lauzhack.twin.ui.theme.Space
import com.lauzhack.twin.ui.theme.TwinIcons
import org.jetbrains.compose.resources.stringResource

/** Top bar actions shared by every screen: language and theme toggles. */
data class TopBarActions(
    val isDark: Boolean,
    val onToggleLang: () -> Unit,
    val onToggleTheme: () -> Unit,
    val onMap: (() -> Unit)? = null,   // null = no game yet, or already on the map
)

/** Data for the side panel (live finances of the person during and after the game). */
data class PanelData(
    val name: String,
    val age: Int,
    val employment: Employment,
    val state: FinancialStatement,
    val goals: List<GoalStatus>,          // where each goal stands at this moment (engine rules)
    val turnsDone: Int,
    val maxTurns: Int,
    val monthsPerTurn: Int,
)

/**
 * App frame: top bar + content. On wide windows (>= 900dp) with [panel] it shows a left
 * side panel with the live finances; on narrow ones the panel becomes a compact KPI strip.
 */
@Composable
fun AppShell(
    actions: TopBarActions,
    panel: PanelData? = null,
    onBack: (() -> Unit)? = null,
    scrollable: Boolean = true,
    maxContentWidth: Dp = 1040.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) { TopBar(actions, panel, onBack, compact = maxWidth < 600.dp) }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 900.dp
                if (wide && panel != null) {
                    Row(Modifier.fillMaxSize()) {
                        ProfilePanel(panel, Modifier.width(300.dp).fillMaxHeight())
                        ContentArea(scrollable, maxContentWidth, null, content, Modifier.weight(1f))
                    }
                } else {
                    ContentArea(scrollable, maxContentWidth, panel, content, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun ContentArea(
    scrollable: Boolean,
    maxWidth: Dp,
    compactPanel: PanelData?,
    content: @Composable ColumnScope.() -> Unit,
    modifier: Modifier,
) {
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        val base = Modifier.widthIn(max = maxWidth).fillMaxWidth()
        Column(
            modifier = (if (scrollable) base.verticalScroll(rememberScrollState()) else base.fillMaxSize())
                .padding(horizontal = Space.xl, vertical = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            if (compactPanel != null) CompactPanel(compactPanel)
            content()
        }
    }
}

@Composable
private fun TopBar(actions: TopBarActions, panel: PanelData?, onBack: (() -> Unit)?, compact: Boolean) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(
                Modifier.fillMaxWidth().height(64.dp).padding(horizontal = Space.l),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(TwinIcons.Back, stringResource(Res.string.action_back), tint = MaterialTheme.colorScheme.onSurface)
                    }
                }
                LogoMark()
                if (!compact || panel == null) {
                    Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.weight(1f))
                if (panel != null) {
                    val year = panel.turnsDone * panel.monthsPerTurn / 12
                    val total = panel.maxTurns * panel.monthsPerTurn / 12
                    Pill(stringResource(Res.string.nav_year, year, total))
                }
                // On phones the actions are icons only, so the bar fits in one line.
                actions.onMap?.let { onMap ->
                    if (compact) {
                        IconButton(onClick = onMap) { Icon(TwinIcons.Tree, stringResource(Res.string.nav_map), tint = MaterialTheme.colorScheme.secondary) }
                    } else {
                        TextButton(onClick = onMap) {
                            Icon(TwinIcons.Tree, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(Res.string.nav_map))
                        }
                    }
                }
                TextButton(onClick = actions.onToggleLang, contentPadding = if (compact) PaddingValues(horizontal = 8.dp) else ButtonDefaults.TextButtonContentPadding) {
                    if (!compact) {
                        Icon(TwinIcons.Globe, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(stringResource(Res.string.lang_toggle), maxLines = 1)
                }
                IconButton(onClick = actions.onToggleTheme) {
                    Icon(
                        if (actions.isDark) TwinIcons.Sun else TwinIcons.Moon,
                        stringResource(if (actions.isDark) Res.string.theme_light else Res.string.theme_dark),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
fun LogoMark(size: Dp = 32.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(LocalTwinColors.current.brand)
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(size / 4)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(TwinIcons.Logo, null, tint = Color(0xFF34D399), modifier = Modifier.size(size * 0.62f))
    }
}

@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.secondary) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier.clip(CircleShape).background(color.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun panelName(panel: PanelData) = panel.name.ifBlank { stringResource(Res.string.profile_you) }

@Composable
private fun ProfilePanel(panel: PanelData, modifier: Modifier) {
    val state = panel.state
    Surface(modifier, color = MaterialTheme.colorScheme.surface) {
        Row {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Space.xl),
                verticalArrangement = Arrangement.spacedBy(Space.l),
            ) {
                // Identity
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    Avatar(panelName(panel))
                    Column {
                        Text(panelName(panel), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(Res.string.state_age_value, panel.age) + " · " + keyString("employment_${state.employment.name}"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Net worth hero + other KPIs
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Overline(stringResource(Res.string.state_net_worth))
                    AnimatedMoney(state.netWorth, MaterialTheme.typography.headlineMedium)
                }
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    LabeledValue(stringResource(Res.string.state_cash), formatChf(state.cash), if (state.cash < 0) signColor(-1.0) else Color.Unspecified)
                    LabeledValue(stringResource(Res.string.state_monthly_net), formatChf(state.monthlyNet, signed = true), signColor(state.monthlyNet))
                }

                // Goals: progress now, deadline and whether reached (measured as the engine does)
                if (panel.goals.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Overline(stringResource(Res.string.goals_title))
                    panel.goals.forEach { GoalMini(it) }
                }

                // Turn timeline
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Overline(stringResource(Res.string.timeline_title))
                TurnTimeline(panel.turnsDone, panel.maxTurns)
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
        }
    }
}

@Composable
private fun Avatar(name: String) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
    }
}

/** Dots for each turn: done (filled), current (ring), future (empty). */
@Composable
fun TurnTimeline(done: Int, total: Int) {
    val accent = MaterialTheme.colorScheme.secondary
    val track = MaterialTheme.colorScheme.outlineVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until total) {
            val fill = when {
                i < done -> accent
                else -> Color.Transparent
            }
            val ring = if (i <= done) accent else track
            Box(Modifier.size(14.dp).clip(CircleShape).background(fill).border(2.dp, ring, CircleShape))
            if (i < total - 1) Box(Modifier.width(22.dp).height(2.dp).background(if (i < done) accent else track))
        }
    }
}

/** Narrow screens: horizontal strip with the 3 key numbers. */
@Composable
private fun CompactPanel(panel: PanelData) {
    SurfaceCard(padding = Space.m) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
            MiniKpi(stringResource(Res.string.state_net_worth), formatChf(panel.state.netWorth))
            MiniKpi(stringResource(Res.string.state_cash), formatChf(panel.state.cash))
            MiniKpi(stringResource(Res.string.state_monthly_net), formatChf(panel.state.monthlyNet, signed = true))
        }
        if (panel.goals.isNotEmpty()) {
            val parts = panel.goals.map { goalLabelPlain(it) }
            Text(
                stringResource(Res.string.goals_title) + ": " + parts.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TurnTimeline(panel.turnsDone, panel.maxTurns)
    }
}

@Composable
private fun goalLabelPlain(s: GoalStatus): String = goalLabel(s.goal.name) + " " + formatPercent(s.progress)

@Composable
private fun MiniKpi(label: String, value: String) {
    Column {
        Overline(label)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
