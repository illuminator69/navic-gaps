package paige.navic.domain.models.settings

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Worbus settings N5: a choice that used to print its Kotlin name has a label of its own, and no two
 * choices share one.
 */
class SettingsLabelsTest {

	@Test
	fun everyCoverShapeHasItsOwnLabel() {
		assertEquals(CoverArtShape.entries.size, CoverArtShape.entries.map { it.label }.toSet().size)
	}

	@Test
	fun everyMarqueeSpeedHasItsOwnLabel() {
		assertEquals(MarqueeSpeed.entries.size, MarqueeSpeed.entries.map { it.label }.toSet().size)
	}

	@Test
	fun everyAppIconHasItsOwnLabel() {
		assertEquals(AppIconVariant.entries.size, AppIconVariant.entries.map { it.label }.toSet().size)
	}

	@Test
	fun animationStyleKeepsItsExistingResources() {
		assertEquals(AnimationStyle.entries.size, AnimationStyle.entries.map { it.displayName }.toSet().size)
	}
}
