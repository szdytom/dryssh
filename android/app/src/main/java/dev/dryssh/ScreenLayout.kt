package dev.dryssh

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

@Composable
internal fun SessionLayout(
	landscape: Boolean,
	header: @Composable (ScreenRect, ScreenGeometry) -> Unit,
	footer: @Composable (ScreenRect, ScreenGeometry) -> Unit,
	controls: @Composable (ScreenRect, ScreenGeometry) -> Unit,
	terminal: @Composable (Modifier) -> Unit,
	geometry: ScreenGeometry = rememberScreenGeometry(),
	imeBottom: Int = WindowInsets.ime.getBottom(LocalDensity.current)
) {
	val rectangles = remember(geometry) { ScreenRectCache(geometry) }
	SubcomposeLayout(Modifier.fillMaxSize().testTag("session-layout")) { constraints ->
		val width = constraints.maxWidth
		val height = constraints.maxHeight
		val bounds = geometry.contentBounds(width, height, imeBottom)
		val placements = mutableListOf<Pair<androidx.compose.ui.layout.Placeable, ScreenRect>>()
		val terminalBounds: ScreenRect
		if (landscape) {
			val railWidth = 52.dp.roundToPx()
			val (rail, viewport) = listOf(true, false).map { left ->
				val rail = geometry.rail(bounds, railWidth, left)
				val rest = if (left) bounds.copy(left = rail.right) else bounds.copy(right = rail.left)
				rail to rectangles.largest(rest)
			}.maxBy { it.second.area }
			terminalBounds = viewport
			val placeable = subcompose("controls") { controls(rail, geometry) }.single()
				.measure(Constraints.fixed(rail.width, rail.height))
			placements.add(placeable to rail)
		} else {
			val top = subcompose("header") { header(bounds, geometry) }.single()
				.measure(Constraints(0, bounds.width, 0, bounds.height))
			placements.add(top to bounds)
			val rest = bounds.copy(top = bounds.top + top.height)
			val bottom = subcompose("footer") { footer(rest, geometry) }.single()
				.measure(Constraints(0, rest.width, 0, rest.height))
			placements.add(bottom to rest.copy(top = rest.bottom - bottom.height))
			terminalBounds = rectangles.largest(rest.copy(bottom = rest.bottom - bottom.height))
		}
		val viewport = subcompose("terminal") { terminal(Modifier.fillMaxSize()) }.single()
			.measure(Constraints.fixed(terminalBounds.width, terminalBounds.height))
		layout(width, height) {
			placements.forEach { (child, rect) -> child.place(rect.left, rect.top) }
			viewport.place(terminalBounds.left, terminalBounds.top)
		}
	}
}

@Composable
internal fun SafeRail(
	bounds: ScreenRect,
	geometry: ScreenGeometry,
	content: @Composable () -> Unit
) {
	val size = with(LocalDensity.current) { 48.dp.roundToPx() }
	val slots = remember(bounds, geometry, size) { geometry.railSlots(bounds, size) }
	var offset by remember { mutableFloatStateOf(0f) }
	var limit by remember { mutableFloatStateOf(0f) }
	fun scroll(delta: Float): Float {
		val before = offset
		offset = (offset - delta).coerceIn(0f, limit)
		return before - offset
	}
	Layout(
		content,
		Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
			.semantics {
				verticalScrollAxisRange = ScrollAxisRange({ offset }, { limit })
				scrollBy { _, y ->
					scroll(-y)
					true
				}
			}.scrollable(rememberScrollableState(::scroll), Orientation.Vertical)
			.testTag("landscape-controls")
	) { measurables, constraints ->
		limit = ((measurables.size - slots.size).coerceAtLeast(0) * size).toFloat()
		val first = (offset.coerceAtMost(limit) / size).toInt()
		val children = measurables.map { it.measure(Constraints.fixed(size, size)) }
		layout(constraints.maxWidth, constraints.maxHeight) {
			slots.forEachIndexed { index, slot ->
				children.getOrNull(first + index)?.place(slot.left - bounds.left, slot.top - bounds.top)
			}
		}
	}
}

internal data class EdgeAction(
	val icon: ImageVector,
	val label: String,
	val active: Boolean = false,
	val click: () -> Unit
)

@Composable
internal fun SafeHeader(
	bounds: ScreenRect,
	geometry: ScreenGeometry,
	actions: List<EdgeAction>,
	status: @Composable () -> Unit
) {
	val size = with(LocalDensity.current) { 48.dp.roundToPx() }
	val (band, slots) = remember(bounds, geometry, size) {
		var band = bounds.copy(bottom = minOf(bounds.bottom, bounds.top + size))
		var slots = emptyList<ScreenRect>()
		while (band.bottom <= bounds.bottom) {
			slots = geometry.spans(band).flatMap { span ->
				(span.last + 1 - size downTo span.first step size).map { x ->
					ScreenRect(x, band.top, x + size, band.bottom)
				}
			}.sortedByDescending { it.left }
			if (slots.isNotEmpty() || band.bottom == bounds.bottom) break
			band = band.copy(top = band.top + 1, bottom = band.bottom + 1)
		}
		band to slots
	}
	val visible = if (slots.size >= actions.size) actions.size else (slots.size - 1).coerceAtLeast(0)
	val overflow = actions.drop(visible)
	var expanded by remember { androidx.compose.runtime.mutableStateOf(false) }
	val occupied = slots.take(visible + if (overflow.isNotEmpty()) 1 else 0).sortedBy { it.left }
	val statusSpan = geometry.copy(
		cutouts =
		geometry.cutouts + occupied.map { ScreenCutout(it, ScreenEdge.TOP) }
	)
		.spans(band).maxByOrNull { it.last - it.first }
	Layout(content = {
		status()
		actions.take(visible).forEach { action ->
			ToolIcon(action.icon, action.label, action.active, click = action.click)
		}
		if (overflow.isNotEmpty() && slots.isNotEmpty()) {
			androidx.compose.foundation.layout.Box {
				ToolIcon(TerminalIcons.More, "More actions") { expanded = true }
				DropdownMenu(expanded, { expanded = false }) {
					overflow.forEach { action ->
						DropdownMenuItem(text = { Text(action.label) }, onClick = {
							expanded = false
							action.click()
						})
					}
				}
			}
		}
	}, modifier = Modifier.testTag("portrait-controls")) { measurables, constraints ->
		val statusWidth = statusSpan?.let { it.last - it.first + 1 } ?: 0
		val statusChild = measurables.first().measure(Constraints(0, statusWidth, 0, band.height))
		val children = measurables.drop(1).map { it.measure(Constraints.fixed(size, band.height)) }
		layout(constraints.maxWidth, band.bottom - bounds.top) {
			if (statusWidth >
				0
			) {
				statusChild.place(
					statusSpan!!.first - bounds.left,
					band.top - bounds.top + (band.height - statusChild.height) / 2
				)
			}
			children.forEachIndexed { index, child ->
				val slot = occupied[index]
				child.place(slot.left - bounds.left, slot.top - bounds.top)
			}
		}
	}
}

@Composable
internal fun SafeFooter(
	bounds: ScreenRect,
	geometry: ScreenGeometry,
	rows: List<@Composable () -> Unit>
) {
	val size = with(LocalDensity.current) { 48.dp.roundToPx() }
	SubcomposeLayout { constraints ->
		val height = (rows.size * size).coerceAtMost(constraints.maxHeight)
		val children = rows.mapIndexed { index, row ->
			val bottom = bounds.bottom - (rows.lastIndex - index) * size
			val band = bounds.copy(
				top = maxOf(bounds.top, bottom - size),
				bottom = maxOf(bounds.top, bottom)
			)
			val span = geometry.spans(band).maxByOrNull { it.last - it.first }
			val x = span?.first ?: bounds.left
			val width = span?.let { it.last - it.first + 1 } ?: 0
			val child = subcompose(index, row).single().measure(Constraints.fixed(width, band.height))
			child to
				ScreenRect(
					x - bounds.left,
					band.top - (bounds.bottom - height),
					x - bounds.left + width,
					band.bottom
				)
		}
		layout(constraints.maxWidth, height) {
			children.forEach { (child, rect) -> child.place(rect.left, rect.top) }
		}
	}
}

@Composable
internal fun SafeWindowRegion(content: @Composable () -> Unit) {
	val geometry = rememberScreenGeometry()
	val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
	val rectangles = remember(geometry) { ScreenRectCache(geometry) }
	Layout(content, Modifier.fillMaxSize()) { measurables, constraints ->
		val bounds = geometry.contentBounds(constraints.maxWidth, constraints.maxHeight, imeBottom)
		val rect = rectangles.largest(bounds)
		val child = measurables.single().measure(Constraints(0, rect.width, 0, rect.height))
		layout(constraints.maxWidth, constraints.maxHeight) {
			child.place(
				rect.left + (rect.width - child.width) / 2,
				rect.top + (rect.height - child.height) / 2
			)
		}
	}
}

private class ScreenRectCache(private val geometry: ScreenGeometry) {
	private val rectangles = LinkedHashMap<ScreenRect, ScreenRect>()
	fun largest(bounds: ScreenRect): ScreenRect = rectangles.getOrPut(bounds) {
		if (rectangles.size == 8) rectangles.remove(rectangles.keys.first())
		geometry.largestRect(bounds)
	}
}
