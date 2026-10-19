package paige.navic.ui.screens.nowPlaying.staged

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The staged queue's resting places (P2, `QueueDetents.kt`): where peek, half and full are, where a
 * release goes, what a tap on the handle and Back do, and how far the player has compacted. Units
 * are dp-as-px on the 844 dp board (`QueueHalf`, `QueueFull`, `StoryQueue`).
 */
class QueueDetentsTest {

	private val board = queueAnchors(844f, 110f, 96f, 414f, 26f, 72f)
	private val noHalf = QueueAnchors(peekTop = 450f, halfTop = null, fullTop = 96f)

	private fun near(expected: Float, actual: Float?, tolerance: Float = 0.001f) {
		assertTrue(actual != null && abs(expected - actual) <= tolerance, "expected $expected, got $actual")
	}

	@Test
	fun theBoardsAnchorsAreThePlansNumbers() {
		assertEquals(734f, board.peekTop)
		assertEquals(440f, board.halfTop)
		assertEquals(96f, board.fullTop)
		// The storyboard's 344 of 638 is 0.46.
		near(0.4608f, board.halfFraction)
		assertEquals(734f, board.top(QueueDetent.Peek))
		assertEquals(440f, board.top(QueueDetent.Half))
		assertEquals(96f, board.top(QueueDetent.Full))
	}

	@Test
	fun theHalfLineFollowsATallerCompactBlockAndNeverOverlapsIt() {
		val bigFont = queueAnchors(844f, 110f, 96f, 520f, 26f, 72f)
		assertEquals(546f, bigFont.halfTop)
	}

	@Test
	fun aShortScreenHasNoHalf() {
		val short = queueAnchors(560f, 110f, 96f, 414f, 26f, 72f)
		// 440 is not above 450 - 72.
		assertNull(short.halfTop)
		assertNull(short.top(QueueDetent.Half))
		assertNull(short.halfFraction)
		assertEquals(450f, short.top(QueueDetent.Peek))
		assertEquals(96f, short.top(QueueDetent.Full))
	}

	@Test
	fun aReleaseGoesToTheAnchorNearestItsProjectedOffset() {
		assertEquals(QueueDetent.Peek, releaseTarget(734f, 0f, board))
		assertEquals(QueueDetent.Peek, releaseTarget(600f, 0f, board))
		assertEquals(QueueDetent.Half, releaseTarget(580f, 0f, board))
		assertEquals(QueueDetent.Half, releaseTarget(600f, -1500f, board))
		assertEquals(QueueDetent.Full, releaseTarget(450f, -1500f, board))
		assertEquals(QueueDetent.Full, releaseTarget(734f, -4500f, board))
		assertEquals(QueueDetent.Half, releaseTarget(734f, -3000f, board))
		assertEquals(QueueDetent.Half, releaseTarget(100f, 1500f, board))
		assertEquals(QueueDetent.Peek, releaseTarget(440f, 4500f, board))
	}

	@Test
	fun withNoHalfAReleaseChoosesBetweenPeekAndFull() {
		// 150 from peek, 204 from full.
		assertEquals(QueueDetent.Peek, releaseTarget(300f, 0f, noHalf))
		assertEquals(QueueDetent.Full, releaseTarget(250f, 0f, noHalf))
	}

	@Test
	fun aTapOnTheHandleMovesOnePlace() {
		assertEquals(QueueDetent.Half, handleTapTarget(QueueDetent.Peek, board))
		assertEquals(QueueDetent.Full, handleTapTarget(QueueDetent.Half, board))
		assertEquals(QueueDetent.Half, handleTapTarget(QueueDetent.Full, board))
		assertEquals(QueueDetent.Full, handleTapTarget(QueueDetent.Peek, noHalf))
		assertEquals(QueueDetent.Peek, handleTapTarget(QueueDetent.Full, noHalf))
	}

	@Test
	fun backCollapsesOnePlaceThenClosesThePlayer() {
		assertEquals(QueueDetent.Half, backTarget(QueueDetent.Full, board))
		assertEquals(QueueDetent.Peek, backTarget(QueueDetent.Half, board))
		assertNull(backTarget(QueueDetent.Peek, board))
		assertEquals(QueueDetent.Peek, backTarget(QueueDetent.Full, noHalf))
		assertNull(backTarget(QueueDetent.Peek, noHalf))
	}

	@Test
	fun compactionRunsToTheHalfLineThenToFull() {
		assertEquals(Compaction(0f, 0f), compaction(0f, 0.46f))
		assertEquals(Compaction(1f, 0f), compaction(0.46f, 0.46f))
		assertEquals(Compaction(1f, 1f), compaction(1f, 0.46f))
		val past = compaction(0.73f, 0.46f)
		near(1f, past.toHalf)
		near(0.5f, past.toFull)
		// Out of range (an overshooting settle) is clamped.
		assertEquals(Compaction(0f, 0f), compaction(-0.1f, 0.46f))
		assertEquals(Compaction(1f, 1f), compaction(1.05f, 0.46f))
	}

	@Test
	fun theCurvesHitTheBoardsValuesAtRestHalfAndFull() {
		val rest = Compaction(0f, 0f)
		val half = Compaction(1f, 0f)
		val full = Compaction(1f, 1f)
		near(1f, coverScale(rest)); near(0.445f, coverScale(half)); near(0.3f, coverScale(full))
		near(1f, coverAlpha(rest)); near(1f, coverAlpha(half)); near(0f, coverAlpha(full))
		near(1f, headerAlpha(rest)); near(1f, headerAlpha(half)); near(0f, headerAlpha(full))
		near(0f, miniHeaderAlpha(rest)); near(0f, miniHeaderAlpha(half)); near(1f, miniHeaderAlpha(full))
		near(1f, actionsAlpha(rest)); near(0f, actionsAlpha(half)); near(0f, actionsAlpha(full))
		near(1f, playbackAlpha(rest)); near(1f, playbackAlpha(half)); near(0f, playbackAlpha(full))
		// The action row is gone halfway to the half line.
		near(0f, actionsAlpha(Compaction(0.5f, 0f)))
	}

	@Test
	fun theHeaderAndTheMiniHeaderNeverStandOnEachOther() {
		val path = (0..100).map { Compaction(1f, it / 100f) }
		for (k in path) {
			assertTrue(headerAlpha(k) == 0f || miniHeaderAlpha(k) == 0f, "both showing at $k")
		}
		// And the cover has cleared before the mini header's cover arrives.
		for (k in path) {
			assertTrue(coverAlpha(k) == 0f || miniHeaderAlpha(k) == 0f, "cover and mini header at $k")
		}
	}

	@Test
	fun everyCurveIsMonotonicAlongTheGesture() {
		val curves = mapOf(
			"coverScale" to ::coverScale,
			"coverAlpha" to ::coverAlpha,
			"headerAlpha" to ::headerAlpha,
			"miniHeaderAlpha" to ::miniHeaderAlpha,
			"actionsAlpha" to ::actionsAlpha,
			"playbackAlpha" to ::playbackAlpha
		)
		val path = (0..200).map { compaction(it / 200f, 0.46f) }
		for ((name, curve) in curves) {
			val values = path.map(curve)
			val rising = values.zipWithNext().all { (a, b) -> b >= a - 1e-6f }
			val falling = values.zipWithNext().all { (a, b) -> b <= a + 1e-6f }
			assertTrue(rising || falling, "$name is not monotonic: $values")
		}
	}

	@Test
	fun theFractionIsHowFarFromPeekToFull() {
		assertEquals(0f, board.fraction(734f))
		near(0.4608f, board.fraction(440f))
		assertEquals(1f, board.fraction(96f))
		// Clamped past either end.
		assertEquals(0f, board.fraction(800f))
		assertEquals(1f, board.fraction(50f))
	}

	@Test
	fun theStoryboardsTwoCurvesSplitAtTheHalfLine() {
		assertEquals(DetentCurve.Rise, detentCurve(QueueDetent.Peek, QueueDetent.Half))
		assertEquals(DetentCurve.Rise, detentCurve(QueueDetent.Half, QueueDetent.Peek))
		assertEquals(DetentCurve.Open, detentCurve(QueueDetent.Half, QueueDetent.Full))
		assertEquals(DetentCurve.Open, detentCurve(QueueDetent.Full, QueueDetent.Half))
		// A fling that passes half finishes on the curve of where it lands.
		assertEquals(DetentCurve.Open, detentCurve(QueueDetent.Peek, QueueDetent.Full))
		assertEquals(DetentCurve.Rise, detentCurve(QueueDetent.Full, QueueDetent.Peek))
	}

	@Test
	fun onlyAPortraitPhoneGetsTheStagedPlayer() {
		assertTrue(stagedPlayerFits(448f, 997f))
		assertTrue(stagedPlayerFits(390f, 844f))
		// Landscape, a tablet, a square window: the old player and the queue sheet.
		assertTrue(!stagedPlayerFits(997f, 448f))
		assertTrue(!stagedPlayerFits(800f, 1280f))
		assertTrue(!stagedPlayerFits(500f, 500f))
		assertTrue(!stagedPlayerFits(600f, 960f))
	}

	@Test
	fun aSwipeOnThePlayerOpensTheQueueToHalfAndNoFurtherByAFlick() {
		// From peek, a flick of any speed stops at half.
		assertEquals(QueueDetent.Half, playerReleaseTarget(734f, -1500f, board))
		assertEquals(QueueDetent.Half, playerReleaseTarget(734f, -4500f, board))
		assertEquals(QueueDetent.Half, playerReleaseTarget(600f, -3000f, board))
		// A finger that took the layer past the half line itself may still finish at full.
		assertEquals(QueueDetent.Full, playerReleaseTarget(300f, -1500f, board))
		// Down from half or full it comes back down, to peek at most, as a release on the layer does.
		assertEquals(QueueDetent.Peek, playerReleaseTarget(440f, 1500f, board))
		assertEquals(QueueDetent.Half, playerReleaseTarget(150f, 1500f, board))
		// A slow release stays with the nearest place.
		assertEquals(QueueDetent.Peek, playerReleaseTarget(700f, 0f, board))
		// No half: the player's flick goes to full, there being nowhere between.
		assertEquals(QueueDetent.Full, playerReleaseTarget(450f, -4500f, noHalf))
	}
}
