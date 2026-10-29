package paige.navic.ui.screens.artist

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
	/** An older lb-bot, no MBID and no override to store one: a disabled button and why. */
	data object NeedsMbid : ArtistScanAction

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
	ui.indexed && ui.sections.isNotEmpty() && ui.scanError.isBlank() && !ui.scanShared -> ArtistScanAction.None
	mbid.isBlank() && !scanByNdId -> if (canOverride) ArtistScanAction.ChooseMbid else ArtistScanAction.NeedsMbid
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
