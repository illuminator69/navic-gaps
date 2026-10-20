package paige.navic.ui.components.common

import kotlin.test.Test
import kotlin.test.assertEquals

/** The alphabetical scrollbar's arithmetic (P5 round 2): where a finger is, which item, which letter. */
class AlphabeticalScrollerTest {

	// A, then B at item 4 (A's header and three artists before it), then D at item 9.
	private val headers = listOf("A" to 0, "B" to 4, "D" to 9)

	@Test
	fun theThumbsMiddleFollowsTheFinger() {
		// A 1000 px track, a 100 px thumb: its middle runs from 50 to 950.
		assertEquals(0f, scrollerFraction(50f, 100f, 1000f))
		assertEquals(0.5f, scrollerFraction(500f, 100f, 1000f))
		assertEquals(1f, scrollerFraction(950f, 100f, 1000f))
	}

	@Test
	fun aFingerPastTheEndsHoldsTheEnds() {
		assertEquals(0f, scrollerFraction(-40f, 100f, 1000f))
		assertEquals(1f, scrollerFraction(1400f, 100f, 1000f))
	}

	@Test
	fun aTrackNoTallerThanTheThumbDoesNotDivideByZero() {
		assertEquals(1f, scrollerFraction(80f, 100f, 100f))
	}

	@Test
	fun theEndsAreTheFirstAndLastItems() {
		assertEquals(0, scrollerTarget(0f, 20))
		assertEquals(19, scrollerTarget(1f, 20))
		assertEquals(10, scrollerTarget(0.5f, 21))
		assertEquals(0, scrollerTarget(0.7f, 1))
		assertEquals(0, scrollerTarget(0.7f, 0))
	}

	@Test
	fun theLetterIsTheGroupTheItemIsIn() {
		assertEquals("A", letterAt(headers, 0))
		assertEquals("A", letterAt(headers, 3))
		assertEquals("B", letterAt(headers, 4))
		assertEquals("B", letterAt(headers, 8))
		assertEquals("D", letterAt(headers, 30))
	}

	@Test
	fun anItemBeforeTheFirstHeaderReadsAsTheFirstLetter() {
		// The artists' list puts its count row before the first letter.
		assertEquals("A", letterAt(listOf("A" to 1, "B" to 5), 0))
		assertEquals("", letterAt(emptyList(), 3))
	}
}
