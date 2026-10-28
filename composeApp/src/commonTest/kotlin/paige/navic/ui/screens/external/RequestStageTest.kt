package paige.navic.ui.screens.external

import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.request_step_count
import navic.composeapp.generated.resources.request_step_finding
import navic.composeapp.generated.resources.request_step_source_chosen
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbFillEntry
import paige.navic.domain.manager.LbGapSource
import paige.navic.domain.manager.LbRelease
import paige.navic.ui.components.common.FillButton
import paige.navic.ui.components.common.FillPresentation
import paige.navic.ui.components.common.FillProgressKind
import paige.navic.ui.components.common.FillText
import paige.navic.ui.components.common.describeFill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * P4: the request box is one function of what is known. A running fill is the truth whatever else
 * is set (it may come from another device, or from before a restart, and a second request must not
 * be offered); lb-bot's index saying the library holds the album beats a settled fill; a source
 * search answers only for the edition it was asked for; a source is selected for the user only when
 * lb-bot recommends it AND found the artist AND reads the folder as this album.
 */
class RequestStageTest {
	private fun fill(
		state: String = "downloading",
		outcome: String = "running",
		settled: Boolean = false,
		failureKind: String = "",
		retryable: Boolean = true,
		mp3WouldHelp: Boolean = false
	) = LbFillEntry(
		key = "rg-1",
		isGap = false,
		rgid = "rg-1",
		groupId = "",
		releaseMbid = "rel-1",
		artist = "A",
		album = "After",
		quality = "",
		state = state,
		outcome = outcome,
		reason = "lb-bot's own words",
		percent = 57,
		done = 4,
		total = 7,
		mp3WouldHelp = mp3WouldHelp,
		failureKind = failureKind,
		retryable = retryable,
		attempts = 1,
		settled = settled,
		startedAt = 0L,
		finishedAt = 0L
	)

	private fun source(
		peer: String = "bob",
		recommended: Boolean = false,
		artistVerified: Boolean = true,
		albumMatchOk: Boolean = true
	) = LbGapSource(peer = peer, recommended = recommended, artistVerified = artistVerified, albumMatchOk = albumMatchOk)

	private fun input(
		support: Support = Support.YES,
		fill: LbFillEntry? = null,
		indexed: LbRelease? = null,
		search: SourceSearch = SourceSearch.Idle,
		editionMbid: String = "ed-1",
		starting: Boolean = false
	) = RequestInput(
		support = support,
		fill = fill,
		indexed = indexed,
		search = search,
		editionMbid = editionMbid,
		starting = starting,
		wishlistAvailable = true,
		now = 1_000L
	)

	// ---- precedence -------------------------------------------------------- //

	@Test
	fun absentIsAbsentWhateverElseIsSet() {
		val stage = requestStage(input(
			support = Support.ABSENT,
			fill = fill(),
			starting = true,
			search = SourceSearch.Searching("ed-1")
		))
		assertEquals(RequestStage.Absent, stage)
	}

	@Test
	fun aHubTooOldWithNoFillNeedsARestart() {
		assertEquals(RequestStage.NeedsHubRestart, requestStage(input(support = Support.NEEDS_HUB_RESTART)))
	}

	@Test
	fun aRunningFillBeatsStartingASearchAndTheIndex() {
		val stage = requestStage(input(
			fill = fill(),
			starting = true,
			search = SourceSearch.Found("ed-1", listOf(source())),
			indexed = LbRelease(rgid = "rg-1", status = "complete")
		))
		assertIs<RequestStage.Active>(stage)
	}

	@Test
	fun aRunningFillShowsEvenOnAHubThatNeedsARestart() {
		assertIs<RequestStage.Active>(requestStage(input(support = Support.NEEDS_HUB_RESTART, fill = fill())))
	}

	// ---- settled fills ----------------------------------------------------- //

	@Test
	fun aDoneFillIsDone() {
		assertIs<RequestStage.Done>(requestStage(input(fill = fill(state = "verified", outcome = "done", settled = true))))
	}

	@Test
	fun failedAndGaveUpAreFailedWithDescribeFillsButtons() {
		val failed = fill(state = "failed", outcome = "failed", settled = true, failureKind = "no_source", retryable = false)
		val stage = requestStage(input(fill = failed))
		assertIs<RequestStage.Failed>(stage)
		assertEquals(describeFill(failed, 1_000L, true).buttons, stage.view.buttons)

		val gaveUp = fill(state = "searching", outcome = "gave_up", settled = true)
		val gaveUpStage = requestStage(input(fill = gaveUp))
		assertIs<RequestStage.Failed>(gaveUpStage)
		assertEquals(describeFill(gaveUp, 1_000L, true).buttons, gaveUpStage.view.buttons)
	}

	@Test
	fun aMatchOrAPickToMakeWantsAttention() {
		assertIs<RequestStage.Attention>(requestStage(input(
			fill = fill(state = "needs_match", outcome = "failed", settled = true)
		)))
		assertIs<RequestStage.Attention>(requestStage(input(
			fill = fill(state = "searching", outcome = "needs_pick", settled = true)
		)))
	}

	@Test
	fun aCancelledFillFallsThroughToTheSearch() {
		val cancelled = fill(state = "cancelled", outcome = "cancelled", settled = true)
		assertEquals(RequestStage.Idle(cancelledBefore = true), requestStage(input(fill = cancelled)))
		assertEquals(RequestStage.Searching, requestStage(input(fill = cancelled, search = SourceSearch.Searching("ed-1"))))
	}

	// ---- the index ---------------------------------------------------------- //

	@Test
	fun anIndexRowThatIsNotMissingIsInLibrary() {
		assertEquals(RequestStage.InLibrary, requestStage(input(indexed = LbRelease(rgid = "rg-1", status = "complete"))))
		assertEquals(RequestStage.InLibrary, requestStage(input(
			indexed = LbRelease(rgid = "rg-1", status = "complete"),
			fill = fill(state = "verified", outcome = "done", settled = true)
		)))
	}

	@Test
	fun anIncompleteAlbumWithAFailedFillIsFailed() {
		// B6 (audit L4b-05): a partly-owned album is not "in your library"; its failed fill must show.
		val stage = requestStage(input(
			indexed = LbRelease(rgid = "rg-1", status = "incomplete", groupId = "g1"),
			fill = fill(state = "failed", outcome = "failed", settled = true)
		))
		assertTrue(stage is RequestStage.Failed, "was $stage")
	}

	@Test
	fun anIncompleteAlbumWithARunningFillIsActive() {
		val stage = requestStage(input(indexed = LbRelease(rgid = "rg-1", status = "incomplete", groupId = "g1"), fill = fill()))
		assertTrue(stage is RequestStage.Active)
	}

	@Test
	fun anIncompleteAlbumWithNoFillIsStillInLibrary() {
		assertEquals(RequestStage.InLibrary, requestStage(input(indexed = LbRelease(rgid = "rg-1", status = "incomplete", groupId = "g1"))))
	}

	@Test
	fun aPresentAlbumIsInLibrary() {
		assertEquals(RequestStage.InLibrary, requestStage(input(
			indexed = LbRelease(rgid = "rg-1", status = "complete"),
			fill = fill(state = "failed", outcome = "failed", settled = true)
		)))
	}

	@Test
	fun aMissingAlbumFallsThrough() {
		val stage = requestStage(input(
			indexed = LbRelease(rgid = "rg-1", status = "missing"),
			fill = fill(state = "failed", outcome = "failed", settled = true)
		))
		assertTrue(stage is RequestStage.Failed)
	}

	@Test
	fun aMissingIndexRowIsNotInLibrary() {
		assertEquals(RequestStage.Idle(false), requestStage(input(indexed = LbRelease(rgid = "rg-1", status = "missing"))))
	}

	// ---- the tap and the search ------------------------------------------- //

	@Test
	fun aTapInFlightIsStarting() {
		assertEquals(RequestStage.Starting, requestStage(input(starting = true, search = SourceSearch.Found("ed-1", listOf(source())))))
	}

	@Test
	fun theSearchForThisEditionDecidesTheStage() {
		assertEquals(RequestStage.Idle(false), requestStage(input()))
		assertEquals(RequestStage.Searching, requestStage(input(search = SourceSearch.Searching("ed-1"))))
		val sources = listOf(source(), source(peer = "eve"))
		assertEquals(RequestStage.Choosing(sources, null), requestStage(input(search = SourceSearch.Found("ed-1", sources))))
		assertEquals(RequestStage.NoSources, requestStage(input(search = SourceSearch.Found("ed-1", emptyList()))))
	}

	@Test
	fun aSearchForAnotherEditionIsIdle() {
		assertEquals(RequestStage.Idle(false), requestStage(input(search = SourceSearch.Searching("ed-0"))))
		assertEquals(RequestStage.Idle(false), requestStage(input(search = SourceSearch.Found("ed-0", listOf(source())))))
	}

	@Test
	fun aFailedSearchSaysItFailedNotThatNothingWasFound() {
		val error = LbBotManager.LbError.Rejected(503, "MusicBrainz is cooling down")
		assertEquals(RequestStage.SearchFailed(error), requestStage(input(search = SourceSearch.Failed(error))))
		assertEquals(RequestStage.SearchFailed(LbBotManager.LbError.Busy), requestStage(input(search = SourceSearch.Failed(LbBotManager.LbError.Busy))))
	}

	// ---- steps ---------------------------------------------------------------- //

	@Test
	fun searchingIsTheFirstStepAndSaysFindingASource() {
		val steps = requestSteps("searching", 0, 0)
		assertEquals(listOf(StepStatus.NOW, StepStatus.PENDING, StepStatus.PENDING, StepStatus.PENDING, StepStatus.PENDING), steps.map { it.status })
		assertEquals(Res.string.request_step_finding.key, (steps[0].label as FillText.Res).res.key)
		assertEquals(Res.string.request_step_source_chosen.key, (requestSteps("queued", 0, 0)[0].label as FillText.Res).res.key)
	}

	@Test
	fun eachStateMovesTheStepsOn() {
		val d = StepStatus.DONE
		val n = StepStatus.NOW
		val p = StepStatus.PENDING
		assertEquals(listOf(d, n, p, p, p), requestSteps("queued", 0, 0).map { it.status })
		assertEquals(listOf(d, d, n, p, p), requestSteps("downloading", 4, 7).map { it.status })
		assertEquals(listOf(d, d, d, n, p), requestSteps("placing", 7, 7).map { it.status })
		assertEquals(listOf(d, d, d, d, n), requestSteps("placed", 7, 7).map { it.status })
		assertEquals(listOf(d, d, d, d, d), requestSteps("verified", 7, 7).map { it.status })
	}

	@Test
	fun downloadingCarriesItsCount() {
		val steps = requestSteps("downloading", 4, 7)
		assertEquals(FillText.Res(Res.string.request_step_count, listOf(4, 7)), steps[2].detail)
		assertTrue(steps.filterIndexed { i, _ -> i != 2 }.all { it.detail == null })
		assertNull(requestSteps("downloading", 0, 0)[2].detail)
	}

	@Test
	fun anyOtherStateHasNoSteps() {
		assertEquals(emptyList(), requestSteps("failed", 0, 0))
		assertEquals(emptyList(), requestSteps("unknown", 0, 0))
		assertEquals(emptyList(), requestSteps("needs_match", 0, 0))
	}

	// ---- preselection ---------------------------------------------------------- //

	@Test
	fun onlyAVerifiedMatchingRecommendationIsPreselected() {
		assertEquals(1, preselectedSource(listOf(source(), source(peer = "eve", recommended = true))))
		assertNull(preselectedSource(listOf(source(recommended = true, artistVerified = false))))
		assertNull(preselectedSource(listOf(source(recommended = true, albumMatchOk = false))))
		assertNull(preselectedSource(listOf(source(), source(peer = "eve"))))
		assertNull(preselectedSource(emptyList()))
	}

	// ---- actions --------------------------------------------------------------- //

	@Test
	fun actionsComeInTheBoxsOrderWithoutOpenAlbum() {
		val view = FillPresentation(
			headline = FillText.Raw(""),
			sublines = emptyList(),
			explain = null,
			progress = FillProgressKind.NONE,
			percent = 0,
			buttons = listOf(
				FillButton.DISMISS, FillButton.OPEN_ALBUM, FillButton.CANCEL, FillButton.WISHLIST,
				FillButton.ALLOW_MP3, FillButton.TRY_ANOTHER, FillButton.RETRY
			)
		)
		assertEquals(
			listOf(FillButton.RETRY, FillButton.TRY_ANOTHER, FillButton.ALLOW_MP3, FillButton.WISHLIST, FillButton.CANCEL, FillButton.DISMISS),
			requestActions(view)
		)
		assertEquals(listOf(FillButton.DISMISS), requestActions(view.copy(buttons = listOf(FillButton.OPEN_ALBUM, FillButton.DISMISS))))
	}

	@Test
	fun theChooseAnEditionLineShowsOnlyWhileTheArrivalSearchWaitsForOne() {
		assertTrue(chooseEditionLineShown(arrivalWanted = true, detailLoaded = true, editionMbid = ""))
		assertFalse(chooseEditionLineShown(arrivalWanted = true, detailLoaded = false, editionMbid = ""), "still loading")
		assertFalse(chooseEditionLineShown(arrivalWanted = true, detailLoaded = true, editionMbid = "ed-1"))
		assertFalse(chooseEditionLineShown(arrivalWanted = false, detailLoaded = true, editionMbid = ""))
	}
}

