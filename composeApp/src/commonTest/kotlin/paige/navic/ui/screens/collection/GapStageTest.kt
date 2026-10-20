package paige.navic.ui.screens.collection

import paige.navic.domain.manager.LbFillEntry
import paige.navic.domain.manager.LbGap
import paige.navic.domain.manager.LbGapSource
import paige.navic.domain.manager.LbGapTask
import paige.navic.domain.manager.LbGapTrack
import paige.navic.ui.screens.external.StepStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/*
 * P4 round 2: which state the album page's gaps box is in. A transfer in flight beats everything; a
 * finished fill shows its result for a few seconds and then the box goes; files waiting on lb-bot's
 * workspace are their own state; the sources view searches, chooses or says nothing was found; a
 * failed fill keeps the box open; otherwise it is the user's fold.
 */
class GapStageTest {
	private fun track(position: Int, state: String) = LbGapTrack(position = position, title = "T$position", state = state)

	private fun gap(
		status: String = "ready",
		tracks: List<LbGapTrack> = listOf(track(1, "present"), track(2, "present"), track(3, "missing"), track(4, "missing")),
		sources: List<LbGapSource> = emptyList(),
		sourceTask: LbGapTask? = null,
		noSourceReason: String = "",
		stalledPlacement: Boolean = false,
		failDetail: String = "",
		mp3WouldHelp: Boolean = false
	) = LbGap(
		id = "g-1", album = "Soft Machinery", present = tracks.count { it.state == "present" }, total = tracks.size,
		status = status, tracks = tracks, sources = sources, sourceTask = sourceTask,
		noSourceReason = noSourceReason, stalledPlacement = stalledPlacement, failDetail = failDetail,
		mp3WouldHelp = mp3WouldHelp
	)

	private fun fill(outcome: String, settled: Boolean = true, finishedAt: Long = 0L, done: Int = 2) = LbFillEntry(
		key = "g-1", isGap = true, rgid = "", groupId = "g-1", releaseMbid = "", artist = "A", album = "Soft Machinery",
		quality = "", state = if (outcome == "done") "complete" else "failed", outcome = outcome,
		reason = "lb-bot's own words", percent = 100, done = done, total = 2, mp3WouldHelp = false,
		failureKind = "", retryable = true, attempts = 1, settled = settled, startedAt = 0L, finishedAt = finishedAt
	)

	private fun input(
		gap: LbGap? = gap(),
		mirrored: Int = 2,
		fill: LbFillEntry? = null,
		view: GapView = GapView.FOLDED,
		committed: Boolean = false,
		searchAsked: Boolean = false,
		now: Long = 100_000L
	) = GapInput(gap, mirrored, fill, view, committed, searchAsked, now)

	private val good = LbGapSource(id = 0, peer = "p", recommended = true, artistVerified = true, albumMatchOk = true)
	private val unverified = LbGapSource(id = 1, peer = "q", recommended = true, artistVerified = false, albumMatchOk = true)

	@Test
	fun beforeLbBotAnswersTheMirrorCountFoldsAndAnOpenBoxWaits() {
		assertEquals(GapStage.Folded(3), gapStage(input(gap = null, mirrored = 3)))
		assertEquals(GapStage.Loading, gapStage(input(gap = null, mirrored = 3, view = GapView.TRACKS)))
		assertEquals(GapStage.Absent, gapStage(input(gap = null, mirrored = 0)))
	}

	@Test
	fun foldedCountsTheTracksStillMissing() {
		assertEquals(GapStage.Folded(2), gapStage(input()))
	}

	@Test
	fun unfoldedListsOnlyTheMissingTracksAgainstTheReleaseLength() {
		val stage = assertIs<GapStage.Tracks>(gapStage(input(view = GapView.TRACKS)))
		assertEquals(listOf(3, 4), stage.missing.map { it.position })
		assertEquals(4, stage.total)
	}

	@Test
	fun aTransferInFlightBeatsTheFoldAndTheSources() {
		val moving = gap(tracks = listOf(track(1, "present"), track(2, "done"), track(3, "downloading")))
		listOf(GapView.FOLDED, GapView.TRACKS, GapView.SOURCES).forEach { view ->
			val stage = assertIs<GapStage.Active>(gapStage(input(gap = moving, view = view)))
			assertEquals(1, stage.done)
			assertEquals(2, stage.wanted)
		}
	}

	@Test
	fun aFetchLbBotAcceptedIsActiveBeforeItsStatusMoves() {
		assertIs<GapStage.Active>(gapStage(input(committed = true, view = GapView.SOURCES)))
	}

	@Test
	fun theStepsFollowTheTracks() {
		fun now(vararg states: String) = gapStage(
			input(gap = gap(status = "downloading", tracks = states.mapIndexed { i, s -> track(i + 1, s) }))
		).let { assertIs<GapStage.Active>(it).steps.indexOfFirst { step -> step.status == StepStatus.NOW } }
		assertEquals(1, now("present", "queued", "queued"))
		assertEquals(2, now("present", "downloaded", "downloading"))
		assertEquals(3, now("present", "downloaded", "downloaded"))
	}

	@Test
	fun aFinishedFillShowsItsResultForAFewSecondsThenTheBoxGoes() {
		val filled = gap(status = "complete", tracks = listOf(track(1, "present"), track(2, "done"), track(3, "done")))
		val done = fill("done", finishedAt = 100_000L)
		assertEquals(GapStage.Done(added = 2, present = 1, total = 3), gapStage(input(gap = filled, fill = done, now = 101_000L)))
		assertEquals(GapStage.Absent, gapStage(input(gap = filled, fill = done, now = 100_000L + GAP_RESULT_HOLD_MS)))
	}

	@Test
	fun aCompleteAlbumHasNoBox() {
		val whole = gap(status = "complete", tracks = listOf(track(1, "present"), track(2, "present")))
		assertEquals(GapStage.Absent, gapStage(input(gap = whole, view = GapView.TRACKS)))
	}

	@Test
	fun filesWaitingOnLbBotsWorkspaceAreAttentionWhateverTheView() {
		val stalled = gap(status = "failed", stalledPlacement = true, failDetail = "Reconcile in lb-bot")
		assertEquals(GapStage.Attention("Reconcile in lb-bot"), gapStage(input(gap = stalled, view = GapView.SOURCES)))
		val awaiting = gap(status = "picking", tracks = listOf(track(1, "present"), track(2, "downloaded"), track(3, "missing")))
		assertIs<GapStage.Attention>(gapStage(input(gap = awaiting)))
	}

	@Test
	fun theSourcesViewSearchesThenChoosesPreselectingOnlyASafeSource() {
		assertEquals(GapStage.Searching, gapStage(input(view = GapView.SOURCES)))
		assertEquals(GapStage.Searching, gapStage(input(view = GapView.SOURCES, gap = gap(sourceTask = LbGapTask(status = "running")))))
		assertEquals(
			GapStage.Choosing(listOf(good), 0),
			gapStage(input(view = GapView.SOURCES, gap = gap(status = "picking", sources = listOf(good))))
		)
		assertEquals(
			GapStage.Choosing(listOf(unverified), null),
			gapStage(input(view = GapView.SOURCES, gap = gap(status = "picking", sources = listOf(unverified))))
		)
	}

	@Test
	fun aSearchTapInFlightSearchesEvenOverOldSources() {
		assertEquals(
			GapStage.Searching,
			gapStage(input(view = GapView.SOURCES, searchAsked = true, gap = gap(status = "picking", sources = listOf(good))))
		)
	}

	@Test
	fun aSearchThatEndedWithNothingSaysSoWithLbBotsReason() {
		val none = gap(status = "picking", sourceTask = LbGapTask(status = "complete"), noSourceReason = "No FLAC", mp3WouldHelp = true)
		assertEquals(GapStage.NoSources("No FLAC", true), gapStage(input(view = GapView.SOURCES, gap = none)))
	}

	@Test
	fun aFailedFillKeepsTheBoxOpenUntilTheSourcesAreAskedAgain() {
		val failed = gap(status = "failed", failDetail = "The download itself failed")
		val entry = fill("failed")
		assertEquals(GapStage.Failed("The download itself failed", false), gapStage(input(gap = failed, fill = entry)))
		assertEquals(GapStage.Searching, gapStage(input(gap = failed, fill = entry, view = GapView.SOURCES, searchAsked = true)))
	}

	@Test
	fun aFailedFillShowsOverTheSourcesItWasRequestedFrom() {
		// The poll's summary drops the source rows, and nothing is searching: without this the box
		// sat on "Searching" after the fill failed.
		val failed = gap(status = "failed", failDetail = "The peer went offline")
		assertEquals(
			GapStage.Failed("The peer went offline", false),
			gapStage(input(gap = failed, fill = fill("failed"), view = GapView.SOURCES))
		)
	}

	@Test
	fun aCancelledFillIsJustTheFold() {
		assertEquals(GapStage.Folded(2), gapStage(input(fill = fill("cancelled"))))
	}
}
