package paige.navic.ui.screens.artist

import paige.navic.domain.manager.LbArtistScan
import paige.navic.domain.manager.LbDiscography
import paige.navic.domain.manager.LbDownloads
import paige.navic.ui.screens.artist.viewmodels.DiscographyUi

/*
 * Worbus P1 (N6): what an owned artist page offers about lb-bot's scan of the artist, one function of
 * what is known, so the page is in one state at a time:
 *
 * - **indexed** (releases, nothing running or failed): nothing; the releases are the page.
 * - **unindexed**: Scan now. Since P1 also for an artist with no MusicBrainz tag: lb-bot resolves the
 *   name itself, as its background worker does. An lb-bot from before that (no `downloads` on the
 *   advert, or its 400 to such a scan) gets today's choice instead: the MusicBrainz picker where the
 *   hub can store an override, else the disabled button that says why.
 * - **scanning(done, total)**: lb-bot's own counts, any scan of the artist (the worker's too).
 * - **unresolved**: lb-bot looked and found no MusicBrainz artist: Retry, and Match manually where the
 *   hub can store an override.
 * - **waiting** (lb-bot out of reach, MusicBrainz down): today's fail-soft, i.e. nothing, or the
 *   scan's failure in lb-bot's words under the button.
 * - **updated**: a scan this page watched has just landed with releases. The slot says so in one quiet
 *   line instead of folding away in the frame the releases arrive, which at the end of the page pulled
 *   it up by the slot's height (the scroll offset clamped to the shorter page; seen on the AVD).
 */
sealed interface ArtistScanAction {
	/** The page's slot for the action: one place, whichever state it is in. */
	val slotKey: String get() = SLOT_KEY

	data object None : ArtistScanAction
	/** [rescan]: an index that holds nothing (a wrong match walked to an empty row) is scanned again. */
	data class ScanNow(val rescan: Boolean) : ArtistScanAction
	data class Scanning(val done: Int, val total: Int) : ArtistScanAction
	data class Unresolved(val matchOffered: Boolean) : ArtistScanAction
	/** An older lb-bot and no MBID: the picker, in place of a button that could only be disabled. */
	data object ChooseMbid : ArtistScanAction
	/**
	 * An older lb-bot, no MBID and no override to store one: a disabled button and why. [rescan]: the
	 * index already has releases (today's "Rescan discography" label).
	 */
	data class NeedsMbid(val rescan: Boolean) : ArtistScanAction
	/** A scan this page watched landed with releases: "Updated just now" holds the slot. */
	data object Updated : ArtistScanAction

	companion object {
		const val SLOT_KEY = "discography-scan"
	}
}

/**
 * [mbid] the effective one (override, else tag); [canOverride] the hub can store an override and the
 * page has a picker to open; [scanByNdId] lb-bot can scan an artist with no MBID ([scanByNdIdOffered]).
 */
fun artistScanAction(ui: DiscographyUi, mbid: String, canOverride: Boolean, scanByNdId: Boolean): ArtistScanAction = when {
	!ui.available -> ArtistScanAction.None
	ui.indexing -> ArtistScanAction.Scanning(ui.scanDone, ui.scanTotal)
	// Whatever the library's own albums put on the page (the shelf is built Navidrome-first, so an artist
	// lb-bot could not match still has sections): lb-bot's row holds nothing (seen on the AVD).
	ui.unresolved && ui.indexed -> ArtistScanAction.Unresolved(canOverride)
	ui.indexed && ui.sections.isNotEmpty() && ui.scanError.isBlank() && !ui.scanShared ->
		if (ui.justScanned) ArtistScanAction.Updated else ArtistScanAction.None
	mbid.isBlank() && !scanByNdId ->
		if (canOverride) ArtistScanAction.ChooseMbid
		else ArtistScanAction.NeedsMbid(rescan = ui.indexed && ui.sections.isNotEmpty())
	else -> ArtistScanAction.ScanNow(rescan = ui.indexed && ui.sections.isNotEmpty())
}

/**
 * Whether lb-bot can scan a library artist by its Navidrome id alone: it says how it downloads
 * (`downloads` on the hub's advert, which only a P1 lb-bot reports, or its 409 `downloads_off`), and it
 * has not refused such a scan with a 400. An older hub in front of a P1 lb-bot reads as older: today's
 * page, which is the safe side.
 */
fun scanByNdIdOffered(downloads: LbDownloads, refused: Boolean): Boolean =
	downloads != LbDownloads.UNKNOWN && !refused

/**
 * A discography that may be lb-bot's "no MusicBrainz match": indexed, no MBID, no releases. The index
 * mirror carries no `unresolved` flag, so the page asks lb-bot's own read once for such a row; an older
 * lb-bot answers without the flag and the page stays today's.
 */
fun discographyUnresolved(data: LbDiscography): Boolean =
	data.indexed && data.artistMbid.isBlank() && data.releases.isEmpty()

/**
 * Whether the page asks lb-bot's own read whether a mirrored row is "no match": only for data from the
 * mirror (a network read already carries `unresolved`), only from a P1 peer ([p1Peer]: an older lb-bot
 * would answer without the flag, one more read per page open for nothing), once per page.
 */
fun unresolvedReadDue(data: LbDiscography, fromNetwork: Boolean, p1Peer: Boolean, asked: Boolean): Boolean =
	!fromNetwork && p1Peer && !asked && discographyUnresolved(data)

/**
 * A scan of the artist the page did not start, running now, to follow like its own: only a record that
 * carries lb-bot's counts, i.e. a P1 lb-bot's (its worker's scans included). An older lb-bot's record of
 * another device's scan is not followed: its page shows today's action, not a spinner.
 */
fun runningScanToFollow(data: LbDiscography): LbArtistScan? =
	data.scan?.takeIf { it.state == "running" && it.taskId.isNotBlank() && it.hasCounts }
