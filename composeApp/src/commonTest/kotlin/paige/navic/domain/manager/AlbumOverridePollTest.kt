package paige.navic.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/*
 * I-022: the poll that follows an apply or a remove (`albumPollStep` in AlbumOverride.kt).
 *
 * lb-bot answers both at once and works in the background, and the album id the sheet was opened
 * with dies on the retag, so the job is followed by the override row's id: every 2 s, until lb-bot
 * says `applied` or `failed` (an apply) or the row is gone (a remove), for three minutes at most.
 * The rule that is easy to get wrong is the quiet one: a poll that did not answer says nothing —
 * not "failed", not "done", and not "still working" either until the three minutes are up.
 */
class AlbumOverridePollTest {
	private fun row(state: String, done: Int = 0, total: Int = 12, albumId: String = "", failures: Int = 0) =
		LbAlbumState(
			albumId = albumId,
			artist = "Tame Impala",
			album = "Currents",
			override = LbAlbumOverrideRow(
				id = "ov-1",
				state = state,
				albumId = albumId,
				done = done,
				total = total,
				failures = List(failures) { LbAlbumFailure("Track $it", "read-only file") }
			)
		)

	private fun answered(state: String, done: Int = 0, total: Int = 12, albumId: String = "", failures: Int = 0) =
		AlbumPollRead.Row(row(state, done, total, albumId, failures))

	private fun AlbumPoll.then(read: AlbumPollRead, atMs: Long) = albumPollStep(this, read, atMs)

	// --- the clock ---------------------------------------------------------------------------

	@Test
	fun thePollTicksEveryTwoSecondsForThreeMinutes() {
		assertEquals(2_000L, ALBUM_POLL_INTERVAL_MS)
		assertEquals(180_000L, ALBUM_POLL_GIVE_UP_MS)
	}

	// --- an apply ----------------------------------------------------------------------------

	@Test
	fun anApplyIsFollowedUntilLbBotSaysApplied() {
		var poll = albumPollStart(AlbumJob.APPLY, nowMs = 0)
		assertEquals(AlbumPollPhase.POLLING, poll.phase)
		assertNull(poll.row)
		assertNull(albumOutcome(poll))

		poll = poll.then(answered("applying", done = 3), atMs = 2_000)
		assertEquals(AlbumPollPhase.POLLING, poll.phase)
		assertEquals(3, poll.row?.done)
		assertEquals(12, poll.row?.total)
		assertNull(albumOutcome(poll))

		poll = poll.then(answered("applied", done = 12, albumId = "al-2"), atMs = 4_000)
		assertEquals(AlbumPollPhase.DONE, poll.phase)
		assertEquals(AlbumOutcome.Wrote(files = 12), albumOutcome(poll))
	}

	@Test
	fun appliedWithFailuresIsAPartialResult() {
		val poll = albumPollStart(AlbumJob.APPLY, 0).then(answered("applied", done = 10, failures = 2), 2_000)
		assertEquals(AlbumPollPhase.DONE, poll.phase)
		assertEquals(AlbumOutcome.WrotePartly(done = 10, total = 12, failed = 2), albumOutcome(poll))
	}

	@Test
	fun failedEndsAnApply() {
		val poll = albumPollStart(AlbumJob.APPLY, 0).then(answered("failed", done = 4), 2_000)
		assertEquals(AlbumPollPhase.FAILED, poll.phase)
		assertEquals(AlbumOutcome.DidNotFinish(done = 4, total = 12), albumOutcome(poll))
	}

	@Test
	fun aFailedPollIsNotAnAnswer() {
		var poll = albumPollStart(AlbumJob.APPLY, 0).then(answered("applying", done = 3), 2_000)
		poll = poll.then(AlbumPollRead.Missed, 4_000)
		// Nothing ended, and the progress last heard is still what is shown.
		assertEquals(AlbumPollPhase.POLLING, poll.phase)
		assertEquals(3, poll.row?.done)
		poll = poll.then(AlbumPollRead.Missed, 6_000).then(answered("applied", done = 12), 8_000)
		assertEquals(AlbumPollPhase.DONE, poll.phase)
	}

	@Test
	fun aPollThatNeverAnswersEndsOnlyOnTheClock() {
		var poll = albumPollStart(AlbumJob.APPLY, 0)
		var at = 0L
		while (at + ALBUM_POLL_INTERVAL_MS < ALBUM_POLL_GIVE_UP_MS) {
			at += ALBUM_POLL_INTERVAL_MS
			poll = poll.then(AlbumPollRead.Missed, at)
			assertEquals(AlbumPollPhase.POLLING, poll.phase, "at $at ms")
		}
		poll = poll.then(AlbumPollRead.Missed, ALBUM_POLL_GIVE_UP_MS)
		assertEquals(AlbumPollPhase.TIMED_OUT, poll.phase)
		assertEquals(AlbumOutcome.StillWorking, albumOutcome(poll))
	}

	@Test
	fun itGivesUpAfterThreeMinutesOfApplying() {
		var poll = albumPollStart(AlbumJob.APPLY, nowMs = 1_000)
		poll = poll.then(answered("applying", done = 5), 1_000 + ALBUM_POLL_GIVE_UP_MS - 1)
		assertEquals(AlbumPollPhase.POLLING, poll.phase)
		poll = poll.then(answered("applying", done = 6), 1_000 + ALBUM_POLL_GIVE_UP_MS)
		assertEquals(AlbumPollPhase.TIMED_OUT, poll.phase)
		// "Still working" is said with the progress last heard, not instead of it.
		assertEquals(6, poll.row?.done)
		assertEquals(AlbumOutcome.StillWorking, albumOutcome(poll))
	}

	@Test
	fun anAnswerThatLandsAtTheDeadlineStillCounts() {
		// The answer is judged before the clock: lb-bot said applied, so it is applied.
		val poll = albumPollStart(AlbumJob.APPLY, 0).then(answered("applied", done = 12), ALBUM_POLL_GIVE_UP_MS + 5_000)
		assertEquals(AlbumPollPhase.DONE, poll.phase)
	}

	@Test
	fun theRowVanishingUnderAnApplyIsItsOwnEnding() {
		// Removed from another client while this one waited: not applied, not failed.
		val poll = albumPollStart(AlbumJob.APPLY, 0).then(answered("applying"), 2_000).then(AlbumPollRead.Gone, 4_000)
		assertEquals(AlbumPollPhase.GONE, poll.phase)
		assertEquals(AlbumOutcome.Gone, albumOutcome(poll))
	}

	@Test
	fun aRemoveStartedElsewhereDoesNotEndAnApply() {
		val poll = albumPollStart(AlbumJob.APPLY, 0).then(answered("removing"), 2_000)
		assertEquals(AlbumPollPhase.POLLING, poll.phase)
	}

	@Test
	fun aStateThisClientHasNeverHeardOfKeepsThePollGoing() {
		val poll = albumPollStart(AlbumJob.APPLY, 0).then(answered("verifying", done = 12), 2_000)
		assertEquals(AlbumPollPhase.POLLING, poll.phase)
		assertEquals("verifying", poll.row?.state)
	}

	// --- a remove ----------------------------------------------------------------------------

	@Test
	fun aRemoveEndsWhenTheRowIsGone() {
		var poll = albumPollStart(AlbumJob.REMOVE, 0).then(answered("removing"), 2_000)
		assertEquals(AlbumPollPhase.POLLING, poll.phase)
		assertNull(albumOutcome(poll))
		poll = poll.then(AlbumPollRead.Gone, 4_000)
		assertEquals(AlbumPollPhase.DONE, poll.phase)
		assertEquals(AlbumOutcome.Removed, albumOutcome(poll))
	}

	@Test
	fun aRemoveSoQuickNoPollSawItRemovingIsStillARemove() {
		val poll = albumPollStart(AlbumJob.REMOVE, 0).then(AlbumPollRead.Gone, 2_000)
		assertEquals(AlbumPollPhase.DONE, poll.phase)
		assertEquals(AlbumOutcome.Removed, albumOutcome(poll))
	}

	@Test
	fun aRemoveIsNotJudgedByTheStateItStartedFrom() {
		// The first poll can land before lb-bot has flipped the row: `applied` — or the `failed`
		// of the interrupted apply being undone — is then the OLD state, not the remove's verdict.
		var poll = albumPollStart(AlbumJob.REMOVE, 0).then(answered("applied", done = 12), 2_000)
		assertEquals(AlbumPollPhase.POLLING, poll.phase)
		poll = albumPollStart(AlbumJob.REMOVE, 0).then(answered("failed", done = 4), 2_000)
		assertEquals(AlbumPollPhase.POLLING, poll.phase)
		poll = poll.then(answered("removing"), 4_000).then(AlbumPollRead.Gone, 6_000)
		assertEquals(AlbumPollPhase.DONE, poll.phase)
	}

	@Test
	fun aRemoveThatFallsBackToAStandingRowDidNotFinish() {
		var poll = albumPollStart(AlbumJob.REMOVE, 0).then(answered("removing"), 2_000).then(answered("failed"), 4_000)
		assertEquals(AlbumPollPhase.FAILED, poll.phase)
		assertEquals(AlbumOutcome.NotRemoved, albumOutcome(poll))
		poll = albumPollStart(AlbumJob.REMOVE, 0).then(answered("removing"), 2_000).then(answered("applied"), 4_000)
		assertEquals(AlbumPollPhase.FAILED, poll.phase)
		assertEquals(AlbumOutcome.NotRemoved, albumOutcome(poll))
	}

	@Test
	fun aRemoveGivesUpOnTheClockToo() {
		val poll = albumPollStart(AlbumJob.REMOVE, 0).then(answered("removing"), ALBUM_POLL_GIVE_UP_MS)
		assertEquals(AlbumPollPhase.TIMED_OUT, poll.phase)
		assertEquals(AlbumOutcome.StillWorking, albumOutcome(poll))
	}

	// --- endings -----------------------------------------------------------------------------

	@Test
	fun aFinishedPollStaysFinished() {
		val done = albumPollStart(AlbumJob.APPLY, 0).then(answered("applied", done = 12), 2_000)
		assertEquals(done, done.then(answered("applying", done = 1), 4_000))
		assertEquals(done, done.then(AlbumPollRead.Gone, 4_000))
		assertEquals(done, done.then(AlbumPollRead.Missed, ALBUM_POLL_GIVE_UP_MS * 2))
	}

	// --- albumPollRead -----------------------------------------------------------------------

	@Test
	fun onlyLbBotsRowOrItsNoOverrideIsAnAnswer() {
		val state = row("applying", done = 1)
		assertEquals(AlbumPollRead.Row(state), albumPollRead(LbBotManager.LbResult.Ok(state)))
		fun failed(error: LbBotManager.LbError) = albumPollRead(LbBotManager.LbResult.Failed(error))
		assertEquals(AlbumPollRead.Gone, failed(LbBotManager.LbError.Rejected(404, "No such override", "no_override")))
		// Every other failure is a poll that did not answer.
		assertEquals(AlbumPollRead.Missed, failed(LbBotManager.LbError.Rejected(404, "Navidrome has no such album", "unknown_album")))
		assertEquals(AlbumPollRead.Missed, failed(LbBotManager.LbError.Rejected(409, "no_override", "busy")))
		assertEquals(AlbumPollRead.Missed, failed(LbBotManager.LbError.Rejected(502, "Navidrome did not answer")))
		assertEquals(AlbumPollRead.Missed, failed(LbBotManager.LbError.Rejected(200, ALBUM_NOT_AN_ANSWER)))
		assertEquals(AlbumPollRead.Missed, failed(LbBotManager.LbError.RouteUnknown))
		assertEquals(AlbumPollRead.Missed, failed(LbBotManager.LbError.Busy))
		assertEquals(AlbumPollRead.Missed, failed(LbBotManager.LbError.Unreachable("timeout")))
		assertEquals(AlbumPollRead.Missed, failed(LbBotManager.LbError.NotConfigured))
	}

	// --- a job found already running ---------------------------------------------------------

	@Test
	fun aJobFoundRunningWhenTheSheetOpensIsPickedUp() {
		assertEquals(AlbumJob.APPLY, albumJobInFlight(row("applying")))
		assertEquals(AlbumJob.REMOVE, albumJobInFlight(row("removing")))
		assertNull(albumJobInFlight(row("applied")))
		assertNull(albumJobInFlight(row("failed")))
		assertNull(albumJobInFlight(LbAlbumState(albumId = "al-1")))
	}

	@Test
	fun aPollStartedFromARowAlreadySeenShowsItAtOnce() {
		val seen = row("removing")
		val poll = albumPollStart(AlbumJob.REMOVE, nowMs = 0, seen = seen)
		assertEquals("removing", poll.row?.state)
		// It was seen removing, so the row standing again is the remove's own verdict.
		assertEquals(AlbumPollPhase.FAILED, poll.then(answered("failed"), 2_000).phase)
	}

	// --- where to go afterwards --------------------------------------------------------------

	@Test
	fun afterAnApplyTheAlbumIsOpenedWhereLbBotSaysItIsNow() {
		val moved = albumPollStart(AlbumJob.APPLY, 0).then(answered("applied", done = 12, albumId = "al-2"), 2_000)
		assertEquals(AlbumAfter.Album("al-2"), albumAfterPoll(moved, openedAlbumId = "al-1"))
		val unindexed = albumPollStart(AlbumJob.APPLY, 0).then(answered("applied", done = 12, albumId = ""), 2_000)
		assertEquals(AlbumAfter.Artist, albumAfterPoll(unindexed, openedAlbumId = "al-1"))
		val same = albumPollStart(AlbumJob.APPLY, 0).then(answered("applied", done = 12, albumId = "al-1"), 2_000)
		assertEquals(AlbumAfter.Stay, albumAfterPoll(same, openedAlbumId = "al-1"))
		// A failed apply wrote some files: the album is wherever lb-bot says, as after a whole one.
		val failed = albumPollStart(AlbumJob.APPLY, 0).then(answered("failed", done = 4, albumId = ""), 2_000)
		assertEquals(AlbumAfter.Artist, albumAfterPoll(failed, openedAlbumId = "al-1"))
	}

	@Test
	fun afterARemoveTheAlbumsIdIsNotKnown() {
		// The row that carried the current id is gone, and the restore changes the id again.
		val removed = albumPollStart(AlbumJob.REMOVE, 0).then(answered("removing", albumId = "al-2"), 2_000).then(AlbumPollRead.Gone, 4_000)
		assertEquals(AlbumAfter.Artist, albumAfterPoll(removed, openedAlbumId = "al-2"))
		val vanished = albumPollStart(AlbumJob.APPLY, 0).then(AlbumPollRead.Gone, 2_000)
		assertEquals(AlbumAfter.Artist, albumAfterPoll(vanished, openedAlbumId = "al-1"))
	}

	@Test
	fun nothingIsKnownToHaveMovedWhileLbBotIsStillWorkingOrNeverAnswered() {
		val waiting = albumPollStart(AlbumJob.APPLY, 0).then(answered("applying", done = 3), 2_000)
		assertEquals(AlbumAfter.Stay, albumAfterPoll(waiting, openedAlbumId = "al-1"))
		val timedOut = albumPollStart(AlbumJob.APPLY, 0).then(AlbumPollRead.Missed, ALBUM_POLL_GIVE_UP_MS)
		assertEquals(AlbumAfter.Stay, albumAfterPoll(timedOut, openedAlbumId = "al-1"))
	}
}
