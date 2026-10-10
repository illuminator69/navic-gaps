package paige.navic.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/*
 * I-022: the poll that follows an apply or a remove (`albumPollStep` in AlbumOverride.kt).
 *
 * lb-bot answers both at once and works in the background, and the album id the sheet was opened
 * with dies on the retag, so the job is followed by the override row's id: every 2 s, until lb-bot
 * says `applied` or `failed` (an apply) or the row is gone (a remove), or until the row has not
 * moved for three minutes.
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

	// The clock measures silence, not length (ruling X2): it restarts whenever the row's `state`
	// or `done` differs from the answer before it.

	@Test
	fun itGivesUpThreeMinutesAfterTheRowLastMoved() {
		var poll = albumPollStart(AlbumJob.APPLY, nowMs = 1_000)
		poll = poll.then(answered("applying", done = 5), 3_000)
		poll = poll.then(answered("applying", done = 6), 5_000)
		// Stuck at 6 from here on. A millisecond short of three minutes of that: still asking.
		poll = poll.then(answered("applying", done = 6), 5_000 + ALBUM_POLL_GIVE_UP_MS - 1)
		assertEquals(AlbumPollPhase.POLLING, poll.phase)
		poll = poll.then(answered("applying", done = 6), 5_000 + ALBUM_POLL_GIVE_UP_MS)
		assertEquals(AlbumPollPhase.TIMED_OUT, poll.phase)
		// "Still working" is said with the progress last heard, not instead of it.
		assertEquals(6, poll.row?.done)
		assertEquals(AlbumOutcome.StillWorking, albumOutcome(poll))
	}

	@Test
	fun aJobThatKeepsAdvancingNeverTimesOut() {
		// A long retag plus lb-bot's wait for Navidrome can run past three minutes with nothing
		// wrong.
		var poll = albumPollStart(AlbumJob.APPLY, 0)
		var at = 0L
		var done = 0
		while (at < ALBUM_POLL_GIVE_UP_MS * 3) {
			at += 60_000
			done += 1
			poll = poll.then(answered("applying", done = done, total = 500), at)
			assertEquals(AlbumPollPhase.POLLING, poll.phase, "at $at ms")
		}
	}

	@Test
	fun aChangeOfStateIsMovementAndAMissedPollIsNot() {
		// Every file written, then the same count under a state this client does not know:
		// the row moved.
		var poll = albumPollStart(AlbumJob.APPLY, 0).then(answered("applying", done = 12), 2_000)
		poll = poll.then(answered("indexing", done = 12), 170_000)
		poll = poll.then(answered("indexing", done = 12), ALBUM_POLL_GIVE_UP_MS + 60_000)
		assertEquals(AlbumPollPhase.POLLING, poll.phase)
		// Silence is silence, whether lb-bot repeats itself or does not answer at all.
		poll = poll.then(AlbumPollRead.Missed, 170_000 + ALBUM_POLL_GIVE_UP_MS)
		assertEquals(AlbumPollPhase.TIMED_OUT, poll.phase)
	}

	@Test
	fun theFirstAnswerIsNotAMovement() {
		// Nothing came before it to differ from: one late answer in hand is still three minutes
		// of a row that has not moved.
		val poll = albumPollStart(AlbumJob.APPLY, 0).then(answered("applying", done = 3), ALBUM_POLL_GIVE_UP_MS)
		assertEquals(AlbumPollPhase.TIMED_OUT, poll.phase)
	}

	// --- the wait for Navidrome (ruling X4) ----------------------------------------------------

	@Test
	fun theWritesAreOverWhenSuccessesPlusFailuresReachTheTotal() {
		assertEquals(true, albumWaitingForIndex(row("applying", done = 12, total = 12).override))
		// `done` counts successes only: with one failed file "done == total" never comes.
		assertEquals(true, albumWaitingForIndex(row("applying", done = 11, total = 12, failures = 1).override))
		assertEquals(true, albumWaitingForIndex(row("removing", done = 12, total = 12).override))
		assertEquals(false, albumWaitingForIndex(row("applying", done = 11, total = 12).override))
		// Nothing counted yet is not "everything written".
		assertEquals(false, albumWaitingForIndex(row("applying", done = 0, total = 0).override))
		// Only a row lb-bot still holds active is waiting; at rest it is simply over.
		assertEquals(false, albumWaitingForIndex(row("applied", done = 12, total = 12).override))
		assertEquals(false, albumWaitingForIndex(row("failed", done = 10, total = 12, failures = 2).override))
		assertEquals(false, albumWaitingForIndex(null))
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

	// lb-bot flips the row to `removing` before it answers the POST, and can be done failing
	// before the first poll two seconds later (ruling X1).

	@Test
	fun aRemoveThatFailsBeforeTheFirstTickIsSeen() {
		val poll = albumPollStart(AlbumJob.REMOVE, 0, postState = "removing")
			.then(answered("failed", done = 10, failures = 2), 2_000)
		assertEquals(AlbumPollPhase.FAILED, poll.phase)
		assertEquals(AlbumOutcome.NotRemoved, albumOutcome(poll))
		// With lb-bot's reasons: they are what tells the user to undo again.
		assertEquals(2, albumJobFailures(poll).size)
		assertEquals(poll.row?.failures, albumJobFailures(poll))
	}

	@Test
	fun onlyThePostsOwnRemovingCountsAsHavingSeenIt() {
		// An answer that names another state, or none, proves nothing about the row.
		assertEquals(
			AlbumPollPhase.POLLING,
			albumPollStart(AlbumJob.REMOVE, 0, postState = "applied").then(answered("failed"), 2_000).phase
		)
		assertEquals(
			AlbumPollPhase.POLLING,
			albumPollStart(AlbumJob.REMOVE, 0, postState = "").then(answered("failed"), 2_000).phase
		)
		assertEquals(true, albumPollStart(AlbumJob.REMOVE, 0, postState = "removing").sawActive)
		assertEquals(false, albumPollStart(AlbumJob.REMOVE, 0).sawActive)
	}

	// --- lb-bot's reasons --------------------------------------------------------------------

	@Test
	fun lbBotsReasonsAreReadWhereverAJobEndedWithSome() {
		val partly = albumPollStart(AlbumJob.APPLY, 0).then(answered("applied", done = 10, failures = 2), 2_000)
		assertEquals(2, albumJobFailures(partly).size)
		val failed = albumPollStart(AlbumJob.APPLY, 0).then(answered("failed", done = 4, failures = 1), 2_000)
		assertEquals(1, albumJobFailures(failed).size)
		// A remove that did not finish: its reason says to undo again, so it must be on screen.
		val notRemoved = albumPollStart(AlbumJob.REMOVE, 0)
			.then(answered("removing"), 2_000)
			.then(answered("failed", done = 9, failures = 3), 4_000)
		assertEquals(AlbumOutcome.NotRemoved, albumOutcome(notRemoved))
		assertEquals(3, albumJobFailures(notRemoved).size)
	}

	@Test
	fun noReasonsAreShownForARemoveThatFinishedOrAJobStillRunning() {
		// The row last seen under a finished remove is from before it ended.
		val removed = albumPollStart(AlbumJob.REMOVE, 0)
			.then(answered("removing", done = 5, failures = 2), 2_000)
			.then(AlbumPollRead.Gone, 4_000)
		assertEquals(AlbumPollPhase.DONE, removed.phase)
		assertEquals(emptyList(), albumJobFailures(removed))
		val running = albumPollStart(AlbumJob.APPLY, 0).then(answered("applying", done = 3, failures = 1), 2_000)
		assertEquals(emptyList(), albumJobFailures(running))
		assertEquals(emptyList(), albumJobFailures(albumPollStart(AlbumJob.APPLY, 0)))
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
	}

	@Test
	fun aFailedApplyMovesNobodyUnlessLbBotNamesAnotherId() {
		// "Did not finish" says nothing about where the album is: only a different id does.
		val unknown = albumPollStart(AlbumJob.APPLY, 0).then(answered("failed", done = 4, albumId = ""), 2_000)
		assertEquals(AlbumAfter.Stay, albumAfterPoll(unknown, openedAlbumId = "al-1"))
		val same = albumPollStart(AlbumJob.APPLY, 0).then(answered("failed", done = 4, albumId = "al-1"), 2_000)
		assertEquals(AlbumAfter.Stay, albumAfterPoll(same, openedAlbumId = "al-1"))
		val moved = albumPollStart(AlbumJob.APPLY, 0).then(answered("failed", done = 4, albumId = "al-2"), 2_000)
		assertEquals(AlbumAfter.Album("al-2"), albumAfterPoll(moved, openedAlbumId = "al-1"))
		// The same for a remove that left the row standing.
		val standing = albumPollStart(AlbumJob.REMOVE, 0)
			.then(answered("removing", albumId = "al-2"), 2_000)
			.then(answered("applied", albumId = ""), 4_000)
		assertEquals(AlbumAfter.Stay, albumAfterPoll(standing, openedAlbumId = "al-2"))
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
