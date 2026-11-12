package dev.dryssh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulatorFactory

class MainActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContent {
			val background = Color(0xFF101A25)
			val foreground = Color(0xFFD4DFE9)
			MaterialTheme(
				colorScheme = darkColorScheme(
					background = background,
					surface = Color(0xFF182433),
					primary = Color(0xFF8AC6DB)
				)
			) {
				val terminal = remember {
					TerminalEmulatorFactory.create(
						defaultForeground = foreground,
						defaultBackground = background
					).also {
						it.setDefaultColors(foreground.toArgb(), background.toArgb())
						val banner = "DrySSH ${NativeBridge.version()}\r\n\r\n" +
							"ASCII / 中文终端\r\n" +
							"\u001b[36mReady to connect.\u001b[0m\r\n"
						it.writeInput(banner.toByteArray())
					}
				}
				DisposableEffect(terminal) { onDispose { terminal.close() } }
				Surface(modifier = Modifier.fillMaxSize()) {
					Column(Modifier.safeDrawingPadding()) {
						Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
							Text(
								"DrySSH",
								modifier = Modifier.weight(1f),
								style = MaterialTheme.typography.titleMedium
							)
							Text(
								"Offline",
								color = MaterialTheme.colorScheme.primary,
								style = MaterialTheme.typography.labelMedium
							)
						}
						Terminal(
							terminalEmulator = terminal,
							modifier = Modifier.fillMaxWidth().weight(1f),
							backgroundColor = background,
							foregroundColor = foreground,
							initialFontSize = 14.sp
						)
					}
				}
			}
		}
	}
}
