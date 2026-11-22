package com.lauzhack.twin.ui.screens

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
import com.lauzhack.twin.model.InterviewAnswer
import com.lauzhack.twin.model.InputUnit
import com.lauzhack.twin.model.InterviewQuestion
import com.lauzhack.twin.model.NumberInput
import com.lauzhack.twin.resources.interview_continue
import com.lauzhack.twin.resources.interview_invalid
import com.lauzhack.twin.resources.interview_range
import com.lauzhack.twin.resources.interview_typical
import com.lauzhack.twin.ui.i18n.formatChf
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import org.jetbrains.compose.resources.stringResource
import com.lauzhack.twin.resources.Res
import com.lauzhack.twin.resources.interview_progress
import com.lauzhack.twin.resources.interview_title
import com.lauzhack.twin.ui.components.AppShell
import com.lauzhack.twin.ui.components.IconBadge
import com.lauzhack.twin.ui.components.Overline
import com.lauzhack.twin.ui.components.SurfaceCard
import com.lauzhack.twin.ui.components.ThinProgress
import com.lauzhack.twin.ui.components.TopBarActions
import com.lauzhack.twin.ui.i18n.keyString
import com.lauzhack.twin.ui.theme.Space
import com.lauzhack.twin.ui.theme.TwinIcons

/** P1: the interview, one question at a time: a choice, or a number typed within the range of the data. */
@Composable
fun InterviewScreen(
    actions: TopBarActions,
    questions: List<InterviewQuestion>,
    answers: List<InterviewAnswer>,
    onAnswer: (questionId: String, optionId: String) -> Unit,
    onBack: () -> Unit,
    onNumber: (questionId: String, value: Double) -> Unit = { _, _ -> },
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
            question.input?.let { spec ->
                val previous = answers.firstOrNull { it.questionId == question.id }?.value
                NumberField(question.id, spec, previous) { onNumber(question.id, it) }
            }
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

/** "12’500" or "34 years": how a bound or the typical value is shown. */
@Composable
private fun shown(x: Double, unit: InputUnit): String =
    if (unit == InputUnit.YEARS) "${x.toLong()} ${keyString("interview_unit_YEARS")}" else formatChf(x)

/** Digits only (apostrophes, spaces, commas and dots used as thousands separators are ignored); null if empty. */
internal fun parseAmount(text: String): Double? = text.filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.toDoubleOrNull()

/** A number between the bounds of the data, pre-filled with the typical value (or the previous answer). */
@Composable
private fun NumberField(questionId: String, spec: NumberInput, previous: Double?, onDone: (Double) -> Unit) {
    var text by remember(questionId) { mutableStateOf((previous ?: spec.typical).toLong().toString()) }
    val value = parseAmount(text)
    val valid = value != null && value >= spec.min && value <= spec.max
    val submit = { if (valid) onDone(value!!) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.filter { c -> c.isDigit() }.take(9) },
            singleLine = true,
            isError = !valid,
            suffix = { Text(keyString("interview_unit_${spec.unit.name}")) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            supportingText = {
                Text(
                    if (valid) stringResource(Res.string.interview_range, shown(spec.min, spec.unit), shown(spec.max, spec.unit)) + " · " +
                        stringResource(Res.string.interview_typical, shown(spec.typical, spec.unit))
                    else stringResource(Res.string.interview_invalid, shown(spec.min, spec.unit), shown(spec.max, spec.unit)),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = submit, enabled = valid, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text(stringResource(Res.string.interview_continue))
        }
    }
}
