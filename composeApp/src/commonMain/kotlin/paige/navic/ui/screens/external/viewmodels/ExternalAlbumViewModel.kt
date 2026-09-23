package paige.navic.ui.screens.external.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import paige.navic.data.database.dao.ArtistDao
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbRelease
import paige.navic.domain.manager.LbReleaseDetail
import paige.navic.domain.manager.LbTracklist
import paige.navic.domain.manager.PreviewManager
import paige.navic.domain.manager.toDomainSong
import paige.navic.domain.models.SavedQueueSource
import paige.navic.shared.MediaPlayerViewModel
import kotlinx.coroutines.flow.firstOrNull

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
	val indexAddFailed: Boolean = false,
	/** Where this album's artist opens, once it is known. Null = nowhere to go. */
	val artistTarget: ArtistTarget? = null
)

/**
 * Where the artist credit on this page should lead.
 *
 * Three outcomes rather than an id, for the same reason `BrowseTarget` has three:
 * the artist may be one the library holds, one only MusicBrainz knows, or one
 * nothing here can identify — and a bare string could express only the middle
 * case. This page reached that middle case *or nothing*, which is why an album
 * arrived at from a Deezer row showed its artist's name and refused to open it.
 */
sealed interface ArtistTarget {
	/** The library's own artist page. */
	data class Library(val artistId: String) : ArtistTarget

	/** The `mb:` page — and the only route to "scan this artist's discography". */
	data class External(val artistMbid: String, val name: String) : ArtistTarget
}

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
	private val artistId: String,
	private val artistDao: ArtistDao,
	private val lbBotManager: LbBotManager,
	private val previewManager: PreviewManager,
	private val mediaPlayer: MediaPlayerViewModel
) : ViewModel() {
	private val _state = MutableStateFlow(ExternalAlbumUi())
	val state = _state.asStateFlow()

	/** Fired at most once per instance, success or failure: this runs on a page the
	 *  user is looking at, and an older hub 404ing would otherwise retry forever. */
	private var indexAddTried = false

	init { load() }

	// ---- previews ------------------------------------------------------------ //

	private val _previewsAvailable = MutableStateFlow(false)

	/** Whether to offer a listen at all. False hides the control entirely. */
	val previewsAvailable: StateFlow<Boolean> = _previewsAvailable.asStateFlow()

	private val _previewBusy = MutableStateFlow<Int?>(null)

	/** The track position currently being resolved, so one row can spin. */
	val previewBusy: StateFlow<Int?> = _previewBusy.asStateFlow()

	private val _previewMissing = MutableStateFlow<Int?>(null)

	/**
	 * A position whose resolve came back empty.
	 *
	 * "No preview found" is a legitimate answer, not an error, so it is remembered
	 * per row and shown there rather than raised as a failure — the same rule lb-bot's
	 * `strict=False` metadata chain follows.
	 */
	val previewMissing: StateFlow<Int?> = _previewMissing.asStateFlow()

	/**
	 * Hear one track of a release the library does not have.
	 *
	 * The point is deciding whether to spend an acquisition on it. So this plays
	 * ONE track, not the record: a full preview queue would be a way of listening to
	 * an album without owning it, which is a different product and not this one.
	 *
	 * Resolution is by artist and title because that is all a MusicBrainz tracklist
	 * gives; `durationMs` is deliberately not sent, since lb-bot's tracklist does not
	 * carry it and inventing a length would make the sidecar reject good matches.
	 */
	fun preview(position: Int, title: String) {
		if (_previewBusy.value != null) return
		val artist = _state.value.detail?.artist.orEmpty().ifBlank { artistName }
		val album = _state.value.detail?.title.orEmpty()
		_previewBusy.value = position
		_previewMissing.value = null
		viewModelScope.launch {
			try {
				val track = previewManager.resolve(artist = artist, title = title, album = album)
				if (track == null) {
					_previewMissing.value = position
					return@launch
				}
				// Replaces the queue rather than appending: this is a listen, not a
				// session, and quietly parking an external track at the end of
				// somebody's real queue is how it ends up playing an hour later with
				// no explanation.
				mediaPlayer.loadRemoteQueue(
					listOf(track.toDomainSong()),
					index = 0,
					positionMs = 0L,
					play = true,
					savedQueueKind = SavedQueueSource.MANUAL,
					savedQueueName = title
				)
			} finally {
				_previewBusy.value = null
			}
		}
	}

	// After the preview properties, not beside `init { load() }` above: an init block
	// placed higher runs before they are constructed and would read them as null.
	// RadioManager carries the same note for the same reason.
	init {
		viewModelScope.launch {
			_previewsAvailable.value = previewManager.ensureAvailability()
		}
	}

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
			resolveArtistTarget(detail?.artistMbid.orEmpty(), detail?.artist.orEmpty())
			refreshIndexRow()
		}
	}

	/**
	 * Decide where the artist credit leads, best answer first.
	 *
	 * The library comes before MusicBrainz, and Room comes before lb-bot, for the
	 * reason the album side of this page already learned: lb-bot's ownership marking
	 * is only as complete as its discography index, while Room holds the whole
	 * library and can answer exactly, offline, for free. An artist the user owns must
	 * open *their* page — the external one shows every album as "Added — syncing".
	 *
	 * [detailMbid] is what makes this reachable at all from a Deezer row. Deezer
	 * carries no MBIDs, so such a row arrives with neither an artist id nor an
	 * artist MBID, and until `/lb/album/releases` started returning one there was
	 * simply nothing to open — the control hid itself and the discography scan
	 * behind it was unreachable.
	 */
	private suspend fun resolveArtistTarget(detailMbid: String, detailName: String) {
		// 1. The caller already knew the library has them.
		if (artistId.isNotBlank()) {
			_state.value = _state.value.copy(artistTarget = ArtistTarget.Library(artistId))
			return
		}

		// 2. Ask Room. Both spellings: the page's own parameter is the browse row's
		//    artist ("Daft Punk") while lb-bot's is the full MusicBrainz credit
		//    ("Daft Punk feat. …"), and either may be the one the library filed.
		val names = listOf(artistName, detailName).map { it.trim() }.filter { it.isNotBlank() }
		if (names.isNotEmpty()) {
			val local = runCatching { artistDao.getArtistsByNames(names) }.getOrNull().orEmpty()
			// In the order asked, so the browse row's plainer name wins a tie.
			val match = names.firstNotNullOfOrNull { wanted ->
				local.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
			}
			if (match != null) {
				_state.value = _state.value.copy(artistTarget = ArtistTarget.Library(match.artistId))
				return
			}
		}

		// 3. MusicBrainz — the page's own parameter, else what lb-bot just answered.
		val mbid = artistMbid.ifBlank { detailMbid }
		if (mbid.isNotBlank()) {
			_state.value = _state.value.copy(
				artistTarget = ArtistTarget.External(mbid, names.firstOrNull().orEmpty())
			)
		}
		// 4. Nothing identifies them. The credit stays plain text rather than
		//    becoming a control that goes nowhere.
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
