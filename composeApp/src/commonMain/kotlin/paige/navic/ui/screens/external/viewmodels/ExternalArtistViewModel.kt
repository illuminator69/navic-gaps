package paige.navic.ui.screens.external.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbScanOutcome
import paige.navic.domain.manager.LbRelease
import paige.navic.ui.screens.artist.viewmodels.DiscographyEntry
import paige.navic.ui.screens.artist.viewmodels.DiscographySection
import paige.navic.ui.screens.artist.viewmodels.DiscographyUi

/**
 * An artist the library does not have, rendered from lb-bot alone.
 *
 * Deliberately separate from `ArtistDetailViewModel`, which loads its artist from
 * Room and errors when it is absent — keeping that screen strictly Navidrome-backed
 * is what makes its DB error path correct, and an lb-bot artist has no Navidrome
 * row by definition.
 *
 * The id form is `mb:<artist-mbid>`, which lb-bot's discography route already
 * understands: it short-circuits the library match and classifies everything
 * `missing`. So every entry here is absent, and the shelf renders that honestly
 * rather than pretending to know about ownership it never looked up.
 */
class ExternalArtistViewModel(
	private val artistMbid: String,
	private val artistName: String,
	private val lbBotManager: LbBotManager
) : ViewModel() {
	private val _discography = MutableStateFlow(DiscographyUi())
	val discography = _discography.asStateFlow()

	private val _selectedRelease = MutableStateFlow<LbRelease?>(null)
	val selectedRelease = _selectedRelease.asStateFlow()

	private val _name = MutableStateFlow(artistName)
	val name = _name.asStateFlow()

	init { load() }

	fun load() {
		if (artistMbid.isBlank()) return
		viewModelScope.launch {
			if (!lbBotManager.ensureAvailability()) {
				_discography.value = DiscographyUi(available = false)
				return@launch
			}
			val data = lbBotManager.discography("mb:$artistMbid", artistMbid)
			if (data == null) {
				_discography.value = DiscographyUi(available = false)
				return@launch
			}
			// lb-bot knows the artist's real name once it has scanned them; the name
			// carried on the route is whatever the fresh feed called them.
			data.artistName.takeIf { it.isNotBlank() }?.let { _name.value = it }
			_discography.value = DiscographyUi(
				available = true,
				indexed = data.indexed,
				scannedAt = (data.scannedAt * 1000).toLong(),
				stale = data.stale,
				sections = sections(data.releases)
			)
		}
	}

	/** Kick the MusicBrainz walk, then wait for the index to fill — the same
	 *  `scanned_at` rule the owned artist page uses, and for the same reason: a
	 *  rescan of an already-indexed artist never flips `indexed`. */
	fun indexArtist() {
		if (artistMbid.isBlank()) return
		viewModelScope.launch {
			_discography.value = _discography.value.copy(indexing = true)
			_discography.value = _discography.value.copy(scanError = "")
			val before = lbBotManager.discography("mb:$artistMbid", artistMbid)?.scannedAt ?: 0.0
			val taskId = lbBotManager.indexArtist(artistMbid, _name.value, "mb:$artistMbid")
			if (taskId == null) {
				_discography.value = _discography.value.copy(
					indexing = false,
					scanError = "lb-bot did not start the scan"
				)
				return@launch
			}
			when (val outcome = lbBotManager.awaitArtistScan("mb:$artistMbid", artistMbid, taskId, before)) {
				is LbScanOutcome.Done -> _discography.value = DiscographyUi(
					available = true,
					indexed = true,
					scannedAt = (outcome.data.scannedAt * 1000).toLong(),
					stale = outcome.data.stale,
					sections = sections(outcome.data.releases)
				)
				is LbScanOutcome.Failed -> _discography.value = _discography.value.copy(
					indexing = false,
					scanError = outcome.error
				)
				LbScanOutcome.TimedOut -> _discography.value = _discography.value.copy(indexing = false)
			}
		}
	}

	fun selectRelease(release: LbRelease?) {
		_selectedRelease.value = release
	}

	/** Group by release type, in lb-bot's own order — the same shape the owned
	 *  artist page builds, so the two groupings cannot diverge. */
	private fun sections(releases: List<LbRelease>): List<DiscographySection> =
		releases
			.map { release ->
				DiscographyEntry(
					key = release.rgid,
					title = release.title,
					year = release.year,
					album = null,
					release = release
				)
			}
			.groupBy { entry ->
				entry.release?.effectiveType?.takeIf { it.isNotBlank() }
					?: entry.release?.primaryType?.takeIf { it.isNotBlank() }
					?: "album"
			}
			.map { (type, entries) ->
				DiscographySection(type, entries.sortedByDescending { it.year })
			}

}
