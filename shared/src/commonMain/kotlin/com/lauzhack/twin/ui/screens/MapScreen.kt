package com.lauzhack.twin.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.lauzhack.twin.resources.Res
import com.lauzhack.twin.resources.map_hint
import com.lauzhack.twin.resources.map_title
import com.lauzhack.twin.ui.AppState
import com.lauzhack.twin.ui.components.AppShell
import com.lauzhack.twin.ui.components.LifeMap
import com.lauzhack.twin.ui.components.PanelData
import com.lauzhack.twin.ui.components.SectionCard
import com.lauzhack.twin.ui.components.TopBarActions
import com.lauzhack.twin.ui.theme.TwinIcons
import org.jetbrains.compose.resources.stringResource

/** The map of lives as its own screen, reachable from the top bar at any moment of the game. */
@Composable
fun MapScreen(actions: TopBarActions, panel: PanelData?, state: AppState) {
    AppShell(actions, panel = panel, onBack = state::closeMap) {
        LifeMapSection(state)
    }
}

/** The map card: computes every possible life in the background the first time, then shows the tree. */
@Composable
fun LifeMapSection(state: AppState) {
    val tree = state.tree ?: return
    LaunchedEffect(tree.root) { state.prepareMap() }
    SectionCard(stringResource(Res.string.map_title), icon = TwinIcons.Tree) {
        Text(stringResource(Res.string.map_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LifeMap(
            tree = tree,
            complete = state.mapReady,
            playedKey = state.playedKey,
            monthsPerTurn = state.config.monthsPerTurn,
            eventById = state::eventById,
            onOpen = state::open,
            onChat = state::chatAt,
        )
    }
}
