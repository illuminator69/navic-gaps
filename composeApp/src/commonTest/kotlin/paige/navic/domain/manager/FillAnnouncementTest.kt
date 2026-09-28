package paige.navic.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/*
 * B-026 (Navic half): what the snackbar and the notification say about a fill that just settled,
 * and where "Open in lb-bot" goes. Mirrors Feishin's `announcementKindFor` /
 * `announcementLinkPathFor` (fill-announce-logic.ts) and its harness case for case, so the two
 * clients agree.
 *
 * A settled `failed` does not always mean nothing arrived: a stalled gap placement (folded into
 * the ledger state `needs_match` by applyGapSummary) and an album fill lb-bot reports as
 * `needs_match` both have the files sitting in lb-bot, waiting to be filed by hand.
 */
class FillAnnouncementTest {
	private fun event(
		outcome: String,
		state: String = "",
		isGap: Boolean = false,
		key: String = "k"
	) = LbFillEvent(
		key = key,
		artist = "Artist",
		album = "Album",
		outcome = outcome,
		reason = "",
		state = state,
		isGap = isGap
	)

	// --- fillAnnouncementFor ---------------------------------------------------------------

	@Test
	fun aRealFailureStillReadsCouldntGet() {
		assertEquals(
			FillAnnouncement.COULDNT_GET,
			fillAnnouncementFor(event(LbBotManager.OUTCOME_FAILED, state = "failed"))
		)
	}

	@Test
	fun aStalledGapPlacementReadsNeedsSorting() {
		// applyGapSummary folds `stalledPlacement` into the ledger state `needs_match`.
		assertEquals(
			FillAnnouncement.NEEDS_SORTING,
			fillAnnouncementFor(event(LbBotManager.OUTCOME_FAILED, state = "needs_match", isGap = true))
		)
	}

	@Test
	fun anAlbumFillInNeedsMatchReadsNeedsSorting() {
		assertEquals(
			FillAnnouncement.NEEDS_SORTING,
			fillAnnouncementFor(event(LbBotManager.OUTCOME_FAILED, state = "needs_match", isGap = false))
		)
	}

	@Test
	fun doneStaysDone() {
		assertEquals(
			FillAnnouncement.DONE,
			fillAnnouncementFor(event(LbBotManager.OUTCOME_DONE, state = "verified"))
		)
		assertEquals(
			FillAnnouncement.DONE,
			fillAnnouncementFor(event(LbBotManager.OUTCOME_DONE, state = "complete", isGap = true))
		)
	}

	@Test
	fun cancelledNeedsPickGaveUpAndRunningAnnounceNothing() {
		assertNull(fillAnnouncementFor(event(LbBotManager.OUTCOME_CANCELLED, state = "cancelled")))
		assertNull(fillAnnouncementFor(event(LbBotManager.OUTCOME_NEEDS_PICK, state = "picking", isGap = true)))
		assertNull(fillAnnouncementFor(event(LbBotManager.OUTCOME_GAVE_UP, state = "needs_match")))
		assertNull(fillAnnouncementFor(event(LbBotManager.OUTCOME_RUNNING, state = "downloading")))
	}

	// --- fillAnnouncementLinkPath ----------------------------------------------------------
	//
	// An album fill's id at needs_match is an import-record id (`rec…`/`ag…`), not a review
	// group, and lb-bot answers the gaps route for it with "Group not found". Only a gap's key is
	// a review group.

	@Test
	fun aGapLinksToItsOwnGapsPage() {
		assertEquals(
			"#/gaps/9f3a1c",
			fillAnnouncementLinkPath(event(LbBotManager.OUTCOME_FAILED, "needs_match", isGap = true, key = "9f3a1c"))
		)
	}

	@Test
	fun anAlbumFillNeverGetsAGapsLinkEvenWithAnImportRecordLookingKey() {
		assertEquals(
			"#/downloads",
			fillAnnouncementLinkPath(event(LbBotManager.OUTCOME_FAILED, "needs_match", isGap = false, key = "rec17"))
		)
		assertEquals(
			"#/downloads",
			fillAnnouncementLinkPath(event(LbBotManager.OUTCOME_FAILED, "needs_match", isGap = false, key = ""))
		)
	}

	@Test
	fun aGapWithABlankGroupIdGetsNoLink() {
		assertEquals(
			"",
			fillAnnouncementLinkPath(event(LbBotManager.OUTCOME_FAILED, "needs_match", isGap = true, key = ""))
		)
		assertEquals(
			"",
			fillAnnouncementLinkPath(event(LbBotManager.OUTCOME_FAILED, "needs_match", isGap = true, key = "  "))
		)
	}

	@Test
	fun aGroupIdThatNeedsEscapingIsEncoded() {
		assertEquals(
			"#/gaps/a%20b%2Fc",
			fillAnnouncementLinkPath(event(LbBotManager.OUTCOME_FAILED, "needs_match", isGap = true, key = "a b/c"))
		)
	}
}
