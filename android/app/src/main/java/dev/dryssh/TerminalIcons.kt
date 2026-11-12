package dev.dryssh

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

object TerminalIcons {
	private fun icon(name: String, path: String) =
		ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
			pathData = PathParser().parsePathString(path).toNodes(),
			fill = null,
			stroke = SolidColor(Color.Black),
			strokeLineWidth = 1.8f,
			strokeLineCap = StrokeCap.Round,
			strokeLineJoin = StrokeJoin.Round
		).build()
	val Connect =
		icon("Connect", "M8 3V8 M16 3V8 M6 8H18V10C18 14 16 16 12 16C8 16 6 14 6 10Z M12 16V21")
	val Editor = icon("Editor", "M13 5H4V21H20V12 M10 14L11 10L19 2L22 5L14 13Z M17 4L20 7")
	val Keyboard =
		icon(
			"Keyboard",
			"M2 5H22V19H2Z M5 9H6 M9 9H10 M13 9H14 M17 9H18 M5 12H6 M9 12H10 M13 12H14 M17 12H18 M7 16H17"
		)
	val Actions = icon("Actions", "M13 2L4 14H11L10 22L20 9H13Z")
	val Settings = icon("Settings", "M4 6H20 M4 12H20 M4 18H20 M8 3V9 M16 9V15 M10 15V21")
	val More = icon("More", "M11 4H13V6H11Z M11 11H13V13H11Z M11 18H13V20H11Z")
	val Disconnect =
		icon(
			"Disconnect",
			"M8 3V8 M16 3V8 M6 8H18V10C18 14 16 16 12 16C8 16 6 14 6 10Z M12 16V21 M3 3L21 21"
		)
	val Awake =
		icon(
			"Awake",
			"M12 3V1 M12 23V21 M3 12H1 M23 12H21 M5 5L3 3 M21 21L19 19 M19 5L21 3 M3 21L5 19 M17 12A5 5 0 1 1 7 12A5 5 0 1 1 17 12"
		)
	val Live = icon("Live", "M12 3V17 M6 11L12 17L18 11 M4 21H20")
}
