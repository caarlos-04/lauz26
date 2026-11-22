package com.lauzhack.twin.ui.screens

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.BoxWithConstraints
import com.lauzhack.twin.resources.chat_suggestion_changed
import com.lauzhack.twin.resources.chat_moment_name
import com.lauzhack.twin.resources.chat_moment_intro
import com.lauzhack.twin.resources.chat_moment_after
import com.lauzhack.twin.resources.chat_moment_title
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
import com.lauzhack.twin.model.Persona
import com.lauzhack.twin.ui.ChatBubble
import androidx.compose.material3.FilterChip
import com.lauzhack.twin.model.ChatRole
import com.lauzhack.twin.resources.Res
import com.lauzhack.twin.resources.chat_ai_badge
import com.lauzhack.twin.resources.chat_edited_notice
import com.lauzhack.twin.resources.chat_offline_badge
import com.lauzhack.twin.resources.chat_offline_note
import com.lauzhack.twin.resources.chat_future_self
import com.lauzhack.twin.resources.chat_intro
import com.lauzhack.twin.resources.chat_placeholder
import com.lauzhack.twin.resources.chat_send
import com.lauzhack.twin.resources.chat_suggestion_goals
import com.lauzhack.twin.resources.chat_suggestion_other
import com.lauzhack.twin.resources.chat_suggestion_risk
import com.lauzhack.twin.resources.chat_title
import com.lauzhack.twin.ui.components.AppShell
import com.lauzhack.twin.ui.components.IconBadge
import com.lauzhack.twin.ui.components.PanelData
import com.lauzhack.twin.ui.components.Pill
import com.lauzhack.twin.ui.components.SurfaceCard
import com.lauzhack.twin.ui.components.TopBarActions
import com.lauzhack.twin.ui.theme.Space
import com.lauzhack.twin.ui.theme.TwinIcons
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Which self you talk to: you right after the last turn of the current node. */
data class ChatMoment(
    val age: Int,
    val year: Int,
    val after: String?,         // "Question → option" of the last turn, or the quiet-period label
    val complete: Boolean,      // a complete life: the self ten years on
)

/** P12: chat with your self of a moment (or of the end of a life). */
@Composable
fun ChatScreen(
    actions: TopBarActions,
    panel: PanelData?,
    history: List<ChatBubble>,
    loading: Boolean,
    isEditedPath: Boolean,
    aiModel: String?, // null = canned answers
    persona: Persona,
    onPersona: (Persona) -> Unit,
    onAsk: suspend (String) -> Unit,
    onBack: () -> Unit,
    moment: ChatMoment? = null,
) {
    val midLife = moment != null && !moment.complete
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

    // On a phone the conversation gets the screen: no money strip, a two-line header, one row of suggestions,
    // messages without the side icon. Wide windows keep the full layout.
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val compact = maxWidth < 600.dp
    val modelLabel = if (aiModel != null) stringResource(Res.string.chat_ai_badge, aiModel) else stringResource(Res.string.chat_offline_badge)
    AppShell(actions, panel = if (compact) null else panel, onBack = onBack, scrollable = false, maxContentWidth = 820.dp) {
        // Header
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (moment != null && midLife) stringResource(Res.string.chat_moment_title, moment.age, moment.year) else stringResource(Res.string.chat_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                moment?.after?.let { Text(stringResource(Res.string.chat_moment_after, it), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Text(modelLabel, style = MaterialTheme.typography.labelSmall, color = if (aiModel != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            IconBadge(TwinIcons.Sparkle, size = 44.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    if (moment != null && midLife) stringResource(Res.string.chat_moment_title, moment.age, moment.year) else stringResource(Res.string.chat_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                moment?.after?.let { Text(stringResource(Res.string.chat_moment_after, it), style = MaterialTheme.typography.bodyMedium) }
                Text(
                    stringResource(
                        if (midLife) Res.string.chat_moment_intro else Res.string.chat_intro,
                    ),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Pill(modelLabel, color = if (aiModel != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (isEditedPath) {
            Text(stringResource(Res.string.chat_edited_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }

        // Conversation
        val futureSelf = when {
            moment != null && midLife -> stringResource(Res.string.chat_moment_name, moment.age)
            else -> stringResource(Res.string.chat_future_self)
        }
        val conversation: @Composable ColumnScope.() -> Unit = {
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                items(history) { Bubble(it, futureSelf, compact) }
                if (loading) item { TypingIndicator() }
            }
        }
        if (compact) Column(Modifier.fillMaxWidth().weight(1f), content = conversation)
        else SurfaceCard(Modifier.weight(1f), padding = Space.l, content = conversation)

        // Suggestions + input
        val suggestions = listOf(
            stringResource(Res.string.chat_suggestion_risk),
            stringResource(Res.string.chat_suggestion_goals),
            stringResource(if (midLife) Res.string.chat_suggestion_changed else Res.string.chat_suggestion_other),
        )
        val chips: @Composable () -> Unit = {
            suggestions.forEach { s ->
                SuggestionChip(
                    onClick = { send(s) },
                    label = { Text(s) },
                    shape = CircleShape,
                    colors = SuggestionChipDefaults.suggestionChipColors(containerColor = MaterialTheme.colorScheme.surface),
                )
            }
        }
        // One scrollable row on a phone; they wrap on wide windows.
        if (compact) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s)) { chips() }
        else FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) { chips() }
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
            FilledIconButton(onClick = { send(input) }, enabled = !loading && input.isNotBlank(), modifier = Modifier.size(if (compact) 48.dp else 52.dp)) {
                Icon(TwinIcons.Send, stringResource(Res.string.chat_send))
            }
        }
    }
    }
}

@Composable
private fun Bubble(bubble: ChatBubble, futureSelfName: String, compact: Boolean = false) {
    val message = bubble.message
    val mine = message.role == ChatRole.USER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Top) {
        if (!mine && !compact) {
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
            if (bubble.offline) {
                Text(stringResource(Res.string.chat_offline_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
