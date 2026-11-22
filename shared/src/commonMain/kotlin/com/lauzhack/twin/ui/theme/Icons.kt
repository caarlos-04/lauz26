package com.lauzhack.twin.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Small stroke icons drawn for this app (24x24 grid, 1.8 stroke), so no icon library is needed.
 * Use with Material3 `Icon(TwinIcons.X, contentDescription, tint = ...)`.
 */
object TwinIcons {
    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach {
                addPath(
                    pathData = addPathNodes(it),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.8f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    private const val CIRCLE = "M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0z"

    val Logo = icon("logo", "M12 2.5L21.5 12L12 21.5L2.5 12z", "M7.5 13.5l3-3 2.5 2.5 3.5-4")
    val Wallet = icon("wallet", "M3 7h15a3 3 0 0 1 3 3v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z", "M3 7l12-4v4", "M16 14h2")
    val TrendUp = icon("trendUp", "M3 17l6-6 4 4 8-8", "M15 7h6v6")
    val TrendDown = icon("trendDown", "M3 7l6 6 4-4 8 8", "M15 17h6v-6")
    val Calendar = icon("calendar", "M4 6h16v14H4z", "M4 10h16", "M8 3v4", "M16 3v4")
    val Target = icon("target", CIRCLE, "M16 12a4 4 0 1 1-8 0a4 4 0 1 1 8 0z", "M12 12h0.01")
    val Chat = icon("chat", "M4 5h16v11H9l-5 4z", "M8 9.5h8", "M8 12.5h5")
    val Tree = icon("tree", "M12 3v6", "M12 9l-6 6", "M12 9l6 6", "M6 15v5", "M18 15v5")
    val Info = icon("info", CIRCLE, "M12 11v6", "M12 7.5v0.5")
    val Sun = icon("sun", "M16 12a4 4 0 1 1-8 0a4 4 0 1 1 8 0z", "M12 2v2", "M12 20v2", "M2 12h2", "M20 12h2",
        "M4.9 4.9l1.4 1.4", "M17.7 17.7l1.4 1.4", "M4.9 19.1l1.4-1.4", "M17.7 6.3l1.4-1.4")
    val Moon = icon("moon", "M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z")
    val Globe = icon("globe", CIRCLE, "M3 12h18", "M12 3a14 14 0 0 1 0 18", "M12 3a14 14 0 0 0 0 18")
    val Back = icon("back", "M15 5l-7 7 7 7")
    val Sparkle = icon("sparkle", "M12 3l2 6 6 2-6 2-2 6-2-6-6-2 6-2z")
    val Layers = icon("layers", "M12 3l9 5-9 5-9-5z", "M3 13l9 5 9-5")
    val Chart = icon("chart", "M4 20V4", "M4 20h16", "M7 15l4-4 3 3 5-6")
    val Send = icon("send", "M4 12l16-8-6 16-3-7z", "M11 13l9-9")
    val User = icon("user", "M16 8a4 4 0 1 1-8 0a4 4 0 1 1 8 0z", "M4 21a8 8 0 0 1 16 0")
    val Check = icon("check", "M5 12l5 5 9-10")
    val Shield = icon("shield", "M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z")
}
