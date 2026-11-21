package ch.lauzhack.twin.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.StartOffset
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import ch.lauzhack.twin.model.ChatMessage
import ch.lauzhack.twin.model.ChatRole
import ch.lauzhack.twin.resources.Res
import ch.lauzhack.twin.resources.chat_edited_notice
import ch.lauzhack.twin.resources.chat_future_self
import ch.lauzhack.twin.resources.chat_intro
import ch.lauzhack.twin.resources.chat_placeholder
import ch.lauzhack.twin.resources.chat_send
import ch.lauzhack.twin.resources.chat_suggestion_goals
import ch.lauzhack.twin.resources.chat_suggestion_other
import ch.lauzhack.twin.resources.chat_suggestion_risk
import ch.lauzhack.twin.resources.chat_title
import ch.lauzhack.twin.ui.components.AppShell
import ch.lauzhack.twin.ui.components.IconBadge
import ch.lauzhack.twin.ui.components.PanelData
import ch.lauzhack.twin.ui.components.SurfaceCard
import ch.lauzhack.twin.ui.components.TopBarActions
import ch.lauzhack.twin.ui.theme.Space
import ch.lauzhack.twin.ui.theme.TwinIcons
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** P10: chat with the future self. */
@Composable
fun ChatScreen(
    actions: TopBarActions,
    panel: PanelData?,
    history: List<ChatMessage>,
    loading: Boolean,
    isEditedPath: Boolean,
    onAsk: suspend (String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(history.size, loading) {
        val count = history.size + if (loading) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }
    fun send(text: String) {
        if (text.isBlank() || loading) return
        input = ""
        scope.launch { onAsk(text.trim()) }
    }

    AppShell(actions, panel = panel, onBack = onBack, scrollable = false, maxContentWidth = 820.dp) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            IconBadge(TwinIcons.Sparkle, size = 44.dp)
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.chat_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(Res.string.chat_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (isEditedPath) {
            Text(stringResource(Res.string.chat_edited_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }

        // Conversation
        SurfaceCard(Modifier.weight(1f), padding = Space.l) {
            val futureSelf = stringResource(Res.string.chat_future_self)
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                items(history) { Bubble(it, futureSelf) }
                if (loading) item { TypingIndicator() }
            }
        }

        // Suggestions + input
        val suggestions = listOf(
            stringResource(Res.string.chat_suggestion_risk),
            stringResource(Res.string.chat_suggestion_goals),
            stringResource(Res.string.chat_suggestion_other),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            suggestions.forEach { s ->
                SuggestionChip(
                    onClick = { send(s) },
                    label = { Text(s) },
                    shape = CircleShape,
                    colors = SuggestionChipDefaults.suggestionChipColors(containerColor = MaterialTheme.colorScheme.surface),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(Res.string.chat_placeholder)) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedBorderColor = MaterialTheme.colorScheme.secondary,
                ),
            )
            FilledIconButton(onClick = { send(input) }, enabled = !loading && input.isNotBlank(), modifier = Modifier.size(52.dp)) {
                Icon(TwinIcons.Send, stringResource(Res.string.chat_send))
            }
        }
    }
}

@Composable
private fun Bubble(message: ChatMessage, futureSelfName: String) {
    val mine = message.role == ChatRole.USER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Top) {
        if (!mine) {
            IconBadge(TwinIcons.Sparkle, size = 32.dp)
            Spacer(Modifier.size(Space.s))
        }
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            if (!mine) Text(futureSelfName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(
                Modifier.widthIn(max = 520.dp)
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp))
                    .background(if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Three bouncing dots while the future self is "typing". */
@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition()
    Row(
        Modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(3) { i ->
            val a by transition.animateFloat(
                initialValue = 0.25f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse, StartOffset(i * 160)),
            )
            Box(Modifier.size(7.dp).alpha(a).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant))
        }
    }
}
