package paige.navic.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * The rules in ActPins.kt: a value this device has commanded is shown at once and stands until
 * the hub answers for the act that set it. Each test is a frame order that showed the old value
 * again under the timed holds, or would let a pin outlive what it was made for.
 */
class ActPinsTest {
	private val t0 = 1_000_000L

	private val queue = listOf("a", "b", "c").map {
		RemoteTrack(id = it, title = it, artist = "", album = null, durationMs = 200_000, imageUrl = null)
	}

	/** Track b of a session, playing at 0:30 as of [t0]. */
	private val playing = RemoteSessionState(
		tracks = queue,
		index = 1,
		isPlaying = true,
		positionMs = 30_000,
		positionAtMs = t0
	)

	/** The hub's snapshot of [playing] with some fields changed, as a `session` frame builds it. */
	private fun snapshot(
		isPlaying: Boolean = true,
		positionMs: Long = 30_000,
		index: Int = 1,
		tracks: List<RemoteTrack> = queue,
		atMs: Long
	) = RemoteSessionState(
		tracks = tracks, index = index, isPlaying = isPlaying, positionMs = positionMs, positionAtMs = atMs
	)

	// --- play state ------------------------------------------------------------------------

	@Test
	fun aPauseIsShownAtOnceAndStopsThePlayheadWhereItWasShown() {
		val paused = playing.pinPlaying(false, "n1", t0 + 4_000)
		assertFalse(paused.isPlaying)
		// 0:30 at t0, playing: shown at 0:34 when the button was pressed.
		assertEquals(34_000L, paused.positionMs)
		assertEquals(PlayPin("n1", t0 + 4_000, under = true), paused.pins.play)
	}

	@Test
	fun aSnapshotFromBeforeThePauseDoesNotUndoIt() {
		val paused = playing.pinPlaying(false, "n1", t0)
		// Another controller's queue edit, broadcast before the hub read the pause.
		val after = snapshot(isPlaying = true, atMs = t0 + 100).underPins(paused, t0 + 100)
		assertFalse(after.isPlaying)
		assertEquals(true, after.pins.play?.under)
	}

	@Test
	fun aProgressFrameFromBeforeThePauseDoesNotUndoIt() {
		val paused = playing.pinPlaying(false, "n1", t0)
		val after = paused.afterProgress(index = 1, isPlaying = true, positionMs = 30_500, nowMs = t0 + 200)
		assertFalse(after.isPlaying)
		assertEquals(true, after.pins.play?.under)
		// The position is not pinned, and follows the frame.
		assertEquals(30_500L, after.positionMs)
	}

	@Test
	fun theAckLetsTheHubsAnswerThrough() {
		val paused = playing.pinPlaying(false, "n1", t0)
		val answered = snapshot(isPlaying = false, atMs = t0 + 300).underPins(paused, t0 + 300)
		val acked = answered.afterAck("n1")
		assertFalse(acked.isPlaying)
		assertTrue(acked.pins.none)
	}

	@Test
	fun anotherControllersPlayWinsOnceTheAckIsIn() {
		val paused = playing.pinPlaying(false, "n1", t0)
		// The hub read our pause, then the other controller's play. Whichever side of our ack
		// the second frame lands on, it is what shows once the ack is in.
		val ours = snapshot(isPlaying = false, atMs = t0 + 300).underPins(paused, t0 + 300)
		val theirsAfter = snapshot(isPlaying = true, atMs = t0 + 310).underPins(ours.afterAck("n1"), t0 + 310)
		assertTrue(theirsAfter.isPlaying)
		val theirsBefore = snapshot(isPlaying = true, atMs = t0 + 310).underPins(ours, t0 + 310)
		assertFalse(theirsBefore.isPlaying)
		assertTrue(theirsBefore.afterAck("n1").isPlaying)
	}

	@Test
	fun aRefusalPutsBackWhatTheHubHad() {
		// ok:false changed nothing and caused no frame: the pin goes, the old state shows.
		val paused = playing.pinPlaying(false, "n1", t0 + 4_000)
		val acked = paused.afterAck("n1")
		assertTrue(acked.isPlaying)
		// Playing on from where the pause froze it, so the playhead has lost nothing.
		assertEquals(34_000L, acked.positionMs)
		assertEquals(t0 + 4_000, acked.positionAtMs)
	}

	@Test
	fun onlyTheNewestActOnAValueReleasesIt() {
		// Pause, then play again before the first answer: two acts, one pin.
		val toggled = playing.pinPlaying(false, "n1", t0).pinPlaying(true, "n2", t0 + 100)
		assertTrue(toggled.isPlaying)
		// What the hub said is still "playing", not this device's own first guess.
		assertEquals(true, toggled.pins.play?.under)
		val afterFirst = snapshot(isPlaying = false, atMs = t0 + 300).underPins(toggled, t0 + 300).afterAck("n1")
		assertTrue(afterFirst.isPlaying)
		assertEquals("n2", afterFirst.pins.play?.cid)
		val afterSecond = snapshot(isPlaying = true, atMs = t0 + 400).underPins(afterFirst, t0 + 400).afterAck("n2")
		assertTrue(afterSecond.isPlaying)
		assertTrue(afterSecond.pins.none)
	}

	@Test
	fun anAckForAnActThatPinnedNothingChangesNothing() {
		val paused = playing.pinPlaying(false, "n1", t0)
		assertEquals(paused, paused.afterAck("n7"))
		assertEquals(playing, playing.afterAck("n7"))
	}

	// --- position --------------------------------------------------------------------------

	@Test
	fun aSeekIsShownAtOnceAndRemembersWhatTheHubHad() {
		val seeked = playing.pinPosition(120_000, "n1", t0 + 1_000)
		assertEquals(120_000L, seeked.positionMs)
		assertEquals(t0 + 1_000, seeked.positionAtMs)
		assertEquals(PositionPin("n1", t0 + 1_000, index = 1, trackId = "b", underMs = 30_000, underAtMs = t0), seeked.pins.position)
		assertNull(seeked.seekHold)
	}

	@Test
	fun aSnapshotFromBeforeTheSeekDoesNotRewindIt() {
		// Under the timed hold any `session` frame ended the hold and rewound the mirror.
		val seeked = playing.pinPosition(120_000, "n1", t0)
		val after = snapshot(positionMs = 30_400, atMs = t0 + 200).underPins(seeked, t0 + 200)
		assertEquals(120_000L, after.positionMs)
		assertEquals(t0, after.positionAtMs)
		assertEquals(30_400L, after.pins.position?.underMs)
	}

	@Test
	fun progressFromBeforeTheSeekIsKeptUnderThePinHoweverLate() {
		val seeked = playing.pinPosition(120_000, "n1", t0)
		// 1.8 s on: past the timed hold's 1 s, and still not the answer.
		val after = seeked.afterProgress(index = 1, isPlaying = true, positionMs = 31_800, nowMs = t0 + 1_800)
		assertEquals(120_000L, after.positionMs)
		assertEquals(t0, after.positionAtMs)
		assertEquals(31_800L, after.pins.position?.underMs)
		assertEquals(t0 + 1_800, after.pins.position?.underAtMs)
	}

	@Test
	fun theSeeksAckShowsTheHubsPosition() {
		val seeked = playing.pinPosition(120_000, "n1", t0)
		val acked = snapshot(positionMs = 120_000, atMs = t0 + 300).underPins(seeked, t0 + 300).afterAck("n1")
		assertEquals(120_000L, acked.positionMs)
		assertEquals(t0 + 300, acked.positionAtMs)
		assertTrue(acked.pins.none)
	}

	@Test
	fun aSecondSeekKeepsTheHubsPositionNotTheFirstTarget() {
		val twice = playing.pinPosition(120_000, "n1", t0).pinPosition(60_000, "n2", t0 + 100)
		assertEquals(60_000L, twice.positionMs)
		assertEquals(30_000L, twice.pins.position?.underMs)
		// The first seek's ack is not this pin's.
		assertEquals(60_000L, twice.afterAck("n1").positionMs)
	}

	@Test
	fun aTrackChangeDropsThePositionPin() {
		val seeked = playing.pinPosition(120_000, "n1", t0)
		// The next track started: its position is not one to hold a seek on the last against.
		val next = snapshot(index = 2, positionMs = 0, atMs = t0 + 500).underPins(seeked, t0 + 500)
		assertEquals(0L, next.positionMs)
		assertNull(next.pins.position)
		// Same row, another track (the queue was replaced).
		val other = queue.map { it.copy(id = it.id + "2") }
		val replaced = snapshot(tracks = other, positionMs = 0, atMs = t0 + 500).underPins(seeked, t0 + 500)
		assertEquals(0L, replaced.positionMs)
		assertNull(replaced.pins.position)
		// And through a `progress` frame, which names only the row.
		val progressed = seeked.afterProgress(index = 2, isPlaying = true, positionMs = 900, nowMs = t0 + 500)
		assertEquals(900L, progressed.positionMs)
		assertNull(progressed.pins.position)
	}

	@Test
	fun aPausePinSurvivesATrackChange() {
		val paused = playing.pinPlaying(false, "n1", t0).pinPosition(120_000, "n2", t0)
		val next = snapshot(index = 2, positionMs = 0, isPlaying = true, atMs = t0 + 500).underPins(paused, t0 + 500)
		assertFalse(next.isPlaying)
		assertEquals("n1", next.pins.play?.cid)
		assertNull(next.pins.position)
	}

	// --- no ack ----------------------------------------------------------------------------

	@Test
	fun aPinWithNoAckLapsesAfterItsDeadline() {
		val pinned = playing.pinPlaying(false, "n1", t0).pinPosition(120_000, "n2", t0 + 1_000)
		assertEquals(pinned, pinned.afterPinExpiry(t0 + ACT_PIN_MS - 1))
		// The pause is due, the later seek is not.
		val one = pinned.afterPinExpiry(t0 + ACT_PIN_MS)
		assertTrue(one.isPlaying)
		assertNull(one.pins.play)
		assertEquals(120_000L, one.positionMs)
		val both = one.afterPinExpiry(t0 + 1_000 + ACT_PIN_MS)
		assertTrue(both.pins.none)
		assertEquals(30_000L, both.positionMs)
		assertEquals(t0, both.positionAtMs)
	}

	@Test
	fun aClockSetBackEndsAPin() {
		val paused = playing.pinPlaying(false, "n1", t0)
		assertTrue(paused.afterPinExpiry(t0 - 60_000).pins.none)
	}

	@Test
	fun aMirrorWithNoPinsIsUntouched() {
		val frame = snapshot(positionMs = 45_000, atMs = t0 + 100)
		assertEquals(frame, frame.underPins(playing, t0 + 100))
		assertEquals(playing, playing.afterPinExpiry(t0 + 60_000))
		// And a progress frame applies as it always did.
		val after = playing.afterProgress(index = 1, isPlaying = false, positionMs = 31_000, nowMs = t0 + 100)
		assertFalse(after.isPlaying)
		assertEquals(31_000L, after.positionMs)
		assertTrue(after.pins.none)
	}

	// --- volume ----------------------------------------------------------------------------

	@Test
	fun aVolumePinStandsForItsDeviceUntilItsDeadline() {
		val pins = VolumePins().pinned("speaker", level = 40, cid = "n1", nowMs = t0)
		assertEquals(40, pins.levelFor("speaker", t0 + ACT_PIN_MS - 1))
		assertNull(pins.levelFor("speaker", t0 + ACT_PIN_MS))
		// A clock set back ends it too.
		assertNull(pins.levelFor("speaker", t0 - 1))
		// Playback moved: the level was commanded for the device it left, and stays there.
		assertNull(pins.levelFor("phone", t0 + 100))
		assertNull(pins.levelFor(null, t0 + 100))
	}

	@Test
	fun eachDevicesVolumeIsPinnedAndAnsweredOnItsOwn() {
		// The playing speaker turned down, then the kitchen's row before music is sent there.
		val pins = VolumePins()
			.pinned("speaker", level = 40, cid = "n1", nowMs = t0)
			.pinned("kitchen", level = 15, cid = "n2", nowMs = t0 + 300)
		assertEquals(40, pins.levelFor("speaker", t0 + 400))
		assertEquals(15, pins.levelFor("kitchen", t0 + 400))

		val afterFirstAck = pins.afterAck("n1")
		assertNull(afterFirstAck.levelFor("speaker", t0 + 400))
		assertEquals(15, afterFirstAck.levelFor("kitchen", t0 + 400))
		// The hub refused the second (`not_supported`): its ack releases the row all the same,
		// and the row shows the level the device list never stopped holding.
		assertEquals(VolumePins(), afterFirstAck.afterAck("n2"))
	}

	@Test
	fun aNewerLevelForADeviceIsNotReleasedByTheOlderActsAck() {
		val pins = VolumePins()
			.pinned("kitchen", level = 30, cid = "n1", nowMs = t0)
			.pinned("kitchen", level = 55, cid = "n2", nowMs = t0 + 200)
		// The first drag's ack lands while the second is still on its way.
		val afterOld = pins.afterAck("n1")
		assertEquals(55, afterOld.levelFor("kitchen", t0 + 300))
		assertEquals(pins, afterOld)
		assertNull(afterOld.afterAck("n2").levelFor("kitchen", t0 + 300))
	}

	@Test
	fun aVolumePinWithNoAckGoesWhenItsTimeIsUpAndTakesNoOtherWithIt() {
		val pins = VolumePins()
			.pinned("speaker", level = 40, cid = "n1", nowMs = t0)
			.pinned("kitchen", level = 15, cid = "n2", nowMs = t0 + 1_000)
		assertEquals(pins, pins.afterExpiry(t0 + ACT_PIN_MS - 1))
		val later = pins.afterExpiry(t0 + ACT_PIN_MS)
		assertEquals(setOf("kitchen"), later.byDevice.keys)
		assertEquals(VolumePins(), later.afterExpiry(t0 + 1_000 + ACT_PIN_MS))
		// An ack for an act that pinned nothing changes nothing.
		assertEquals(pins, pins.afterAck("n9"))
	}
}
