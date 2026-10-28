package paige.navic.ui.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How light a cover reads ([coverTone]) and the page brightness it gives ([pageIsDark]). */
class CoverToneTest {

	private fun solid(rgb: Int, count: Int) = IntArray(count) { (0xFF shl 24) or rgb }
	private fun mix(vararg parts: Pair<Int, Int>) = parts.flatMap { (rgb, count) -> solid(rgb, count).toList() }.toIntArray()

	private val black = 0x0C0C0C
	private val yellow = 0xF0D228
	private val peach = 0xF8BE8C

	@Test
	fun theOwnersFiveCoversGetThePagesTheOwnerChose() {
		val light = mapOf("Vanille fraise" to OwnerCovers.vanilleFraise, "The Bends" to OwnerCovers.theBends, "Mitski" to OwnerCovers.mitski)
		val dark = mapOf("KID A MNESIA" to OwnerCovers.kidAMnesia, "Heavy Is the Crown" to OwnerCovers.heavyIsTheCrown)
		for ((name, cover) in light) assertFalse(pageIsDark(coverTone(cover)!!), "$name: tone ${coverTone(cover)}")
		for ((name, cover) in dark) assertTrue(pageIsDark(coverTone(cover)!!), "$name: tone ${coverTone(cover)}")
	}

	@Test
	fun aBlackSleeveWithASmallYellowLogoStaysDark() {
		assertTrue(pageIsDark(coverTone(mix(black to 90, yellow to 10))!!))
	}

	@Test
	fun aPeachFrameRoundABlackPhotoIsLight() {
		assertFalse(pageIsDark(coverTone(mix(black to 52, peach to 48))!!))
	}

	@Test
	fun aCoverWithNoColourReadsAsItsAverage() {
		// A grey's L* is its tone: the colour half has nothing to weigh but greys.
		assertEquals(53.6f, coverTone(solid(0x808080, 100))!!, 0.2f)
		assertTrue(pageIsDark(coverTone(mix(0xF5F5F5 to 30, black to 70))!!))
		assertFalse(pageIsDark(coverTone(mix(0xF5F5F5 to 85, black to 15))!!))
	}

	@Test
	fun transparentPixelsAreNotCounted() {
		assertNull(coverTone(IntArray(4)))
		val withHoles = solid(0x808080, 10) + IntArray(10)
		assertEquals(coverTone(solid(0x808080, 10))!!, coverTone(withHoles)!!, 1e-4f)
	}

	@Test
	fun withoutAToneThePageFollowsTheDominantAsBefore() {
		assertTrue(CoverSeeds(dominant = 0xFF404040.toInt(), vivid = null).pageIsDark())
		assertFalse(CoverSeeds(dominant = 0xFFE0E0E0.toInt(), vivid = null).pageIsDark())
		// A tone overrides the dominant: Mitski's black dominant on a cover that reads light.
		assertFalse(CoverSeeds(dominant = 0xFF202020.toInt(), vivid = null, tone = 60f).pageIsDark())
	}
}
