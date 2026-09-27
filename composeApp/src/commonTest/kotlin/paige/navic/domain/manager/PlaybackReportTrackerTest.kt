package paige.navic.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import paige.navic.domain.manager.PlaybackReportState.PAUSED
import paige.navic.domain.manager.PlaybackReportState.PLAYING
import paige.navic.domain.manager.PlaybackReportState.STARTING
import paige.navic.domain.manager.PlaybackReportState.STOPPED

/*
 * B-009: which OpenSubsonic `reportPlayback` calls each local-player event implies. The driver
 * (PlaybackReporter) only adds the capability gate, connectivity and the queue around these.
 */
class PlaybackReportTrackerTest {
	private fun snap(
		id: String? = "s1",
		playing: Boolean = false,
		wants: Boolean = playing,
		stopped: Boolean = false,
		pos: Long = 0L,
		rate: Float = 1f
	) = PlaybackSnapshot(id, playing, wants, stopped, pos, rate)

	private fun List<PlaybackReport>.states() = map { it.mediaId to it.state }

	@Test
	fun aTrackThatStartsPlayingReportsStartingThenPlaying() {
		val t = PlaybackReportTracker()
		val out = t.onMediaChanged(snap(playing = true), suppressed = false)
		assertEquals(listOf("s1" to STARTING, "s1" to PLAYING), out.states())
		assertEquals("s1", t.openId)
	}

	@Test
	fun aBufferingStartReportsStartingAndPlayingOnlyOnceAudioMoves() {
		val t = PlaybackReportTracker()
		assertEquals(listOf("s1" to STARTING), t.onMediaChanged(snap(wants = true), false).states())
		assertEquals(
			listOf("s1" to PLAYING),
			t.onPlayingChanged(snap(playing = true, pos = 40), false).states()
		)
	}

	@Test
	fun pauseAndResumeCarryThePosition() {
		val t = PlaybackReportTracker()
		t.onMediaChanged(snap(playing = true), false)
		val paused = t.onPlayingChanged(snap(pos = 61_000), false)
		assertEquals(listOf(PlaybackReport("s1", PAUSED, 61_000, 1f)), paused)
		assertEquals(listOf("s1" to PLAYING), t.onPlayingChanged(snap(playing = true, pos = 61_000), false).states())
	}

	@Test
	fun aRebufferIsNotAPause() {
		val t = PlaybackReportTracker()
		t.onMediaChanged(snap(playing = true), false)
		assertTrue(t.onPlayingChanged(snap(playing = false, wants = true, pos = 5_000), false).isEmpty())
	}

	@Test
	fun theQueueEndingClosesTheEntry() {
		val t = PlaybackReportTracker()
		t.onMediaChanged(snap(playing = true), false)
		val out = t.onPlayingChanged(snap(wants = true, stopped = true, pos = 200_000), false)
		assertEquals(listOf(PlaybackReport("s1", STOPPED, 200_000, 1f)), out)
		assertNull(t.openId)
	}

	@Test
	fun aClearedQueueStopsTheLastReportedItemAtItsLastPosition() {
		val t = PlaybackReportTracker()
		t.onMediaChanged(snap(playing = true, pos = 0), false)
		t.onPlayingChanged(snap(pos = 30_000), false)
		val out = t.onMediaChanged(snap(id = null, stopped = true), false)
		assertEquals(listOf(PlaybackReport("s1", STOPPED, 30_000, 1f)), out)
	}

	@Test
	fun anAutoAdvanceStartsTheNextTrack() {
		val t = PlaybackReportTracker()
		t.onMediaChanged(snap(playing = true), false)
		val out = t.onMediaChanged(snap(id = "s2", playing = true), false)
		assertEquals(listOf("s2" to STARTING, "s2" to PLAYING), out.states())
		assertEquals("s2", t.openId)
	}

	@Test
	fun aRestoredPausedQueueIsNotReportedButASkipWhilePausedIs() {
		val t = PlaybackReportTracker()
		assertTrue(t.onMediaChanged(snap(), false).isEmpty())
		assertNull(t.openId)

		t.onPlayingChanged(snap(playing = true), false)
		t.onPlayingChanged(snap(pos = 10_000), false)
		assertEquals(listOf("s2" to PAUSED), t.onMediaChanged(snap(id = "s2"), false).states())
	}

	@Test
	fun aSeekReReportsTheCurrentStateAtTheNewPosition() {
		val t = PlaybackReportTracker()
		t.onMediaChanged(snap(playing = true), false)
		assertEquals(
			listOf(PlaybackReport("s1", PLAYING, 90_000, 1f)),
			t.onPositionOrRateChanged(snap(playing = true, pos = 90_000), false)
		)
		t.onPlayingChanged(snap(pos = 90_000), false)
		assertEquals(
			listOf(PlaybackReport("s1", PAUSED, 10_000, 1f)),
			t.onPositionOrRateChanged(snap(pos = 10_000), false)
		)
	}

	@Test
	fun aRateChangeIsReported() {
		val t = PlaybackReportTracker()
		t.onMediaChanged(snap(playing = true), false)
		assertEquals(
			listOf(PlaybackReport("s1", PLAYING, 3_000, 1.5f)),
			t.onPositionOrRateChanged(snap(playing = true, pos = 3_000, rate = 1.5f), false)
		)
	}

	@Test
	fun aSeekOnAnItemNeverReportedSendsNothing() {
		val t = PlaybackReportTracker()
		assertTrue(t.onPositionOrRateChanged(snap(pos = 5_000), false).isEmpty())
	}

	@Test
	fun handingOffToARemoteDeviceClosesTheEntryOnceAndThenStaysSilent() {
		val t = PlaybackReportTracker()
		t.onMediaChanged(snap(playing = true), false)
		// RemoteSessionPlayer's swap pauses the local player just after isRemoteActive flips.
		val handoff = t.onPlayingChanged(snap(pos = 42_000), suppressed = true)
		assertEquals(listOf(PlaybackReport("s1", STOPPED, 42_000, 1f)), handoff)
		assertTrue(t.onMediaChanged(snap(id = "s2", playing = true), suppressed = true).isEmpty())
		assertTrue(t.onPlayingChanged(snap(id = "s2", playing = true), suppressed = true).isEmpty())
		assertTrue(t.onPositionOrRateChanged(snap(id = "s2", playing = true), suppressed = true).isEmpty())
	}

	@Test
	fun playbackReturningHereStartsAFreshEntry() {
		val t = PlaybackReportTracker()
		t.onMediaChanged(snap(playing = true), false)
		t.onPlayingChanged(snap(), suppressed = true)
		val out = t.onPlayingChanged(snap(playing = true, pos = 42_000), false)
		assertEquals(listOf("s1" to STARTING, "s1" to PLAYING), out.states())
	}

	@Test
	fun radioAndPreviewsAreNeverReported() {
		val t = PlaybackReportTracker()
		assertTrue(t.onMediaChanged(snap(id = "radio_7", playing = true), false).isEmpty())
		assertTrue(
			t.onMediaChanged(snap(id = PreviewManager.EXT_PREFIX + "deezer:1", playing = true), false).isEmpty()
		)
		assertNull(t.openId)
	}

	@Test
	fun switchingToARadioStreamClosesTheSongEntry() {
		val t = PlaybackReportTracker()
		t.onMediaChanged(snap(playing = true, pos = 1_000), false)
		val out = t.onMediaChanged(snap(id = "radio_7", playing = true), false)
		assertEquals(listOf("s1" to STOPPED), out.states())
	}
}
