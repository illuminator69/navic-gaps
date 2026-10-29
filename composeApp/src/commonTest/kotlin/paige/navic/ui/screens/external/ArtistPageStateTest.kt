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
		assertEquals(ArtistScanAction.NeedsMbid, owned(DiscographyUi(available = true), canOverride = false, byNdId = false))
		assertEquals(ArtistScanAction.ScanNow(rescan = false), owned(DiscographyUi(available = true), mbid = "mb-1", byNdId = false))
		// The refusal that says so.
		assertTrue(scanByNdIdRefused(LbBotManager.LbError.Rejected(400, "mbid is required")))
		assertFalse(scanByNdIdRefused(LbBotManager.LbError.Rejected(409, "busy")))
		assertFalse(scanByNdIdRefused(LbBotManager.LbError.Unreachable("")))
		// A scan record from an older lb-bot has no counts: 0 of 0, a spinner.
		val scan = lbWireJson.decodeFromString(LbArtistScan.serializer(), """{"state":"running","taskId":"t"}""")
		assertEquals(0, scan.total)
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
	fun completion_keeps_scroll_key() {
		// The scan's slot moves Scan now -> Scanning -> nothing, in place: when the index lands the
		// action folds away in the same frame the releases arrive above it (the page's scroll offset is
		// the view model's and nothing above the reader is replaced), and a rescan of an artist with
		// releases never takes them off the page.
		val sections = listOf(
			DiscographySection("album", listOf(DiscographyEntry(key = "rg-1", title = "After", year = "2020", release = LbRelease(rgid = "rg-1"))))
		)
		val steps = listOf(
			DiscographyUi(available = true),
			DiscographyUi(available = true, indexing = true),
			DiscographyUi(available = true, indexing = true, scanDone = 5, scanTotal = 10),
			DiscographyUi(available = true, indexed = true, sections = sections)
		).map { owned(it) }
		assertEquals(
			listOf(
				ArtistScanAction.ScanNow(rescan = false), ArtistScanAction.Scanning(0, 0),
				ArtistScanAction.Scanning(5, 10), ArtistScanAction.None
			),
			steps
		)
		assertTrue(steps.all { it.slotKey == ArtistScanAction.SLOT_KEY })
	}
}
