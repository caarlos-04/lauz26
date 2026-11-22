package com.lauzhack.twin.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue

/**
 * Overrides the locale used by Compose resources so the user can switch ES/EN inside the app.
 * Pattern from the Compose Multiplatform resources docs; each platform sets its default Locale.
 */
expect object LocalAppLocale {
    val current: String @Composable get
    @Composable infix fun provides(value: String?): ProvidedValue<*>
}
