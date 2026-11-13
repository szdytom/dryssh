package dev.dryssh

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun ScreenDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
	Dialog(
		onDismissRequest = onDismissRequest,
		properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
	) {
		ImmersiveDialogWindow()
		Box(
			Modifier.fillMaxSize().pointerInput(onDismissRequest) {
				detectTapGestures { onDismissRequest() }
			}
		) {
			SafeWindowRegion {
				Box(
					Modifier.pointerInput(Unit) {
						detectTapGestures { }
					}.testTag("dialog-content")
				) { content() }
			}
		}
	}
}

@Composable
internal fun ScreenAlertDialog(
	onDismissRequest: () -> Unit,
	title: @Composable () -> Unit,
	text: @Composable () -> Unit,
	confirmButton: @Composable () -> Unit,
	dismissButton: (@Composable () -> Unit)? = null
) {
	ScreenDialog(onDismissRequest) {
		Surface(
			Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth(),
			shape = MaterialTheme.shapes.extraLarge,
			tonalElevation = 6.dp
		) {
			Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
				ProvideTextStyle(MaterialTheme.typography.headlineSmall, title)
				Box(Modifier.weight(1f, fill = false)) {
					ProvideTextStyle(MaterialTheme.typography.bodyMedium, text)
				}
				Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
					dismissButton?.invoke()
					confirmButton()
				}
			}
		}
	}
}
