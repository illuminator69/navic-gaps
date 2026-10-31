package paige.navic.ui.screens.external

import paige.navic.domain.manager.LbArtistScan
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbDiscography
import paige.navic.domain.manager.LbRelease
import paige.navic.domain.manager.LbRescanAnswer
import paige.navic.domain.manager.LbRescanStart
import paige.navic.domain.manager.rescanStartOf
import paige.navic.domain.manager.lbWireJson
import paige.navic.domain.manager.scanByNdIdRefused
import paige.navic.domain.manager.scanPollDelayMs
import paige.navic.ui.screens.artist.runningScanToFollow
import paige.navic.ui.screens.artist.unresolvedReadDue
import paige.navic.ui.screens.artist.ArtistScanAction
import paige.navic.ui.screens.artist.artistScanAction
import paige.navic.ui.screens.artist.discographyUnresolved
import paige.navic.ui.screens.artist.viewmodels.DiscographyEntry
import paige.navic.ui.screens.artist.viewmodels.DiscographySection
import paige.navic.ui.screens.artist.viewmodels.DiscographyUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * P4: the unowned artist page says what it is doing. A failed scan, a running scan and an artist
 * with no releases are three states; and since the page scans a never-walked artist on arrival, a
 * never-indexed page with nothing running and no error is Scanning (the page is starting it), never
 * "no releases".
 */
class ArtistPageStateTest {
	private val sections = listOf(
		DiscographySection(
			"album",
			listOf(DiscographyEntry(key = "rg-1", title = "After", year = "2020", release = LbRelease(rgid = "rg-1")))
		)
	)

	@Test
	fun loadingIsLoading() {
		assertEquals(ArtistPageState.Loading, artistPageState(DiscographyUi(available = true, indexed = true, sections = sections), loading = true))
	}

	@Test
	fun unavailableIsUnreachable() {
		assertEquals(ArtistPageState.Unreachable, artistPageState(DiscographyUi(available = false), loading = false))
	}

	@Test
	fun indexingIsScanningEvenWithStaleSections() {
		assertEquals(ArtistPageState.Scanning, artistPageState(DiscographyUi(available = true, indexing = true), loading = false))
		assertEquals(
			ArtistPageState.Scanning,
			artistPageState(DiscographyUi(available = true, indexed = true, indexing = true, sections = sections), loading = false)
		)
	}

	@Test
	fun aFailedScanWithNothingIndexedSaysSo() {
		val ui = DiscographyUi(available = true, scanError = "MusicBrainz did not answer (HTTP 503)")
		assertEquals(ArtistPageState.ScanFailed("MusicBrainz did not answer (HTTP 503)"), artistPageState(ui, loading = false))
	}

	@Test
	fun indexedWithNoSectionsHasNoReleases() {
		assertEquals(ArtistPageState.NoReleases, artistPageState(DiscographyUi(available = true, indexed = true), loading = false))
	}

	@Test
	fun indexedWithSectionsHasReleases() {
		assertEquals(ArtistPageState.Releases, artistPageState(DiscographyUi(available = true, indexed = true, sections = sections), loading = false))
	}

	@Test
	fun aSharedDiscographyWithSectionsHasReleases() {
		val ui = DiscographyUi(available = true, indexed = true, scanShared = true, sections = sections)
		assertEquals(ArtistPageState.Releases, artistPageState(ui, loading = false))
	}

	@Test
	fun aRescanThatFailedKeepsTheReleasesItHad() {
		val ui = DiscographyUi(available = true, indexed = true, scanError = "MusicBrainz did not answer", sections = sections)
		assertEquals(ArtistPageState.Releases, artistPageState(ui, loading = false))
	}

	@Test
	fun aNeverIndexedPageIsScanningNotEmpty() {
		assertEquals(ArtistPageState.Scanning, artistPageState(DiscographyUi(available = true), loading = false))
	}

	// ---- Worbus P1 (N6): the owned artist page's scan, without an MBID, with counts, and no match ---- //

	private fun owned(ui: DiscographyUi, mbid: String = "", canOverride: Boolean = true, byNdId: Boolean = true) =
		artistScanAction(ui, mbid = mbid, canOverride = canOverride, scanByNdId = byNdId)

	@Test
	fun unindexed_offers_scan_now_without_mbid() {
		// lb-bot has no row for this artist and Navidrome has no MusicBrainz tag: Scan now, by its
		// Navidrome id alone (lb-bot resolves the name itself).
		assertEquals(ArtistScanAction.ScanNow(rescan = false), owned(DiscographyUi(available = true)))
		// With a tag, as before.
		assertEquals(ArtistScanAction.ScanNow(rescan = false), owned(DiscographyUi(available = true), mbid = "mb-1"))
		// Nothing at all while lb-bot is out of reach (today's fail-soft).
		assertEquals(ArtistScanAction.None, owned(DiscographyUi(available = false)))
	}

	@Test
	fun scanning_shows_counts() {
		// Right after Scan now lb-bot says 0 of 0: a spinner with no count yet.
		assertEquals(ArtistScanAction.Scanning(done = 0, total = 0), owned(DiscographyUi(available = true, indexing = true)))
		assertEquals(
			ArtistScanAction.Scanning(done = 3, total = 10),
			owned(DiscographyUi(available = true, indexing = true, scanDone = 3, scanTotal = 10))
		)
		// The counts ride lb-bot's scan record, the background worker's included.
		val scan = lbWireJson.decodeFromString(
			LbArtistScan.serializer(),
			"""{"state":"running","error":"","taskId":"auto-index","startedAt":1.5,"finishedAt":0,"done":3,"total":10}"""
		)
		assertEquals(3, scan.done)
		assertEquals(10, scan.total)
	}

	@Test
	fun unresolved_offers_retry_and_match() {
		val ui = DiscographyUi(available = true, indexed = true, unresolved = true)
		assertEquals(ArtistScanAction.Unresolved(matchOffered = true), owned(ui))
		// Match manually needs a hub that can store an override.
		assertEquals(ArtistScanAction.Unresolved(matchOffered = false), owned(ui, canOverride = false))
		// The library's own albums still make sections (the shelf is Navidrome-first): still no match.
		val librarySections = listOf(DiscographySection("album", listOf(DiscographyEntry(key = "nd-1", title = "Mine", year = "2001"))))
		assertEquals(ArtistScanAction.Unresolved(matchOffered = true), owned(ui.copy(sections = librarySections)))
		// lb-bot's flag, read as sent.
		val data = lbWireJson.decodeFromString(
			LbDiscography.serializer(), """{"indexed":true,"artist_mbid":"","releases":[],"unresolved":true}"""
		)
		assertTrue(data.unresolved)
		// The mirror carries no flag: a row with no MBID and no releases is worth asking lb-bot about.
		assertTrue(discographyUnresolved(LbDiscography(indexed = true, artistMbid = "")))
		assertFalse(discographyUnresolved(LbDiscography(indexed = true, artistMbid = "mb-1")))
		assertFalse(discographyUnresolved(LbDiscography(indexed = true, artistMbid = "", releases = listOf(LbRelease(rgid = "r")))))
		assertFalse(discographyUnresolved(LbDiscography(indexed = false)))
	}

	@Test
	fun older_peer_no_unresolved_reads_as_today() {
		// No `unresolved` on the wire: false, and an indexed artist with no releases is today's page.
		val data = lbWireJson.decodeFromString(LbDiscography.serializer(), """{"indexed":true,"releases":[]}""")
		assertFalse(data.unresolved)
		assertEquals(ArtistPageState.NoReleases, artistPageState(DiscographyUi(available = true, indexed = true), loading = false))
		assertEquals(ArtistScanAction.ScanNow(rescan = false), owned(DiscographyUi(available = true, indexed = true), mbid = "mb-1"))
		// An lb-bot that cannot scan by Navidrome id (no `downloads` on the advert, or its 400
		// "mbid is required"): with no MBID, today's choice — the picker where the hub can store one,
		// else the disabled button that says why.
		assertEquals(ArtistScanAction.ChooseMbid, owned(DiscographyUi(available = true), byNdId = false))
		assertEquals(ArtistScanAction.NeedsMbid(rescan = false), owned(DiscographyUi(available = true), canOverride = false, byNdId = false))
		assertEquals(ArtistScanAction.ScanNow(rescan = false), owned(DiscographyUi(available = true), mbid = "mb-1", byNdId = false))
		// The refusal that says so.
		assertTrue(scanByNdIdRefused(LbBotManager.LbError.Rejected(400, "mbid is required")))
		assertFalse(scanByNdIdRefused(LbBotManager.LbError.Rejected(409, "busy")))
		assertFalse(scanByNdIdRefused(LbBotManager.LbError.Unreachable("")))
		// A scan record from an older lb-bot has no counts.
		val scan = lbWireJson.decodeFromString(LbArtistScan.serializer(), """{"state":"running","taskId":"t"}""")
		assertEquals(null, scan.total)
		assertFalse(scan.hasCounts)
		// Scan now answered 400 for a POST with no MBID is an older lb-bot; with an MBID it is a refusal.
		val refused = LbBotManager.LbError.Rejected(400, "mbid is required")
		assertEquals(LbRescanStart.Older, rescanStartOf(answer = null, error = refused, sentMbid = false))
		assertEquals(LbRescanStart.NotStarted, rescanStartOf(answer = null, error = refused, sentMbid = true))
		assertEquals(
			LbRescanStart.Started("auto-index"),
			rescanStartOf(answer = LbRescanAnswer(ok = true, taskId = "auto-index", running = true), error = null, sentMbid = false)
		)
	}

	@Test
	fun completion_holds_the_slot_with_a_freshness_line() {
		// The scan's slot moves Scan now -> Scanning -> "Updated just now", in place. It does not fold
		// away in the frame the releases land: at the end of the page that shortened the page under the
		// reader and the scroll offset clamped (the page jumped ~228 px on the AVD). The slot draws the
		// line at the height the scan had (DiscographyScanAction).
		val sections = listOf(
			DiscographySection("album", listOf(DiscographyEntry(key = "rg-1", title = "After", year = "2020", release = LbRelease(rgid = "rg-1"))))
		)
		val steps = listOf(
			DiscographyUi(available = true),
			DiscographyUi(available = true, indexing = true),
			DiscographyUi(available = true, indexing = true, scanDone = 5, scanTotal = 10),
			DiscographyUi(available = true, indexed = true, sections = sections, justScanned = true)
		).map { owned(it) }
		assertEquals(
			listOf(
				ArtistScanAction.ScanNow(rescan = false), ArtistScanAction.Scanning(0, 0),
				ArtistScanAction.Scanning(5, 10), ArtistScanAction.Updated
			),
			steps
		)
		assertTrue(steps.none { it == ArtistScanAction.None })
		// A page opened on an artist already indexed (no scan watched here) has no slot, as today.
		assertEquals(ArtistScanAction.None, owned(DiscographyUi(available = true, indexed = true, sections = sections)))
		// A scan that lands as "no match" says so, not "Updated".
		assertEquals(
			ArtistScanAction.Unresolved(matchOffered = true),
			owned(DiscographyUi(available = true, indexed = true, unresolved = true, justScanned = true))
		)
	}

	@Test
	fun older_peer_choose_and_needs_mbid_read_as_today() {
		val sections = listOf(
			DiscographySection("album", listOf(DiscographyEntry(key = "rg-1", title = "After", year = "2020", release = LbRelease(rgid = "rg-1"))))
		)
		// Today's "Rescan discography" on the disabled button once there are releases (with a failed
		// rescan to show), "Scan discography" before.
		val failed = DiscographyUi(available = true, indexed = true, sections = sections, scanError = "MusicBrainz did not answer")
		assertEquals(ArtistScanAction.NeedsMbid(rescan = true), owned(failed, canOverride = false, byNdId = false))
		assertEquals(ArtistScanAction.NeedsMbid(rescan = false), owned(DiscographyUi(available = true), canOverride = false, byNdId = false))
	}

	@Test
	fun older_peer_scan_poll_waits_15_s_every_time() {
		// Today's cadence: 15 s (or the `artistScanned` frame) before the first read and every one after,
		// whatever an older lb-bot's record says, its own scans running included.
		assertEquals(15_000L, scanPollDelayMs(first = true, p1Peer = false, last = null))
		val olderRunning = lbWireJson.decodeFromString(LbArtistScan.serializer(), """{"state":"running","taskId":"t"}""")
		assertEquals(15_000L, scanPollDelayMs(first = false, p1Peer = false, last = olderRunning))
		assertEquals(15_000L, scanPollDelayMs(first = false, p1Peer = true, last = olderRunning))
		// A P1 lb-bot: the first read at once, then 3 s while its record (with counts) says running.
		val p1Running = lbWireJson.decodeFromString(
			LbArtistScan.serializer(), """{"state":"running","taskId":"t","done":0,"total":0}"""
		)
		assertEquals(0L, scanPollDelayMs(first = true, p1Peer = true, last = null))
		assertEquals(3_000L, scanPollDelayMs(first = false, p1Peer = true, last = p1Running))
		assertEquals(15_000L, scanPollDelayMs(first = false, p1Peer = true, last = p1Running.copy(state = "done")))
		assertEquals(15_000L, scanPollDelayMs(first = false, p1Peer = true, last = null))
	}

	@Test
	fun older_peer_no_extra_unresolved_read() {
		val looksUnresolved = LbDiscography(indexed = true, artistMbid = "")
		// An older peer: the mirror row is today's page, with no extra read.
		assertFalse(unresolvedReadDue(looksUnresolved, fromNetwork = false, p1Peer = false, asked = false))
		// Data from lb-bot's own read already carries the flag: never asked again.
		assertFalse(unresolvedReadDue(looksUnresolved, fromNetwork = true, p1Peer = true, asked = false))
		// Once per page.
		assertFalse(unresolvedReadDue(looksUnresolved, fromNetwork = false, p1Peer = true, asked = true))
		// A P1 peer's mirror row that looks like "no match": asked.
		assertTrue(unresolvedReadDue(looksUnresolved, fromNetwork = false, p1Peer = true, asked = false))
		assertFalse(unresolvedReadDue(LbDiscography(indexed = true, artistMbid = "mb"), fromNetwork = false, p1Peer = true, asked = false))
	}

	@Test
	fun older_peer_no_follow_of_a_foreign_scan() {
		// An older lb-bot's record of a scan this page did not start (another device's): not followed,
		// so the page shows today's action and no spinner.
		val older = lbWireJson.decodeFromString(
			LbDiscography.serializer(), """{"indexed":false,"scan":{"state":"running","taskId":"t-other"}}"""
		)
		assertEquals(null, runningScanToFollow(older))
		// A P1 lb-bot's (its worker's included) is followed, with its counts.
		val p1 = lbWireJson.decodeFromString(
			LbDiscography.serializer(), """{"indexed":false,"scan":{"state":"running","taskId":"auto-index","done":3,"total":40}}"""
		)
		assertEquals("auto-index", runningScanToFollow(p1)?.taskId)
		// Nothing running, or no task: nothing to follow.
		val done = lbWireJson.decodeFromString(
			LbDiscography.serializer(), """{"indexed":true,"scan":{"state":"done","taskId":"t","done":40,"total":40}}"""
		)
		assertEquals(null, runningScanToFollow(done))
	}

	@Test
	fun only_mbid_is_required_reads_as_older() {
		assertTrue(scanByNdIdRefused(LbBotManager.LbError.Rejected(400, "mbid is required")))
		assertTrue(scanByNdIdRefused(LbBotManager.LbError.Rejected(400, " MBID is required ")))
		// Any other 400 is a scan that did not start, not an older lb-bot.
		assertFalse(scanByNdIdRefused(LbBotManager.LbError.Rejected(400, "nd_id is not a library artist")))
		assertFalse(scanByNdIdRefused(LbBotManager.LbError.Rejected(400, "")))
		assertEquals(
			LbRescanStart.NotStarted,
			rescanStartOf(answer = null, error = LbBotManager.LbError.Rejected(400, "bad request"), sentMbid = false)
		)
	}
}
