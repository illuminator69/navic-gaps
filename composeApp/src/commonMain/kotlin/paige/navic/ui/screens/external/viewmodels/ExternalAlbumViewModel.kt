package paige.navic.ui.screens.external.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbRelease
import paige.navic.domain.manager.LbReleaseDetail
import paige.navic.domain.manager.LbTracklist

data class ExternalAlbumUi(
	val loading: Boolean = true,
	val detail: LbReleaseDetail? = null,
	val tracklist: LbTracklist? = null,
	/** The index row for this release-group, once lb-bot has one. Null while the
	 *  artist has never been scanned, or while the single-release add is in flight. */
	val indexed: LbRelease? = null,
	/** A Navidrome album id, if the library turns out to hold this release-group —
	 *  only lb-bot's index knows which album that is. */
	val ownedAlbumId: String? = null,
	/** The single-release index add could not be done: no MusicBrainz artist, an
	 *  older hub that does not proxy the route, or MusicBrainz not answering. */
	val indexAddFailed: Boolean = false
)

/**
 * A release the library does not have, keyed on its MusicBrainz release-group id.
 *
 * The interesting part is the index refresh. lb-bot serves a stored discography
 * immediately even when stale *by design*, so a release published since the last
 * scan is simply absent from it — which is every row arrived at from the Fresh
 * tab. A full rescan for one album is a MusicBrainz request per second per
 * release-group, so instead this asks lb-bot to classify and store that one row.
 *
 * Two things follow from having the row, not just tidiness: an `incomplete` result
 * carries the real `group_id` the gap-fill workspace is reached by, and a release
 * the library actually holds carries its Navidrome album ids — which is the only
 * way this page can know to send the user to the real album instead.
 */
class ExternalAlbumViewModel(
	private val rgid: String,
	private val artistMbid: String,
	private val artistName: String,
	private val lbBotManager: LbBotManager
) : ViewModel() {
	private val _state = MutableStateFlow(ExternalAlbumUi())
	val state = _state.asStateFlow()

	/** Fired at most once per instance, success or failure: this runs on a page the
	 *  user is looking at, and an older hub 404ing would otherwise retry forever. */
	private var indexAddTried = false

	init { load() }

	fun load() {
		if (rgid.isBlank()) return
		viewModelScope.launch {
			_state.value = _state.value.copy(loading = true)
			val detail = lbBotManager.albumReleases(rgid)
			// The tracklist follows the default variant. `presenceKnown` comes back
			// false with no Navidrome album ids supplied, which is the normal case
			// here: every track is missing, and that is not an error and must not
			// render as "0/12".
			val releaseMbid = detail?.releases?.firstOrNull()?.releaseMbid.orEmpty()
			val tracks = if (releaseMbid.isBlank()) null else lbBotManager.tracklist(releaseMbid)
			_state.value = _state.value.copy(loading = false, detail = detail, tracklist = tracks)
			refreshIndexRow()
		}
	}

	private suspend fun refreshIndexRow() {
		if (artistMbid.isBlank()) return
		val disco = lbBotManager.discography("mb:$artistMbid", artistMbid)
		val row = disco?.releases?.firstOrNull { it.rgid == rgid }
		_state.value = _state.value.copy(
			indexed = row,
			ownedAlbumId = row?.navidromeAlbumIds?.firstOrNull { it.isNotBlank() }
		)
		if (row != null || !disco?.indexed.let { it == true } || indexAddTried) return
		indexAddTried = true
		// The route is a hub whitelist entry; an older hub answers a plain 404, which
		// no HTTP client raises as an error. Check before pressing rather than after.
		if (!lbBotManager.supportsSingleReleaseIndex) {
			_state.value = _state.value.copy(indexAddFailed = true)
			return
		}
		// The detail this screen already loaded is lb-bot's escape hatch when
		// MusicBrainz is inside its five-minute failure cooldown for this
		// release-group: without it one 503 makes the add fail for everyone who
		// asks next, and this page already holds what the row needs.
		val detail = _state.value.detail
		val result = lbBotManager.indexRelease(
			rgid = rgid,
			mbid = artistMbid,
			ndId = "mb:$artistMbid",
			name = artistName,
			external = true,
			title = detail?.title.orEmpty(),
			artist = detail?.artist.orEmpty().ifBlank { artistName },
			type = detail?.primaryType.orEmpty(),
			year = detail?.year.orEmpty()
		)
		if (result !is LbBotManager.LbResult.Ok) {
			_state.value = _state.value.copy(indexAddFailed = true)
			return
		}
		val added = lbBotManager.discography("mb:$artistMbid", artistMbid)
			?.releases?.firstOrNull { it.rgid == rgid }
		_state.value = _state.value.copy(
			indexed = added,
			ownedAlbumId = added?.navidromeAlbumIds?.firstOrNull { it.isNotBlank() }
		)
	}

	/**
	 * The index row if there is one, else a synthetic `missing` row.
	 *
	 * The picker only reads `rgid`, `title` and `status` off it, and everything real
	 * about the release comes from lb-bot inside the sheet — so a release the index
	 * has never heard of is still fully actionable rather than a dead page.
	 */
	fun releaseForPicker(fallbackTitle: String): LbRelease =
		_state.value.indexed ?: LbRelease(
			rgid = rgid,
			title = _state.value.detail?.title?.ifBlank { null } ?: fallbackTitle,
			status = "missing"
		)
}
