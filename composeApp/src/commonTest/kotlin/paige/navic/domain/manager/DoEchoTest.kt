package paige.navic.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * The rules in DoEcho.kt: `applied`, the number this device sends back in every report, moves
 * only once a directive's effect is in the player's state, only forward, and only for the
 * connection the directive came in on. A report that leaves before that carries the old number
 * and the hub drops it, which is what stops a skip or a transfer from flickering back.
 */
class DoEchoTest {
	private val t0 = 1_000_000L

	/** Track 2 of a queue, playing at 0:30. */
	private val playing = EchoPlayerState(isPaused = false, index = 2, positionMs = 30_000, queueEmpty = false)

	private fun expect(cmd: String, seq: Int = 7, index: Int? = null, positionMs: Long? = null) =
		assertNotNull(doExpectation(cmd, seq, index, positionMs, t0))

	// --- what each directive has to leave in the player ------------------------------------

	@Test
	fun playWantsThePlayerNotPaused() {
		val play = expect("play")
		assertTrue(play.settledBy(playing, t0))
		assertFalse(play.settledBy(playing.copy(isPaused = true), t0))
	}

	@Test
	fun pauseWantsThePlayerPaused() {
		val pause = expect("pause")
		assertFalse(pause.settledBy(playing, t0))
		assertTrue(pause.settledBy(playing.copy(isPaused = true), t0))
	}

	@Test
	fun jumpWantsItsIndexAndNothingElse() {
		val jump = expect("jump", index = 5)
		// Still on the track it was told to leave: the report that used to send the hub back.
		assertFalse(jump.settledBy(playing, t0))
		assertTrue(jump.settledBy(playing.copy(index = 5, positionMs = 0), t0))
		// A jump does not say whether to play.
		assertTrue(jump.settledBy(playing.copy(index = 5, isPaused = true), t0))
	}

	@Test
	fun queueChangedWantsTheRowTheTrackMovedTo() {
		// A row above the playing track was removed: it is row 1 now.
		val edit = expect("queueChanged", index = 1)
		assertFalse(edit.settledBy(playing, t0))
		assertTrue(edit.settledBy(playing.copy(index = 1), t0))
	}

	@Test
	fun seekWantsAPositionNearItsTarget() {
		val seek = expect("seek", positionMs = 120_000)
		assertFalse(seek.settledBy(playing, t0))
		assertTrue(seek.settledBy(playing.copy(positionMs = 120_000), t0))
		// Playing on from the target is still the seek; the line is the one CommandHold draws.
		assertTrue(seek.settledBy(playing.copy(positionMs = 120_000 + DO_ECHO_SEEK_NEAR_MS - 1), t0))
		assertFalse(seek.settledBy(playing.copy(positionMs = 120_000 + DO_ECHO_SEEK_NEAR_MS), t0))
		assertFalse(seek.settledBy(playing.copy(positionMs = 120_000 - DO_ECHO_SEEK_NEAR_MS), t0))
		// A seek while paused is a seek too.
		assertTrue(seek.settledBy(playing.copy(positionMs = 120_000, isPaused = true), t0))
	}

	@Test
	fun clearWantsAnEmptyQueueAndAStoppedPlayer() {
		val clear = expect("clear")
		val emptied = EchoPlayerState(isPaused = true, index = 0, positionMs = 0, queueEmpty = true)
		assertTrue(clear.settledBy(emptied, t0))
		// Emptied and still "playing": what Navic reported after every do:clear.
		assertFalse(clear.settledBy(emptied.copy(isPaused = false), t0))
		assertFalse(clear.settledBy(playing.copy(isPaused = true), t0))
	}

	@Test
	fun aLoadAndADirectiveWithNothingToShowLeaveNoExpectation() {
		// The load's own awaited outcome settles it; a jump with no index has nothing to wait for.
		assertNull(doExpectation("load", 7, index = 3, positionMs = 0, nowMs = t0))
		assertNull(doExpectation("jump", 7, index = null, positionMs = null, nowMs = t0))
		assertNull(doExpectation("seek", 7, index = null, positionMs = null, nowMs = t0))
		assertNull(doExpectation("setVolume", 7, index = null, positionMs = null, nowMs = t0))
		assertNull(doExpectation(null, 7, index = null, positionMs = null, nowMs = t0))
	}

	// --- the deadline ---------------------------------------------------------------------

	@Test
	fun anExpectationSettlesAnywayAtItsDeadline() {
		val pause = expect("pause")
		assertEquals(t0 + DO_ECHO_DEADLINE_MS, pause.deadlineMs)
		assertFalse(pause.settledBy(playing, t0 + DO_ECHO_DEADLINE_MS - 1))
		assertTrue(pause.settledBy(playing, t0 + DO_ECHO_DEADLINE_MS))
	}

	@Test
	fun aClockThatWentBackwardsDoesNotWaitForEver() {
		assertTrue(expect("pause").settledBy(playing, t0 - 60_000))
	}

	// --- applied ---------------------------------------------------------------------------

	/** A connection on which directive 6 has been carried out. */
	private val at6 = DoEcho().onNewConnection().settled(1, 6)

	@Test
	fun appliedStartsAtZeroAndTakesASettledDirective() {
		assertEquals(DoEcho(connection = 1), DoEcho().onNewConnection())
		assertEquals(6, at6.applied)
		assertNull(at6.pending)
	}

	@Test
	fun appliedWaitsForTheEffectAndMovesWhenThePlayerShowsIt() {
		val waiting = at6.expecting(1, expect("jump", seq = 7, index = 5))
		// The command has run, the player has not caught up: reports still say 6.
		assertEquals(6, waiting.applied)
		assertEquals(6, waiting.evaluated(playing, t0 + 10).applied)
		assertEquals(7, waiting.pending?.seq)
		val seen = waiting.evaluated(playing.copy(index = 5), t0 + 20)
		assertEquals(7, seen.applied)
		assertNull(seen.pending)
	}

	@Test
	fun anEffectAlreadyInThePlayerSettlesAtOnce() {
		// A pause emits nothing further, so it must not wait for a next emission.
		val paused = playing.copy(isPaused = true)
		assertEquals(7, at6.expecting(1, expect("pause", seq = 7)).evaluated(paused, t0).applied)
	}

	@Test
	fun appliedMovesAtTheDeadlineWhateverThePlayerSays() {
		val waiting = at6.expecting(1, expect("seek", seq = 7, positionMs = 120_000))
		assertEquals(6, waiting.evaluated(playing, t0 + DO_ECHO_DEADLINE_MS - 1).applied)
		assertEquals(7, waiting.evaluated(playing, t0 + DO_ECHO_DEADLINE_MS).applied)
	}

	@Test
	fun appliedNeverMovesBack() {
		assertEquals(6, at6.settled(1, 4).applied)
		// Nor does an expectation for a number already passed wait for anything.
		assertNull(at6.expecting(1, expect("pause", seq = 5)).pending)
	}

	@Test
	fun theNewestExpectationReplacesOneStillWaiting() {
		val two = at6
			.expecting(1, expect("jump", seq = 7, index = 5))
			.expecting(1, expect("pause", seq = 8))
		assertEquals(8, two.pending?.seq)
		// The jump's index showing up is not what is waited for any more.
		assertEquals(6, two.evaluated(playing.copy(index = 5), t0).applied)
		// Settling the newer one covers the older: both commands have run.
		assertEquals(8, two.evaluated(playing.copy(index = 5, isPaused = true), t0).applied)
		// And an older one arriving late does not push the newer out.
		assertEquals(8, two.expecting(1, expect("jump", seq = 7, index = 5)).pending?.seq)
	}

	@Test
	fun aDirectiveThatEndsAtOnceCoversOneStillWaitingBelowIt() {
		// A load (settled when its outcome is known) after a seek that never showed.
		val waiting = at6.expecting(1, expect("seek", seq = 7, positionMs = 120_000))
		val loaded = waiting.settled(1, 8)
		assertEquals(8, loaded.applied)
		assertNull(loaded.pending)
		// One above it keeps waiting.
		val newer = at6.expecting(1, expect("pause", seq = 9)).settled(1, 8)
		assertEquals(8, newer.applied)
		assertEquals(9, newer.pending?.seq)
	}

	@Test
	fun aNewConnectionStartsFromZeroAndWaitsForNothing() {
		val waiting = at6.expecting(1, expect("pause", seq = 7))
		assertEquals(DoEcho(connection = 2), waiting.onNewConnection())
	}

	@Test
	fun aDirectiveFromThePreviousConnectionMovesNothing() {
		val next = at6.onNewConnection()
		// A load still running when the socket was replaced ends on the new connection.
		assertEquals(next, next.settled(1, 9))
		assertEquals(next, next.expecting(1, expect("pause", seq = 9)))
		// The new connection's own directives do.
		assertEquals(3, next.settled(2, 3).applied)
	}

	@Test
	fun withNothingWaitingAStateChangesNothing() {
		assertEquals(at6, at6.evaluated(playing, t0))
	}
}
