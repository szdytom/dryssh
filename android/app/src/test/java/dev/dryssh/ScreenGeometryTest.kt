package dev.dryssh

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenGeometryTest {
	@Test fun rectangularScreenHasNoArtificialInsets() {
		val bounds = ScreenRect(0, 0, 1080, 2400)
		assertEquals(bounds, ScreenGeometry().largestRect(bounds))
	}

	@Test fun keyboardAndNavigationOverlapInsteadOfAddingTheirHeights() {
		val geometry = ScreenGeometry(edges = ScreenEdges(3, 7, 5, 24))
		assertEquals(ScreenRect(3, 7, 1075, 1600), geometry.contentBounds(1080, 2400, 800))
		assertEquals(ScreenRect(3, 7, 1075, 2376), geometry.contentBounds(1080, 2400))
	}

	@Test fun railAbsorbsCutoutDepthAndItsSlotsAvoidTheHole() {
		val hole = ScreenRect(0, 120, 70, 190)
		val geometry = ScreenGeometry(listOf(ScreenCutout(hole, ScreenEdge.LEFT)))
		val bounds = ScreenRect(0, 0, 800, 400)
		val rail = geometry.rail(bounds, 52, true)
		assertEquals(70, rail.width)
		assertEquals(ScreenRect(70, 0, 800, 400), geometry.largestRect(bounds.copy(left = rail.right)))
		val slots = geometry.railSlots(rail, 48)
		assertTrue(slots.any { it.bottom <= hole.top })
		assertTrue(slots.any { it.top >= hole.bottom })
		assertTrue(slots.all { safe(it, geometry) })
	}

	@Test fun notchLeavesBothSidesAvailableToTheToolbar() {
		val geometry = ScreenGeometry(listOf(ScreenCutout(ScreenRect(140, 0, 220, 35), ScreenEdge.TOP)))
		assertEquals(listOf(0 until 140, 220 until 360), geometry.spans(ScreenRect(0, 0, 360, 48)))
		assertEquals(listOf(0 until 360), geometry.spans(ScreenRect(0, 48, 360, 96)))
	}

	@Test fun roundedLandscapeScreenTradesSmallVerticalInsetsForMoreColumns() {
		val geometry = ScreenGeometry(corners = corners(800, 400, listOf(40, 40, 40, 40)))
		val rect = geometry.largestRect(ScreenRect(52, 0, 800, 400))
		assertTrue(safe(rect, geometry))
		assertTrue(rect.width > 800 - 52 - 40)
		assertTrue(rect.top < 40 && rect.bottom > 360)
	}

	@Test fun randomizedRectanglesMatchAnExhaustiveIndependentOracle() {
		val random = Random(20261113)
		repeat(180) { iteration ->
			val width = random.nextInt(8, 20)
			val height = random.nextInt(8, 20)
			val holes = List(random.nextInt(0, 4)) {
				val left = random.nextInt(width)
				val top = random.nextInt(height)
				ScreenCutout(
					ScreenRect(
						left,
						top,
						random.nextInt(left + 1, width + 1),
						random.nextInt(
							top + 1,
							height + 1
						)
					),
					ScreenEdge.entries.random(random)
				)
			}
			val geometry = ScreenGeometry(
				holes,
				corners(
					width,
					height,
					List(4) {
						random.nextInt(
							0,
							minOf(width, height) / 2 + 1
						)
					}
				)
			)
			val bounds = ScreenRect(random.nextInt(0, 3), random.nextInt(0, 3), width, height)
			val actual = geometry.largestRect(bounds)
			var expected = bounds.copy(right = bounds.left, bottom = bounds.top)
			for (top in bounds.top until bounds.bottom) {
				for (left in bounds.left until bounds.right) {
					for (bottom in top + 1..bounds.bottom) {
						for (right in left + 1..bounds.right) {
							val candidate = ScreenRect(left, top, right, bottom)
							if (!safe(candidate, geometry)) continue
							if (candidate.area > expected.area ||
								candidate.area == expected.area && candidate.width > expected.width
							) {
								expected = candidate
							}
						}
					}
				}
			}
			assertEquals("case $iteration: $geometry", expected, actual)
		}
	}

	private fun corners(width: Int, height: Int, radii: List<Int>): List<ScreenCorner> =
		radii.mapIndexed {
				index,
				radius
			->
			val left = index % 2 == 0
			val top = index < 2
			ScreenCorner(
				if (left) radius else width - radius,
				if (top) radius else height - radius,
				radius,
				left,
				top
			)
		}

	private fun safe(rect: ScreenRect, geometry: ScreenGeometry): Boolean {
		if (geometry.cutouts.any { (hole) ->
				rect.left < hole.right && rect.right > hole.left &&
					rect.top < hole.bottom &&
					rect.bottom > hole.top
			}
		) {
			return false
		}
		return geometry.corners.all { corner ->
			val x = if (corner.left) rect.left else rect.right
			val y = if (corner.top) rect.top else rect.bottom
			val dx = if (corner.left) corner.x - x else x - corner.x
			val dy = if (corner.top) corner.y - y else y - corner.y
			dx <= 0 || dy <= 0 ||
				dx.toLong() * dx + dy.toLong() * dy <= corner.radius.toLong() * corner.radius
		}
	}
}
