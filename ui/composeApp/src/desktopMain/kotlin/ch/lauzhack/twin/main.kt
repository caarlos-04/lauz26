package ch.lauzhack.twin

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ch.lauzhack.twin.ui.App

// Desktop entry point: startup only, all logic lives in commonMain.
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Financial Twin",
        state = rememberWindowState(width = 1100.dp, height = 820.dp),
    ) {
        App()
    }
}
