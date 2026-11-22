package com.lauzhack.twin.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import com.lauzhack.twin.resources.Res
import com.lauzhack.twin.resources.explore_title
import com.lauzhack.twin.resources.trace_open
import com.lauzhack.twin.ui.InspectRequest
import com.lauzhack.twin.ui.theme.TwinIcons
import org.jetbrains.compose.resources.stringResource

/**
 * Opens the Explore panel on a figure ("where does this number come from?"). Provided by the app while a game is on;
 * null otherwise, and then figures are plain text.
 */
val LocalInspect = staticCompositionLocalOf<((InspectRequest) -> Unit)?> { null }

/**
 * Marks a figure as explorable: a dotted underline (the only visual cue, so the screens stay as clean as before) and a
 * click that opens the Explore panel on it. No-op when [request] is null or no game is on.
 */
@Composable
fun Modifier.explorable(request: InspectRequest?): Modifier {
    val open = LocalInspect.current
    if (request == null || open == null) return this
    val line = MaterialTheme.colorScheme.outline
    return this
        .clip(RoundedCornerShape(3.dp))
        .pointerHoverIcon(PointerIcon.Hand)
        .clickable(onClickLabel = stringResource(Res.string.explore_title)) { open(request) }
        .drawBehind {
            val y = size.height - 1.dp.toPx()
            drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(),
                     pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())))
        }
}

/** The explicit way in, for who does not notice the underlines: "Where do these numbers come from?". */
@Composable
fun ExploreLink(request: InspectRequest) {
    val open = LocalInspect.current ?: return
    TextButton(onClick = { open(request) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
        Icon(TwinIcons.Info, null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(Res.string.trace_open))
    }
}
