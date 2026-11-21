package ch.lauzhack.twin.ui.i18n

import androidx.compose.runtime.Composable
import ch.lauzhack.twin.model.Lang
import ch.lauzhack.twin.resources.Res
import ch.lauzhack.twin.resources.allStringResources
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
