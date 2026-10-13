package paige.navic.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * The rules in CommandHold.kt: a value this device has just commanded stands against the hub's
 * lagging echoes. The seek half is what keeps the scrubber (in the app and on the lock screen)
 * from going back to the old position between a seek and the hub's answer; the volume half is
 * what makes two quick presses of the rocker two steps.
 */
class CommandHoldTest {
	private val t0 = 1_000_000L

	/** Track 2 of a session, playing at 0:30 as of [t0]. */
	private val playing = RemoteSessionState(
		index = 2,
		isPlaying = true,
		positionMs = 30_000,
		positionAtMs = t0
	)

	/** [playing], with a seek to 2:00 sent at [t0]. */
	private val seeked = playing.afterSeekSent(120_000, t0)

	// --- seek ------------------------------------------------------------------------------

	@Test
	fun aSentSeekIsInTheMirrorAtOnce() {
		assertEquals(120_000L, seeked.positionMs)
		assertEquals(t0, seeked.positionAtMs)
		assertEquals(SeekHold(120_000, t0), seeked.seekHold)
		// Nothing else moved.
		assertEquals(playing, seeked.copy(positionMs = 30_000, seekHold = null))
	}

	@Test
	fun aSeekBelowZeroIsHeldAtZero() {
		assertEquals(SeekHold(0, t0), playing.afterSeekSent(-500, t0).seekHold)
	}

	@Test
	fun aPositionFromBeforeTheSeekIsLeftOut() {
		// The receiver's 1 Hz report, sent before it heard of the seek.
		val after = seeked.afterProgress(index = 2, isPlaying = true, positionMs = 31_000, nowMs = t0 + 300)
		assertEquals(120_000L, after.positionMs)
		// The timestamp stays the seek's, or the playhead would lose the 300 ms it has played.
		assertEquals(t0, after.positionAtMs)
		assertEquals(seeked.seekHold, after.seekHold)
	}

	@Test
	fun theRestOfAHeldFrameStillApplies() {
		val after = seeked.afterProgress(index = 2, isPlaying = false, positionMs = 31_000, nowMs = t0 + 300)
		assertFalse(after.isPlaying)
		assertEquals(120_000L, after.positionMs)
	}

	@Test
	fun aPositionAtTheTargetEndsTheHold() {
		val after = seeked.afterProgress(index = 2, isPlaying = true, positionMs = 120_700, nowMs = t0 + 700)
		assertEquals(120_700L, after.positionMs)
		assertEquals(t0 + 700, after.positionAtMs)
		assertNull(after.seekHold)
		// And with the hold over, the next frame is taken wherever it says.
		assertEquals(5_000L, after.afterProgress(2, true, 5_000, t0 + 800).positionMs)
	}

	@Test
	fun farIsTheSameLineTheHubDraws() {
		val just = seeked.afterProgress(2, true, 120_000 + SEEK_HOLD_FAR_MS - 1, t0 + 100)
		assertEquals(120_000 + SEEK_HOLD_FAR_MS - 1, just.positionMs)
		val far = seeked.afterProgress(2, true, 120_000 + SEEK_HOLD_FAR_MS, t0 + 100)
		assertEquals(120_000L, far.positionMs)
		// Behind the target is as far as ahead of it.
		val behind = seeked.afterProgress(2, true, 120_000 - SEEK_HOLD_FAR_MS, t0 + 100)
		assertEquals(120_000L, behind.positionMs)
	}

	@Test
	fun theHoldLapsesAfterASecond() {
		// Still held on its last millisecond.
		assertEquals(120_000L, seeked.afterProgress(2, true, 31_000, t0 + SEEK_HOLD_MS - 1).positionMs)
		// After it the receiver is believed, even where the seek never took.
		val after = seeked.afterProgress(2, true, 31_000, t0 + SEEK_HOLD_MS)
		assertEquals(31_000L, after.positionMs)
		assertNull(after.seekHold)
	}

	@Test
	fun aFrameForAnotherTrackIsATrackChangeNotAnEcho() {
		val after = seeked.afterProgress(index = 3, isPlaying = true, positionMs = 400, nowMs = t0 + 300)
		assertEquals(3, after.index)
		assertEquals(400L, after.positionMs)
		assertNull(after.seekHold)
	}

	@Test
	fun aClockThatWentBackwardsDoesNotHoldForEver() {
		val after = seeked.afterProgress(2, true, 31_000, t0 - 60_000)
		assertEquals(31_000L, after.positionMs)
	}

	@Test
	fun aFieldTheFrameOmitsKeepsItsValue() {
		val after = playing.afterProgress(index = null, isPlaying = null, positionMs = null, nowMs = t0 + 1_000)
		assertEquals(playing.copy(positionAtMs = t0 + 1_000), after)
		// A frame with no position says nothing about a seek either way.
		assertEquals(seeked.seekHold, seeked.afterProgress(null, null, null, t0 + 100).seekHold)
	}

	@Test
	fun withNoSeekSentAFrameIsAppliedAsItAlwaysWas() {
		val after = playing.afterProgress(index = 2, isPlaying = false, positionMs = 90_000, nowMs = t0 + 1_000)
		assertEquals(playing.copy(isPlaying = false, positionMs = 90_000, positionAtMs = t0 + 1_000), after)
	}

	// --- volume ----------------------------------------------------------------------------

	/** Volume up from 50, sent to the speaker at [t0]. */
	private val up = VolumeCommand(level = 55, atMs = t0, activeDeviceId = "speaker")

	private fun stands(
		command: VolumeCommand = up,
		frameLevel: Int = 50,
		frameIsNewer: Boolean = false,
		activeDeviceId: String? = "speaker",
		now: Long = t0 + 200
	) = volumeHoldStands(command, frameLevel, frameIsNewer, activeDeviceId, now)

	@Test
	fun theCommandedLevelStandsUntilAFrameAgrees() {
		// The frame from before the press: the second press must step from 55, not 50.
		assertTrue(stands())
		// A newer frame that is not the answer (somebody else's device came online).
		assertTrue(stands(frameIsNewer = true))
		// The hub's answer.
		assertFalse(stands(frameLevel = 55, frameIsNewer = true))
	}

	@Test
	fun theEchoOfAnEarlierPressDoesNotEndTheHold() {
		// Two presses, 50 → 55 → 60. The answer to the first lands after the second was sent.
		val second = VolumeCommand(level = 60, atMs = t0 + 150, activeDeviceId = "speaker")
		assertTrue(stands(command = second, frameLevel = 55, frameIsNewer = true))
		assertFalse(stands(command = second, frameLevel = 60, frameIsNewer = true))
	}

	@Test
	fun onlyAFrameFromAfterTheCommandCanAgree() {
		// Up and straight back down: 50 is commanded while the frame still says 50 from before
		// either press. That is not the answer, and the first press's 55 is still on its way.
		val down = VolumeCommand(level = 50, atMs = t0 + 150, activeDeviceId = "speaker")
		assertTrue(stands(command = down, frameLevel = 50, frameIsNewer = false))
		assertTrue(stands(command = down, frameLevel = 55, frameIsNewer = true))
		assertFalse(stands(command = down, frameLevel = 50, frameIsNewer = true))
	}

	@Test
	fun theHoldLapsesWhenNoAnswerComes() {
		assertTrue(stands(now = t0 + VOLUME_HOLD_MS - 1))
		assertFalse(stands(now = t0 + VOLUME_HOLD_MS))
		// Nor does a clock that went backwards hold it for ever.
		assertFalse(stands(now = t0 - 1))
	}

	@Test
	fun aLevelSentToOneDeviceSaysNothingAboutAnother() {
		// The session moved to the desktop inside the hold.
		assertFalse(stands(activeDeviceId = "desktop"))
		assertFalse(stands(activeDeviceId = null))
	}
}
