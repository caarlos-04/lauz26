package ch.lauzhack.twin.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.lauzhack.twin.model.InterviewAnswer
import ch.lauzhack.twin.model.InterviewQuestion
import ch.lauzhack.twin.resources.Res
import ch.lauzhack.twin.resources.interview_progress
import ch.lauzhack.twin.resources.interview_title
import ch.lauzhack.twin.ui.components.AppShell
import ch.lauzhack.twin.ui.components.IconBadge
import ch.lauzhack.twin.ui.components.Overline
import ch.lauzhack.twin.ui.components.SurfaceCard
import ch.lauzhack.twin.ui.components.ThinProgress
import ch.lauzhack.twin.ui.components.TopBarActions
import ch.lauzhack.twin.ui.i18n.keyString
import ch.lauzhack.twin.ui.theme.Space
import ch.lauzhack.twin.ui.theme.TwinIcons
import org.jetbrains.compose.resources.stringResource

/** P1: multiple-choice questions shown one at a time. */
@Composable
fun InterviewScreen(
    actions: TopBarActions,
    questions: List<InterviewQuestion>,
    answers: List<InterviewAnswer>,
    onAnswer: (questionId: String, optionId: String) -> Unit,
    onBack: () -> Unit,
) {
    val index = answers.size.coerceAtMost(questions.lastIndex)
    val question = questions[index]
    AppShell(actions, onBack = onBack, maxContentWidth = 680.dp) {
        SurfaceCard(padding = Space.xxl) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                IconBadge(TwinIcons.User)
                Column {
                    Overline(stringResource(Res.string.interview_progress, index + 1, questions.size))
                    Text(stringResource(Res.string.interview_title), style = MaterialTheme.typography.titleMedium)
                }
            }
            ThinProgress((index + 1) / questions.size.toFloat())
            Text(keyString(question.textKey), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = Space.s))
            question.options.forEach { option ->
                OptionTile(keyString(option.labelKey)) { onAnswer(question.id, option.id) }
            }
        }
    }
}

@Composable
private fun OptionTile(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val accent = MaterialTheme.colorScheme.secondary
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().hoverable(interaction),
        interactionSource = interaction,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(if (hovered) 2.dp else 1.dp, if (hovered) accent else MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = if (hovered) accent.copy(alpha = 0.05f) else MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(horizontal = Space.l, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            Box(Modifier.size(18.dp).border(2.dp, if (hovered) accent else MaterialTheme.colorScheme.outline, CircleShape))
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (hovered) Icon(TwinIcons.Check, null, tint = accent, modifier = Modifier.size(18.dp))
        }
    }
}
