package com.lauzhack.twin.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

actual object LocalAppLocale {
    private var default: Locale? = null
    private val LocalLocale = staticCompositionLocalOf { Locale.getDefault().toString() }

    actual val current: String
        @Composable get() = LocalLocale.current

    @Composable
    actual infix fun provides(value: String?): ProvidedValue<*> {
        if (default == null) default = Locale.getDefault()
        val new = if (value == null) default!! else Locale.forLanguageTag(value)
        Locale.setDefault(new)
        return LocalLocale.provides(new.toString())
    }
}
