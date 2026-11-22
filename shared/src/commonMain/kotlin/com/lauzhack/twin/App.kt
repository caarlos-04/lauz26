package com.lauzhack.twin

import androidx.compose.runtime.Composable

// Root composable shared by Android, Desktop and Web (entry points in androidApp/, desktopApp/, webApp/).
@Composable
fun App() = com.lauzhack.twin.ui.App()
