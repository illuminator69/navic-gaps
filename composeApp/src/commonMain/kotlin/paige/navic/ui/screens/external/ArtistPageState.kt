package paige.navic.ui.screens.external

import paige.navic.ui.screens.artist.viewmodels.DiscographyUi

/*
 * navi-connect P4: what the unowned artist page is showing, one function of the discography state.
 * A scan that failed, a scan still running and "lb-bot has no releases" are three states with three
 * sets of words; none of them reads as an empty list. The page scans an artist lb-bot has never
 * walked as soon as it arrives (call 7), so a never-indexed page is Scanning, never NoReleases.
 */
sealed interface ArtistPageState {
	data object Loading : ArtistPageState
	/** lb-bot out of reach, or this hub without it: nothing to read. */
	data object Unreachable : ArtistPageState
	data object Scanning : ArtistPageState
	/** [error] is lb-bot's own sentence. */
	data class ScanFailed(val error: String) : ArtistPageState
	data object NoReleases : ArtistPageState
	data object Releases : ArtistPageState
}

fun artistPageState(ui: DiscographyUi, loading: Boolean): ArtistPageState = when {
	loading -> ArtistPageState.Loading
	!ui.available -> ArtistPageState.Unreachable
	// Stale sections, if any, stay under the pill while the scan runs.
	ui.indexing -> ArtistPageState.Scanning
	ui.scanError.isNotBlank() && (!ui.indexed || ui.sections.isEmpty()) -> ArtistPageState.ScanFailed(ui.scanError)
	ui.sections.isNotEmpty() -> ArtistPageState.Releases
	// Nothing to scan from here: another library artist holds this discography.
	ui.indexed || ui.scanShared -> ArtistPageState.NoReleases
	// Never walked, nothing running, nothing failed: the page is starting the scan.
	else -> ArtistPageState.Scanning
}
