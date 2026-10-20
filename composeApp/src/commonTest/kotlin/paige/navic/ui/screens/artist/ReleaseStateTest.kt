package paige.navic.ui.screens.artist

import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.info_pending_sync
import navic.composeapp.generated.resources.lbbot_fail_no_source
import navic.composeapp.generated.resources.lbbot_fill_downloading
import navic.composeapp.generated.resources.lbbot_fill_gave_up
import navic.composeapp.generated.resources.lbbot_fill_needs_match
import navic.composeapp.generated.resources.lbbot_fill_needs_pick
import navic.composeapp.generated.resources.lbbot_fill_verified
import navic.composeapp.generated.resources.release_missing
import navic.composeapp.generated.resources.release_owned
import navic.composeapp.generated.resources.release_partly_owned
import org.jetbrains.compose.resources.StringResource
import paige.navic.domain.manager.LbFillEntry
import paige.navic.domain.manager.LbRelease
import paige.navic.domain.models.DomainAlbum
import paige.navic.ui.components.common.FillText
import paige.navic.ui.components.common.Pill
import paige.navic.ui.components.common.PillTone
import paige.navic.ui.screens.artist.viewmodels.DiscographyEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/*
 * P4: what one discography row says. Owned iff Room holds the album, Partly owned iff the index
 * row is `incomplete` with a group id, Added, syncing iff the index says present and Room has
 * none, Missing otherwise. A running fill is the news and beats every base state; a settled one
 * says what became of it, except a `done` fill on an album Room holds and a `cancelled` one.
 */
class ReleaseStateTest {
	private fun album() = DomainAlbum(
		id = "al-1", name = "After", artistName = "A", artistId = "a", year = null, coverArtId = null,
		genre = null, genres = emptyList(), songCount = 7, duration = null, createdAt = Instant.fromEpochSeconds(0),
		starredAt = null, lastPlayedAt = null, userRating = null, version = null, musicBrainzId = null,
		songs = emptyList()
	)

	private fun entry(owned: Boolean, status: String = "missing", groupId: String? = null) = DiscographyEntry(
		key = "rg-1",
		title = "After",
		year = "2020",
		album = if (owned) album() else null,
		release = LbRelease(
			rgid = "rg-1",
			title = "After",
			status = status,
			groupId = groupId,
			present = if (groupId != null) 4 else null,
			total = if (groupId != null) 7 else null
		)
	)

	private fun fill(
		state: String = "downloading",
		outcome: String = "running",
		settled: Boolean = false,
		failureKind: String = "",
		isGap: Boolean = false,
		key: String = "rg-1"
	) = LbFillEntry(
		key = key,
		isGap = isGap,
		rgid = if (isGap) "" else "rg-1",
		groupId = if (isGap) key else "",
		releaseMbid = "rel-1",
		artist = "A",
		album = "After",
		quality = "",
		state = state,
		outcome = outcome,
		reason = "",
		percent = 57,
		done = 4,
		total = 7,
		mp3WouldHelp = false,
		failureKind = failureKind,
		retryable = false,
		attempts = 1,
		settled = settled,
		startedAt = 0L,
		finishedAt = 0L
	)

	private fun assertPill(res: StringResource, tone: PillTone, pill: Pill) {
		assertEquals(res.key, (pill.label as FillText.Res).res.key)
		assertEquals(tone, pill.tone)
	}

	@Test
	fun anOwnedAlbumWithNoGapIsOwned() {
		assertPill(Res.string.release_owned, PillTone.OK, releaseState(entry(owned = true, status = "complete"), null))
	}

	@Test
	fun anOwnedAlbumWithAGapGroupIsPartlyOwned() {
		val e = entry(owned = true, status = "incomplete", groupId = "g-1")
		assertPill(Res.string.release_partly_owned, PillTone.WARN, releaseState(e, null))
	}

	@Test
	fun noAlbumAndAMissingRowIsMissing() {
		assertPill(Res.string.release_missing, PillTone.MUTE, releaseState(entry(owned = false), null))
	}

	@Test
	fun noAlbumAndAPresentRowIsAddedSyncing() {
		assertPill(Res.string.info_pending_sync, PillTone.RUN, releaseState(entry(owned = false, status = "complete"), null))
	}

	@Test
	fun aRunningFillOnAMissingReleaseShowsItsOwnHeadline() {
		val pill = releaseState(entry(owned = false), fill())
		assertEquals(FillText.Res(Res.string.lbbot_fill_downloading, listOf(4, 7)), pill.label)
		assertEquals(PillTone.RUN, pill.tone)
	}

	@Test
	fun aFailedFillOnAMissingReleaseShowsTheFailure() {
		val pill = releaseState(
			entry(owned = false),
			fill(state = "failed", outcome = "failed", settled = true, failureKind = "no_source")
		)
		assertPill(Res.string.lbbot_fail_no_source, PillTone.BAD, pill)
	}

	@Test
	fun aDoneFillOnAnOwnedAlbumChangesNothing() {
		val pill = releaseState(
			entry(owned = true, status = "complete"),
			fill(state = "verified", outcome = "done", settled = true)
		)
		assertPill(Res.string.release_owned, PillTone.OK, pill)
	}

	@Test
	fun aDoneFillTheIndexHasNotCaughtUpWithSaysInYourLibrary() {
		val pill = releaseState(entry(owned = false), fill(state = "verified", outcome = "done", settled = true))
		assertPill(Res.string.lbbot_fill_verified, PillTone.GOOD, pill)
	}

	@Test
	fun aCancelledFillIsIgnored() {
		val pill = releaseState(entry(owned = false), fill(state = "cancelled", outcome = "cancelled", settled = true))
		assertPill(Res.string.release_missing, PillTone.MUTE, pill)
	}

	@Test
	fun aFillThatGaveUpIsGaveUp() {
		val pill = releaseState(entry(owned = false), fill(state = "searching", outcome = "gave_up", settled = true))
		assertPill(Res.string.lbbot_fill_gave_up, PillTone.MUTE, pill)
	}

	@Test
	fun aFillWaitingOnAPickOrAMatchWantsTheUser() {
		assertPill(
			Res.string.lbbot_fill_needs_pick, PillTone.WARN,
			releaseState(entry(owned = false), fill(state = "searching", outcome = "needs_pick", settled = true))
		)
		assertPill(
			Res.string.lbbot_fill_needs_match, PillTone.WARN,
			releaseState(entry(owned = false), fill(state = "needs_match", outcome = "failed", settled = true))
		)
	}

	@Test
	fun aRunningGapFillOnAPartlyOwnedAlbumShowsItsHeadline() {
		val e = entry(owned = true, status = "incomplete", groupId = "g-1")
		val pill = releaseState(e, fill(isGap = true, key = "g-1"))
		assertEquals(FillText.Res(Res.string.lbbot_fill_downloading, listOf(4, 7)), pill.label)
		assertEquals(PillTone.RUN, pill.tone)
	}
}
