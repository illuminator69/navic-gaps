package paige.navic.ui.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * P1 (the shell and Home), brief section 6: how a page's rows and tiles arrive. The first five rise
 * 30 ms apart so the page reads as a left-to-right sweep; everything after the fifth lands together,
 * because a stagger down a long list is a wait. These pin the numbers and the once-per-key rule; that
 * the modifier draws them is looked at on a device (Task 1 step 5, Task 9 steps 8-9).
 */
class ArrivalTest {

	@Test
	fun theFirstFiveAreThirtyMsApart() {
		assertEquals(listOf(0, 30, 60, 90, 120), (0..4).map { arrivalDelayMs(it) })
	}

	@Test
	fun everythingAfterTheFifthArrivesTogether() {
		assertEquals(150, arrivalDelayMs(5))
		assertEquals(150, arrivalDelayMs(6))
		assertEquals(150, arrivalDelayMs(40))
	}

	@Test
	fun aNegativeIndexIsTheFirst() {
		assertEquals(0, arrivalDelayMs(-1))
		assertEquals(0, arrivalDelayMs(Int.MIN_VALUE))
	}

	@Test
	fun aGateClaimsAKeyOnce() {
		val gate = ArrivalGate()
		assertTrue(gate.claim("a"))
		assertFalse(gate.claim("a"))
		assertTrue(gate.claim("b"))
	}

	@Test
	fun aKeyIsItsIdentityNotItsPosition() {
		// Two tiles that swap places are still the same two keys: neither arrives again.
		val gate = ArrivalGate()
		assertTrue(gate.claim("al-1"))
		assertTrue(gate.claim("al-2"))
		assertFalse(gate.claim("al-2"))
		assertFalse(gate.claim("al-1"))
	}

	@Test
	fun claimingTheRestMakesThemAppearAtOnceLater() {
		// A tile scrolled in after the page settled must not play its arrival: the shelf claims every
		// key it holds once the first frame is up, and only the ones composed before that animate.
		val gate = ArrivalGate()
		assertTrue(gate.claim("on-screen"))
		gate.claimAll(listOf("on-screen", "off-screen-1", "off-screen-2"))
		assertFalse(gate.claim("off-screen-1"))
		assertFalse(gate.claim("off-screen-2"))
		assertFalse(gate.claim("on-screen"))
	}

	@Test
	fun theKeysSurviveASavedStateRoundTrip() {
		// A pushed page covers Home and Back brings it back: the gate is saved with the entry, so the
		// tiles that already arrived do not arrive again.
		val gate = ArrivalGate()
		gate.claim("al-1")
		gate.claim(42)
		val restored = ArrivalGate.fromSaved(gate.toSaved())
		assertFalse(restored.claim("al-1"))
		assertFalse(restored.claim(42))
		assertTrue(restored.claim("al-2"))
	}

	@Test
	fun theTimingsAreTheBriefs() {
		assertEquals(8, ARRIVAL_RISE_DP)
		assertEquals(240, ARRIVAL_MS)
		assertEquals(280, COVER_FADE_MS)
	}
}
