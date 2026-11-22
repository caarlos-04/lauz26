package com.lauzhack.twin.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lauzhack.twin.ui.AppState
import com.lauzhack.twin.ui.Inspect
import com.lauzhack.twin.ui.components.ExplorePanel

/**
 * Where the Explore panel lives: over whatever screen it was opened from, so closing it leaves the user exactly where
 * they were. A sheet on the right on wide windows, from the bottom on phones; the dimmed rest of the screen closes it.
 */
@Composable
fun ExploreHost(state: AppState) {
    val inspect = state.inspecting
    // The last thing shown stays drawn while the panel slides out.
    var shown by remember { mutableStateOf<Inspect?>(null) }
    if (inspect != null) shown = inspect
    LaunchedEffect(inspect?.key) { inspect?.let { state.prepareMonths(it.key) } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 700.dp
        AnimatedVisibility(inspect != null, enter = fadeIn(tween(180)), exit = fadeOut(tween(160))) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = state::closeInspect),
            )
        }
        AnimatedVisibility(
            inspect != null,
            modifier = Modifier.align(if (wide) Alignment.CenterEnd else Alignment.BottomCenter),
            enter = if (wide) slideInHorizontally(tween(240)) { it } else slideInVertically(tween(240)) { it },
            exit = if (wide) slideOutHorizontally(tween(180)) { it } else slideOutVertically(tween(180)) { it },
        ) {
            val current = shown ?: return@AnimatedVisibility
            val session = state.tree?.nodes?.get(current.key) ?: return@AnimatedVisibility
            Surface(
                modifier = if (wide) Modifier.width(460.dp).fillMaxHeight() else Modifier.fillMaxWidth().fillMaxHeight(0.9f),
                color = MaterialTheme.colorScheme.surface,
                shape = if (wide) RoundedCornerShape(0.dp) else RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                shadowElevation = 12.dp,
            ) {
                ExplorePanel(
                    inspect = current,
                    months = state.monthsOf(current.key),
                    session = session,
                    catalog = state.catalog,
                    facts = state.facts,
                    assumptions = state.assumptions,
                    trends = state.trends,
                    onFigure = state::inspectFigure,
                    onScope = state::inspectScope,
                    onMonth = state::inspectMonth,
                    onClose = state::closeInspect,
                    modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                )
            }
        }
    }
}
