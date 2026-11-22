package com.lauzhack.twin.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.lauzhack.twin.ai.of
import com.lauzhack.twin.model.LocalizedText
import com.lauzhack.twin.model.Lang
import com.lauzhack.twin.resources.Res
import com.lauzhack.twin.resources.allStringResources
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** Locale tag used by Compose resources for each app language. */
val Lang.tag: String get() = name.lowercase()

/**
 * Resolves a translation key coming from data (event titleKey, labelKey, goal names...).
 * Falls back to the key itself so missing translations are visible but never crash.
 */
@Composable
fun keyString(key: String): String {
    val res = Res.allStringResources[key] ?: return key
    return stringResource(res)
}

/** String key of a dataset goal name: "Emergency savings" -> "goal_emergency_savings" (tools/interview.py writes them). */
fun goalKey(name: String): String = "goal_" + name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

/** Text of a goal: dataset goal names ("Emergency savings") through goal_<slug>, otherwise the name as a key. */
@Composable
fun goalLabel(name: String): String = Res.allStringResources[goalKey(name)]?.let { stringResource(it) } ?: keyString(name)

/** Non-composable [keyString] for code outside the UI tree (the chat briefing). Uses the app locale. */
suspend fun keyStringAsync(key: String): String {
    val res = Res.allStringResources[key] ?: return key
    return getString(res)
}

/** Language selected in the app, for content texts written inline in both languages (events, facts). */
val LocalLang = staticCompositionLocalOf { Lang.EN }

/** Content text ([LocalizedText], e.g. event titles from content/events.json) in the app language. */
@Composable
fun localized(text: LocalizedText): String = text.of(LocalLang.current)
