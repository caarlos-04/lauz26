package com.lauzhack.twin.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf

// Compose resources on the web read navigator.languages; webApp/index.html overrides it with
// window.__customLocale (pattern from the Compose Multiplatform resources docs).
@JsFun("(locale) => { window.__customLocale = locale; }")
private external fun setCustomLocale(locale: String?)

actual object LocalAppLocale {
    private val LocalLocale = staticCompositionLocalOf { "" }

    actual val current: String
        @Composable get() = LocalLocale.current

    @Composable
    actual infix fun provides(value: String?): ProvidedValue<*> {
        setCustomLocale(value?.replace('_', '-'))
        return LocalLocale.provides(value ?: "")
    }
}
