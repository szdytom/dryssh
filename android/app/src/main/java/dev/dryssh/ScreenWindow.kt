package dev.dryssh

import android.graphics.Color
import android.os.Build
import android.view.RoundedCorner
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Suppress("DEPRECATION")
internal fun Window.useImmersiveScreen() {
	WindowCompat.setDecorFitsSystemWindows(this, false)
	attributes =
		attributes.apply {
			layoutInDisplayCutoutMode =
				WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
		}
	statusBarColor = Color.TRANSPARENT
	navigationBarColor = Color.TRANSPARENT
	hideSystemBars()
}

internal fun Window.hideSystemBars() {
	WindowCompat.getInsetsController(this, decorView).apply {
		systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
		hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
	}
}

@Composable
internal fun ImmersiveDialogWindow() {
	val view = LocalView.current
	val window = (view.parent as DialogWindowProvider).window
	DisposableEffect(window, view) {
		window.useImmersiveScreen()
		val observer = view.viewTreeObserver
		val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
			if (focused) window.hideSystemBars()
		}
		observer.addOnWindowFocusChangeListener(listener)
		onDispose { if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener) }
	}
}

private fun screenGeometry(view: View): ScreenGeometry {
	val insets = view.rootWindowInsets ?: return ScreenGeometry()
	val location = IntArray(2)
	view.getLocationInWindow(location)
	val x = location[0]
	val y = location[1]
	val cutout = insets.displayCutout
	val cutouts = if (cutout == null) {
		emptyList()
	} else {
		listOf(
			ScreenEdge.LEFT to cutout.boundingRectLeft,
			ScreenEdge.TOP to cutout.boundingRectTop,
			ScreenEdge.RIGHT to cutout.boundingRectRight,
			ScreenEdge.BOTTOM to cutout.boundingRectBottom
		).filterNot { it.second.isEmpty }.map { (edge, rect) ->
			ScreenCutout(ScreenRect(rect.left - x, rect.top - y, rect.right - x, rect.bottom - y), edge)
		}
	}
	val corners = if (Build.VERSION.SDK_INT >= 31) {
		listOf(
			RoundedCorner.POSITION_TOP_LEFT,
			RoundedCorner.POSITION_TOP_RIGHT,
			RoundedCorner.POSITION_BOTTOM_LEFT,
			RoundedCorner.POSITION_BOTTOM_RIGHT
		).mapNotNull { position ->
			insets.getRoundedCorner(position)?.let {
				ScreenCorner(
					it.center.x - x,
					it.center.y - y,
					it.radius,
					position == RoundedCorner.POSITION_TOP_LEFT || position == RoundedCorner.POSITION_BOTTOM_LEFT,
					position == RoundedCorner.POSITION_TOP_LEFT || position == RoundedCorner.POSITION_TOP_RIGHT
				)
			}
		}
	} else {
		emptyList()
	}
	val bars = insets.getInsets(WindowInsets.Type.systemBars())
	val waterfall = cutout?.waterfallInsets
	return ScreenGeometry(
		cutouts,
		corners,
		ScreenEdges(
			maxOf(bars.left, waterfall?.left ?: 0),
			maxOf(bars.top, waterfall?.top ?: 0),
			maxOf(bars.right, waterfall?.right ?: 0),
			maxOf(bars.bottom, waterfall?.bottom ?: 0)
		)
	)
}

@Composable
internal fun rememberScreenGeometry(): ScreenGeometry {
	val view = LocalView.current
	var geometry by remember(view) { mutableStateOf(screenGeometry(view)) }
	DisposableEffect(view) {
		// Observe without replacing Compose's insets listener or consuming its IME insets.
		val observer = view.viewTreeObserver
		val listener = ViewTreeObserver.OnPreDrawListener {
			geometry = screenGeometry(view)
			true
		}
		observer.addOnPreDrawListener(listener)
		onDispose { if (observer.isAlive) observer.removeOnPreDrawListener(listener) }
	}
	return geometry
}
