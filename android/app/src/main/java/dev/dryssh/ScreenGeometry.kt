package dev.dryssh

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

internal data class ScreenRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
	val width get() = (right - left).coerceAtLeast(0)
	val height get() = (bottom - top).coerceAtLeast(0)
	val area get() = width.toLong() * height
}

internal enum class ScreenEdge { LEFT, TOP, RIGHT, BOTTOM }

internal data class ScreenCutout(val bounds: ScreenRect, val edge: ScreenEdge)

internal data class ScreenCorner(
	val x: Int,
	val y: Int,
	val radius: Int,
	val left: Boolean,
	val top: Boolean
)

internal data class ScreenEdges(
	val left: Int = 0,
	val top: Int = 0,
	val right: Int = 0,
	val bottom: Int = 0
)

internal data class ScreenGeometry(
	val cutouts: List<ScreenCutout> = emptyList(),
	val corners: List<ScreenCorner> = emptyList(),
	val edges: ScreenEdges = ScreenEdges()
) {
	fun contentBounds(width: Int, height: Int, imeBottom: Int = 0): ScreenRect = ScreenRect(
		edges.left.coerceIn(0, width),
		edges.top.coerceIn(0, height),
		(width - edges.right).coerceIn(edges.left.coerceAtMost(width), width),
		(height - maxOf(edges.bottom, imeBottom)).coerceIn(edges.top.coerceAtMost(height), height)
	)

	/** Safe horizontal intervals for an entire vertical band, in window pixels. */
	fun spans(bounds: ScreenRect): List<IntRange> {
		if (bounds.width == 0 || bounds.height == 0) return emptyList()
		var left = bounds.left
		var right = bounds.right
		for (corner in corners) {
			val distance = if (corner.top) corner.y - bounds.top else bounds.bottom - corner.y
			if (distance <= 0) continue
			val dy = distance.coerceAtMost(corner.radius).toDouble()
			val dx = sqrt((corner.radius.toDouble() * corner.radius - dy * dy).coerceAtLeast(0.0))
			if (corner.left) {
				left = maxOf(left, ceil(corner.x - dx).toInt())
			} else {
				right = minOf(right, floor(corner.x + dx).toInt())
			}
		}
		var spans = if (left < right) listOf(left until right) else emptyList()
		for ((cutout) in cutouts) {
			if (cutout.top >= bounds.bottom || cutout.bottom <= bounds.top) continue
			spans = spans.flatMap { span ->
				if (cutout.right <= span.first || cutout.left > span.last) {
					listOf(span)
				} else {
					listOf(
						span.first until minOf(span.last + 1, cutout.left),
						maxOf(span.first, cutout.right)..span.last
					)
						.filterNot { it.isEmpty() }
				}
			}
		}
		return spans
	}

	/** Largest safe pixel-aligned rectangle; ties prefer width, then top and left. */
	fun largestRect(bounds: ScreenRect): ScreenRect {
		if (bounds.area == 0L) return bounds
		val tops = sortedSetOf(bounds.top)
		val bottoms = sortedSetOf(bounds.bottom)
		for ((cutout) in cutouts) {
			if (cutout.right <= bounds.left || cutout.left >= bounds.right) continue
			if (cutout.bottom in bounds.top until bounds.bottom) tops.add(cutout.bottom)
			if (cutout.top in (bounds.top + 1)..bounds.bottom) bottoms.add(cutout.top)
		}
		for (corner in corners) {
			if (corner.top) {
				tops.addAll(maxOf(bounds.top, corner.y - corner.radius)..minOf(bounds.bottom - 1, corner.y))
			} else {
				bottoms.addAll(maxOf(bounds.top + 1, corner.y)..minOf(bounds.bottom, corner.y + corner.radius))
			}
		}
		var best = ScreenRect(bounds.left, bounds.top, bounds.left, bounds.top)
		for (top in tops) {
			for (bottom in bottoms) {
				if (top >= bottom) continue
				for (span in spans(ScreenRect(bounds.left, top, bounds.right, bottom))) {
					val rect = ScreenRect(span.first, top, span.last + 1, bottom)
					if (rect.area > best.area || rect.area == best.area && rect.width > best.width) best = rect
				}
			}
		}
		return best
	}

	fun rail(bounds: ScreenRect, width: Int, left: Boolean): ScreenRect {
		val depth = cutouts.filter { it.edge == if (left) ScreenEdge.LEFT else ScreenEdge.RIGHT }
			.maxOfOrNull { if (left) it.bounds.right - bounds.left else bounds.right - it.bounds.left } ?: 0
		val railWidth = maxOf(width, depth).coerceAtMost(bounds.width)
		return if (left) {
			bounds.copy(right = bounds.left + railWidth)
		} else {
			bounds.copy(
				left =
				bounds.right - railWidth
			)
		}
	}

	fun railSlots(bounds: ScreenRect, size: Int): List<ScreenRect> = buildList {
		var y = bounds.top
		while (y + size <= bounds.bottom) {
			val span = spans(bounds.copy(top = y, bottom = y + size)).firstOrNull {
				it.last - it.first + 1 >=
					size
			}
			if (span == null) {
				y++
			} else {
				val x = span.first + (span.last - span.first + 1 - size) / 2
				add(ScreenRect(x, y, x + size, y + size))
				y += size
			}
		}
	}
}
