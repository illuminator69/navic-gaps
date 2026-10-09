package paige.navic.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/*
 * I-022: what follows a job once it is no longer the sheet's (`AlbumJobState` and the decisions
 * around it in AlbumOverride.kt).
 *
 * The job outlives the surface that started it: the manager follows it, and when it ends three
 * things are decided from the job alone — never from whether a sheet happens to be open. Which
 * screen leaves (only one showing the album whose id just died), which album id the local library
 * forgets (only one the contract says changed), and whether a second apply or remove may be sent
 * meanwhile (it may not). Each of those was a place where the first version quietly did nothing:
 * a sheet swiped away mid-apply left the user on a dead page with no outcome.
 */
class AlbumJobTest {
	private val release = "d105e2cd-1111-4111-8111-111111111111"
	private val previous = "22222222-2222-4222-8222-222222222222"

	private fun answered(
		state: String,
		albumId: String = "",
		done: Int = 12,
		releaseMbid: String = release,
		prevReleaseMbid: String = previous
	) = AlbumPollRead.Row(
		LbAlbumState(
			albumId = albumId,
			album = "Currents",
			override = LbAlbumOverrideRow(
				id = "ov-1",
				state = state,
				releaseMbid = releaseMbid,
				prevReleaseMbid = prevReleaseMbid,
				albumId = albumId,
				done = done,
				total = 12
			)
		)
	)

	private fun applied(albumId: String) =
		albumPollStart(AlbumJob.APPLY, 0).let { albumPollStep(it, answered("applied", albumId), 2_000) }

	private fun removed(
		lastAlbumId: String = "al-2",
		sawRemoving: Boolean = true,
		releaseMbid: String = release,
		prevReleaseMbid: String = previous
	): AlbumPoll {
		var poll = albumPollStart(AlbumJob.REMOVE, 0)
		if (sawRemoving) {
			poll = albumPollStep(poll, answered("removing", lastAlbumId, releaseMbid = releaseMbid, prevReleaseMbid = prevReleaseMbid), 2_000)
		}
		return albumPollStep(poll, AlbumPollRead.Gone, 4_000)
	}

	private fun timedOut() =
		albumPollStart(AlbumJob.APPLY, 0).let { albumPollStep(it, answered("applying", done = 3), ALBUM_POLL_GIVE_UP_MS) }

	/** When the jobs below end, and a screen that was already showing its album by then. */
	private val endedAt = 50_000L
	private val before = 10_000L
	private val later = 90_000L

	/** A job as the manager publishes it once it is over and the library has been settled. */
	private fun ended(poll: AlbumPoll, startedFor: String = "al-1", artistId: String = "ar-1") =
		AlbumJobState(
			rowId = "ov-1",
			albumId = startedFor,
			artistId = artistId,
			poll = poll,
			after = albumAfterPoll(poll, startedFor),
			endedAtMs = endedAt
		)

	/** A screen that was showing [shownAlbumId] when the job ended; the library still holds it. */
	private fun leave(job: AlbumJobState, shownAlbumId: String) =
		albumLeave(job, shownAlbumId, onScreenSinceMs = before, albumMissing = false)

	// --- albumLeave: which screen goes where ---------------------------------------------------

	@Test
	fun aScreenShowingTheDeadAlbumLeavesForItsNewPage() {
		// Decided from the job and the id the screen shows. No sheet is asked: it may be long closed.
		assertEquals(AlbumLeave.ToAlbum("al-2"), leave(ended(applied("al-2")), "al-1"))
	}

	@Test
	fun aScreenShowingSomethingElseStaysWhereItIs() {
		val job = ended(applied("al-2"))
		assertEquals(AlbumLeave.Stay, leave(job, "al-9"))
		// A screen that shows no album at all: the artist page, settings, the library.
		assertEquals(AlbumLeave.Stay, leave(job, ""))
		// The album's NEW page is not the dead one.
		assertEquals(AlbumLeave.Stay, leave(job, "al-2"))
	}

	@Test
	fun theDeadAlbumIsMatchedByItsWholeId() {
		assertEquals(AlbumLeave.Stay, leave(ended(applied("al-2"), startedFor = "al-1"), "al-10"))
	}

	@Test
	fun anApplyThatNamesNoAlbumIdSendsTheScreenToTheArtist() {
		// lb-bot's 60 s ran out before Navidrome indexed the album: there is no album page to open.
		assertEquals(AlbumLeave.ToArtist("ar-1"), leave(ended(applied("")), "al-1"))
	}

	@Test
	fun aRemoveNeverLandsOnAnAlbum() {
		// The row that named the album's id is gone, and the restore changed the id again.
		assertEquals(AlbumLeave.ToArtist("ar-1"), leave(ended(removed(lastAlbumId = "al-2"), startedFor = "al-2"), "al-2"))
		assertEquals(AlbumLeave.ToArtist("ar-1"), leave(ended(removed(lastAlbumId = "al-7"), startedFor = "al-2"), "al-2"))
	}

	@Test
	fun theUndoOfACoverOnlyChangeLeavesThePageWhereItIs() {
		// The override never changed the release, so the id is the same before and after it.
		val coverOnly = removed(lastAlbumId = "al-2", releaseMbid = release, prevReleaseMbid = release)
		assertEquals(AlbumLeave.Reread, leave(ended(coverOnly, startedFor = "al-2"), "al-2"))
		// A row that names no release says nothing about it: the page is left, to be safe.
		val unsaid = removed(lastAlbumId = "al-2", releaseMbid = "", prevReleaseMbid = "")
		assertEquals(AlbumLeave.ToArtist("ar-1"), leave(ended(unsaid, startedFor = "al-2"), "al-2"))
		// So is a remove nobody saw `removing`: there is no row to read the release from.
		val unseen = removed(sawRemoving = false)
		assertEquals(AlbumLeave.ToArtist("ar-1"), leave(ended(unseen, startedFor = "al-2"), "al-2"))
	}

	@Test
	fun withNoArtistToGoToTheDeadPageIsSimplyLeft() {
		assertEquals(AlbumLeave.Back, leave(ended(applied(""), artistId = ""), "al-1"))
		assertEquals(AlbumLeave.Back, leave(ended(removed(), startedFor = "al-2", artistId = ""), "al-2"))
		// A new album id needs no artist.
		assertEquals(AlbumLeave.ToAlbum("al-2"), leave(ended(applied("al-2"), artistId = ""), "al-1"))
	}

	@Test
	fun anAlbumWhoseIdSurvivedIsReadAgainInPlace() {
		// A cover-only change, or the same release applied again: the page is still the album.
		assertEquals(AlbumLeave.Reread, leave(ended(applied("al-1")), "al-1"))
	}

	@Test
	fun nothingMovesWhileTheJobRunsOrAfterItTimedOut() {
		val running = AlbumJobState("ov-1", "al-1", "ar-1", albumPollStart(AlbumJob.APPLY, 0))
		assertEquals(AlbumLeave.Stay, leave(running, "al-1"))
		// Over, but the local library is still being brought to agree: not yet.
		val settling = AlbumJobState("ov-1", "al-1", "ar-1", applied("al-2"), after = null)
		assertEquals(AlbumLeave.Stay, leave(settling, "al-1"))
		// Three minutes without an ending say nothing about where the album is.
		assertEquals(AlbumLeave.Stay, leave(ended(timedOut()), "al-1"))
	}

	// --- albumLeave: a screen opened after the job had ended -----------------------------------

	@Test
	fun aPageOpenedLaterOnAnAlbumTheLibraryStillHoldsIsNotSentAway() {
		// Files the retag left behind keep the old id an album of its own. Opening it afterwards
		// must open it, not bounce to the album it was split from.
		val job = ended(applied("al-2"))
		assertEquals(AlbumLeave.Stay, albumLeave(job, "al-1", onScreenSinceMs = later, albumMissing = false))
		// Nor is it read again for a job that was over before it appeared.
		assertEquals(AlbumLeave.Stay, albumLeave(ended(applied("al-1")), "al-1", onScreenSinceMs = later, albumMissing = false))
	}

	@Test
	fun aPageOpenedLaterOnAnIdTheLibraryDroppedIsSentWhereTheAlbumWent() {
		// A queue or a list still naming the dead id: there is nothing to show under it.
		val job = ended(applied("al-2"))
		assertEquals(AlbumLeave.ToAlbum("al-2"), albumLeave(job, "al-1", onScreenSinceMs = later, albumMissing = true))
		val undone = ended(removed(), startedFor = "al-2")
		assertEquals(AlbumLeave.ToArtist("ar-1"), albumLeave(undone, "al-2", onScreenSinceMs = later, albumMissing = true))
	}

	@Test
	fun aPageOnScreenAsTheJobEndsLeavesEvenWhileTheLibraryStillHoldsTheOldId() {
		// The row is dropped only once it is empty, which can be minutes later or never.
		val job = ended(applied("al-2"))
		assertEquals(AlbumLeave.ToAlbum("al-2"), albumLeave(job, "al-1", onScreenSinceMs = endedAt, albumMissing = false))
		assertEquals(AlbumLeave.ToAlbum("al-2"), albumLeave(job, "al-1", onScreenSinceMs = before, albumMissing = false))
	}

	@Test
	fun aMissingAlbumAloneMovesNobody() {
		// Missing from the library, but no job says where it went.
		val elsewhere = ended(applied("al-2"), startedFor = "al-7")
		assertEquals(AlbumLeave.Stay, albumLeave(elsewhere, "al-1", onScreenSinceMs = later, albumMissing = true))
		val running = AlbumJobState("ov-1", "al-1", "ar-1", albumPollStart(AlbumJob.APPLY, 0))
		assertEquals(AlbumLeave.Stay, albumLeave(running, "al-1", onScreenSinceMs = later, albumMissing = true))
	}

	// --- albumDeadId: which id the local library forgets --------------------------------------

	@Test
	fun anApplyThatMovedTheAlbumLeavesItsOldIdDead() {
		assertEquals("al-1", albumDeadId(applied("al-2"), startedAlbumId = "al-1"))
	}

	@Test
	fun nothingIsForgottenWhenTheIdDidNotChange() {
		// A cover-only change, the same release re-applied.
		assertEquals("", albumDeadId(applied("al-1"), startedAlbumId = "al-1"))
	}

	@Test
	fun nothingIsForgottenWhenLbBotNamesNoIdYet() {
		// `applied` with "" is lb-bot's 60 s running out: the next sync settles it, not a guess.
		assertEquals("", albumDeadId(applied(""), startedAlbumId = "al-1"))
	}

	@Test
	fun nothingIsForgottenOnAFailureATimeoutOrAVanishedRow() {
		val failed = albumPollStart(AlbumJob.APPLY, 0).let { albumPollStep(it, answered("failed", albumId = "al-2", done = 4), 2_000) }
		assertEquals(AlbumPollPhase.FAILED, failed.phase)
		assertEquals("", albumDeadId(failed, "al-1"))
		assertEquals("", albumDeadId(timedOut(), "al-1"))
		val vanished = albumPollStart(AlbumJob.APPLY, 0).let { albumPollStep(it, AlbumPollRead.Gone, 2_000) }
		assertEquals("", albumDeadId(vanished, "al-1"))
		// Still running.
		assertEquals("", albumDeadId(albumPollStart(AlbumJob.APPLY, 0), "al-1"))
	}

	@Test
	fun aFinishedRemoveOfARetagLeavesTheIdItWasStartedForDead() {
		// The override had changed the release, so putting the previous one back changes the id.
		assertEquals("al-2", albumDeadId(removed(), startedAlbumId = "al-2"))
		// An album that was untagged before: no previous release is still a different one.
		assertEquals("al-2", albumDeadId(removed(prevReleaseMbid = ""), startedAlbumId = "al-2"))
	}

	@Test
	fun aFinishedRemoveOfACoverOnlyChangeForgetsNothing() {
		// The release never changed, so neither did the id — on the apply or on the undo.
		assertEquals("", albumDeadId(removed(releaseMbid = release, prevReleaseMbid = release), startedAlbumId = "al-2"))
	}

	@Test
	fun aRowThatNamesNoReleaseForgetsNothing() {
		// Two blanks are not "the same release": lb-bot did not say, so nothing is concluded.
		assertEquals("", albumDeadId(removed(releaseMbid = "", prevReleaseMbid = ""), startedAlbumId = "al-2"))
	}

	@Test
	fun aRemoveNobodySawRemovingForgetsNothing() {
		// The 404 alone does not say what the row was: without a row there is no release to compare.
		assertEquals("", albumDeadId(removed(sawRemoving = false), startedAlbumId = "al-2"))
	}

	@Test
	fun aRemoveThatDidNotFinishForgetsNothing() {
		var poll = albumPollStart(AlbumJob.REMOVE, 0)
		poll = albumPollStep(poll, answered("removing", "al-2"), 2_000)
		poll = albumPollStep(poll, answered("applied", "al-2"), 4_000)
		assertEquals(AlbumPollPhase.FAILED, poll.phase)
		assertEquals("", albumDeadId(poll, "al-2"))
	}

	@Test
	fun aJobStartedForNoAlbumForgetsNothing() {
		assertEquals("", albumDeadId(applied("al-2"), startedAlbumId = ""))
		assertEquals("", albumDeadId(removed(), startedAlbumId = ""))
	}

	// --- albumJobInWay: a second apply or remove ------------------------------------------------

	private fun running(rowId: String, albumId: String) =
		AlbumJobState(rowId, albumId, "ar-1", albumPollStart(AlbumJob.APPLY, 0))

	@Test
	fun aSecondChangeToAnAlbumBeingFollowedIsRefused() {
		val jobs = listOf(running("ov-1", "al-1"), running("ov-2", "al-5"))
		assertEquals("ov-1", albumJobInWay(jobs, albumId = "al-1")?.rowId)
		assertEquals("ov-2", albumJobInWay(jobs, albumId = "al-5")?.rowId)
		assertNull(albumJobInWay(jobs, albumId = "al-9"))
	}

	@Test
	fun aRowBeingFollowedIsInTheWayWhateverAlbumIdItIsReachedBy() {
		// An undo pressed from the data panel knows the row, and the album under another id.
		val jobs = listOf(running("ov-1", "al-1"))
		assertEquals("ov-1", albumJobInWay(jobs, albumId = "al-2", rowId = "ov-1")?.rowId)
		assertNull(albumJobInWay(jobs, albumId = "al-2", rowId = "ov-7"))
	}

	@Test
	fun aJobStillSettlingIsStillInTheWayAndAnEndedOneIsNot() {
		val settling = AlbumJobState("ov-1", "al-1", "ar-1", applied("al-2"), after = null)
		assertEquals("ov-1", albumJobInWay(listOf(settling), albumId = "al-1")?.rowId)
		assertNull(albumJobInWay(listOf(ended(applied("al-2"))), albumId = "al-1"))
		// Given up on after three minutes: lb-bot will say `in_progress` itself if it is.
		assertNull(albumJobInWay(listOf(ended(timedOut())), albumId = "al-1"))
	}

	@Test
	fun aBlankIdMatchesNoJob() {
		val jobs = listOf(AlbumJobState("ov-1", "", "ar-1", albumPollStart(AlbumJob.REMOVE, 0)))
		assertNull(albumJobInWay(jobs, albumId = ""))
		assertNull(albumJobInWay(jobs, albumId = "", rowId = ""))
		assertEquals("ov-1", albumJobInWay(jobs, albumId = "", rowId = "ov-1")?.rowId)
	}
}
