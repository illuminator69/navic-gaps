package paige.navic.ui.screens.external.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import paige.navic.data.database.dao.AlbumDao
import paige.navic.data.database.dao.ArtistDao
import paige.navic.domain.repositories.DbRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import paige.navic.domain.manager.AllFilesNote
import paige.navic.domain.manager.ConnectivityManager
import paige.navic.domain.manager.FillActions
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbCachePolicy
import paige.navic.domain.manager.LbGapSource
import paige.navic.domain.manager.LbMeta
import paige.navic.domain.manager.LbOk
import paige.navic.domain.manager.LbResolvedEdition
import paige.navic.domain.manager.allFilesNote
import paige.navic.domain.manager.LbIndexSync
import paige.navic.domain.manager.LbMirroredRelease
import paige.navic.domain.manager.LbRelease
import paige.navic.domain.manager.LbReleaseDetail
import paige.navic.domain.manager.LbTracklist
import paige.navic.domain.manager.PreviewManager
import paige.navic.domain.manager.toDomainSong
import paige.navic.domain.models.SavedQueueSource
import paige.navic.shared.MediaPlayerViewModel
import paige.navic.ui.components.common.FillButton
import paige.navic.ui.screens.external.SourceSearch
import paige.navic.ui.screens.external.preselectedSource
import paige.navic.util.Logger

/**
 * Whether opening an album page starts its source search by itself (P4, call 1: it does not). A
 * search is a live slskd fan-out of 30 to 90 s holding one of the hub's four proxy slots, and
 * browsing five albums would hold five; so it is one tap in the request box. True reverses it.
 */
const val SOURCES_AUTO_START = false

/**
 * P6: whether the search Statistics' "Find sources" asked for starts now: asked, not yet done, the
 * edition known, nothing searching or filling for it already, and a hub that carries the route.
 */
internal fun arrivalSearchShouldStart(
	wanted: Boolean,
	done: Boolean,
	editionKnown: Boolean,
	searching: Boolean,
	fillRunning: Boolean,
	supported: Boolean
): Boolean = wanted && !done && editionKnown && !searching && !fillRunning && supported

/**
 * The request box's own state (P4): what used to live in `MissingAlbumSheet`'s `remember`s, moved
 * here so the box on the album page carries it and the page's tracklist and the box agree on the
 * edition. Which stage the box shows is `requestStage`'s, from this and the fill ledger.
 */
data class RequestUi(
	val search: SourceSearch = SourceSearch.Idle,
	/** The source card the user (or the preselection) chose. */
	val selected: Int? = null,
	/** A request tap whose answer has not come back. Taken in the tap itself. */
	val starting: Boolean = false,
	/** lb-bot answered `existing`: this album was already being fetched (another device, maybe). */
	val alreadyRunning: Boolean = false,
	/** What lb-bot's answer said of a "Download all" (B-084); nothing until one is asked for. */
	val allFilesNote: AllFilesNote = AllFilesNote.None,
	/** Why the last request or fill action failed. */
	val error: LbBotManager.LbError? = null,
	/** lb-bot took the action's request but did not act on it, and said nothing. */
	val refused: Boolean = false,
	/** A fill action (retry, cancel, allow MP3, wishlist) in flight. */
	val acting: Boolean = false,
	/** The sticky quality preference ("" = lb-bot's default). */
	val quality: String = "",
	/** The version (a different tracklist) and the edition (the same tracklist on other media). */
	val variantIndex: Int = 0,
	val editionMbid: String = "",
	/** Whether this release-group is on lb-bot's wishlist; null when unknown or unsupported. */
	val wishlisted: Boolean? = null,
	/** Statistics' Find sources asked for a search on arrival that has not started yet. */
	val arrivalWanted: Boolean = false
)

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
	val artistTarget: ArtistTarget? = null,
	/**
	 * The title and artist as lb-bot's index mirror has them — read from Room before any
	 * request leaves, so the header is right in the first frame even when the route carried
	 * only an id. [detail] supersedes both once `/lb/album/releases` answers.
	 */
	val mirrorTitle: String = "",
	val mirrorArtist: String = "",
	val request: RequestUi = RequestUi(),
	/** lb-bot's About for the release-group (`/lb/meta/album`); null when it has none. */
	val meta: LbMeta? = null,
	val aboutOpen: Boolean = false
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
	/** The title the route carried: the last fallback for the release's name. */
	private val title: String,
	private val artistDao: ArtistDao,
	private val albumDao: AlbumDao,
	private val dbRepository: DbRepository,
	private val lbBotManager: LbBotManager,
	private val lbIndexSync: LbIndexSync,
	private val previewManager: PreviewManager,
	private val mediaPlayer: MediaPlayerViewModel,
	private val fillActions: FillActions,
	private val connectivityManager: ConnectivityManager
) : ViewModel() {
	private val _state = MutableStateFlow(ExternalAlbumUi(request = RequestUi(quality = lbBotManager.preferredQuality)))
	val state = _state.asStateFlow()

	private fun updateRequest(change: (RequestUi) -> RequestUi) {
		_state.value = _state.value.copy(request = change(_state.value.request))
	}

	/** Fired at most once per instance, success or failure: this runs on a page the
	 *  user is looking at, and an older hub 404ing would otherwise retry forever. */
	private var indexAddTried = false

	// Above `init`, which calls load(): a property initialiser placed after it would run
	// afterwards and null out the job load() had just stored.
	private var loadJob: Job? = null
	private var tracklistJob: Job? = null
	private var metaJob: Job? = null
	/** The edition the shown tracklist belongs to: its size is that edition's track count only. */
	private var tracklistFor: String = ""

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

	/*
	 * P4 round 3, the owner's note: a request that finished while this page was open ended on
	 * "In your library" with nowhere to go. The page read the index mirror once, at open, so the
	 * Navidrome album lb-bot names after the landing never reached it, and Room (which the album
	 * page reads) may not have had the album yet either. Once this release-group's fill has
	 * landed, the page looks again on every library bump and every [LANDING_POLL_MS], for
	 * [LANDING_FOLLOW_MS]; when the mirror names the album it is pulled into Room if the landing
	 * sync has not done so, and `ownedAlbumId` moves the page to it, as an owned release-group
	 * always did.
	 */
	private var landingJob: Job? = null

	init {
		viewModelScope.launch {
			lbBotManager.ledger
				.map { rows ->
					rows.firstOrNull { it.key == rgid }
						?.let { it.settled && it.outcome == LbBotManager.OUTCOME_DONE } == true
				}
				.distinctUntilChanged()
				.collect { landed -> if (landed) followLanding() }
		}
	}

	private fun followLanding() {
		if (landingJob?.isActive == true || _state.value.ownedAlbumId != null) return
		landingJob = viewModelScope.launch {
			withTimeoutOrNull(LANDING_FOLLOW_MS) {
				while (_state.value.ownedAlbumId == null) {
					resolveLandedAlbum()
					if (_state.value.ownedAlbumId != null) break
					withTimeoutOrNull(LANDING_POLL_MS) { lbBotManager.libraryBumps.first() }
				}
			}
		}
	}

	private suspend fun resolveLandedAlbum() {
		val found = runCatching { lbIndexSync.releasesByRgid(rgid, preferArtistKey = artistMbid) }
			.getOrNull()?.firstOrNull() ?: return
		val albumId = found.release.navidromeAlbumIds.firstOrNull { it.isNotBlank() } ?: return
		if (albumDao.getAlbumById(albumId) == null) {
			dbRepository.syncAlbumsById(listOf(albumId))
				.onSuccess { lbBotManager.onLocalLibrarySynced(listOf(albumId)) }
				.onFailure { Logger.w("ExternalAlbumViewModel", "landed album $albumId not pulled yet", it) }
			if (albumDao.getAlbumById(albumId) == null) return
		}
		mirrored = found
		Logger.i("ExternalAlbumViewModel", "request landed: $rgid is album $albumId")
		_state.value = _state.value.copy(indexed = found.release, ownedAlbumId = albumId)
	}

	/**
	 * The mirror's answer for this release-group, if it has one: resolved once, before the
	 * network, and reused by [resolveArtistTarget] and [refreshIndexRow].
	 */
	private var mirrored: LbMirroredRelease? = null

	fun load() {
		if (rgid.isBlank()) return
		loadJob?.cancel()
		loadJob = viewModelScope.launch {
			_state.value = _state.value.copy(loading = true)
			applyMirror()
			// Both reads are stale-while-revalidate over `lb_response_cache` (a month: they are
			// MusicBrainz data), so a release-group opened before paints from Room with no
			// network at all. The first emission is the cached body when there is one; a second
			// arrives only if the revalidation changed something.
			var resolved = false
			lbBotManager.albumReleases(rgid).collect { detail ->
				followEdition(detail)
				// The tracklist follows the edition the request box is on. `presenceKnown` comes
				// back false with no Navidrome album ids supplied, which is the normal case here:
				// every track is missing, and that is not an error and must not render as "0/12".
				val edition = _state.value.request.editionMbid
				val tracks = when {
					edition.isBlank() -> null
					edition != tracklistFor -> followTracklist(edition)
					else -> _state.value.tracklist
				}
				_state.value = _state.value.copy(loading = false, detail = detail, tracklist = tracks)
				// Once: both only need the artist and the rgid, which a revalidated detail
				// does not change — and the index-row add must not be pressed twice.
				if (!resolved) {
					resolved = true
					loadMeta(detail?.releases?.firstOrNull()?.releaseMbid)
					loadWishlisted()
					resolveArtistTarget(detail?.artistMbid.orEmpty(), detail?.artist.orEmpty())
					refreshIndexRow()
					if (SOURCES_AUTO_START && lbBotManager.supportsSourcePicker) findSources()
					startArrivalSearchIfDue()
				}
			}
		}
	}

	/**
	 * The edition the box and the tracklist are on, kept across a revalidated answer.
	 *
	 * Moved verbatim from the missing-album sheet, with the reasons it paid for: only the first
	 * answer picks the edition, and after that the variant follows the edition by MBID, never by
	 * position.
	 */
	private fun followEdition(detail: LbReleaseDetail?) {
		val variants = detail?.releases.orEmpty()
		val editionMbid = _state.value.request.editionMbid
		// Only the first answer picks the edition: a revalidation must not move a pick
		// the user has already made (or that the tracklist below is already following).
		if (editionMbid.isBlank()) {
			updateRequest { it.copy(variantIndex = 0, editionMbid = variants.firstOrNull()?.releaseMbid.orEmpty()) }
			return
		}
		// But the variant is held by *position*, and a revalidated list need not be in the
		// same order, or the same length. Left alone, `chosenEdition()` paired the kept
		// edition with whatever variant now sat at the old index — the wrong title and track
		// count sent to `albumSources` and `download`, silently. So the index follows the
		// edition. An edition the new answer no longer lists at all is a pick that no longer
		// exists: fall back to the first variant, as the first answer did, rather than send
		// an edition next to a variant it does not belong to.
		val found = variants.indexOfFirst { variant ->
			variant.releaseMbid == editionMbid ||
				variant.editions.any { it.releaseMbid == editionMbid }
		}
		if (found >= 0) {
			updateRequest { it.copy(variantIndex = found) }
		} else {
			updateRequest { it.copy(variantIndex = 0, editionMbid = variants.firstOrNull()?.releaseMbid.orEmpty()) }
		}
	}

	/**
	 * The tracklist's first answer (the cached one, when there is one), returned so the page
	 * paints detail and tracks together as it always has; any revalidated answer after it is
	 * applied on its own. One follower at a time — a new edition replaces the last. The tracklist
	 * is per *edition*: two pressings of one release-group can differ, which is the whole reason
	 * the picker exists.
	 */
	private suspend fun followTracklist(releaseMbid: String): LbTracklist? {
		tracklistJob?.cancel()
		val first = CompletableDeferred<LbTracklist?>()
		tracklistJob = viewModelScope.launch {
			lbBotManager.tracklist(releaseMbid).collect { tracks ->
				if (!first.complete(tracks)) {
					_state.value = _state.value.copy(tracklist = tracks)
				}
			}
			first.complete(null)
		}
		val tracks = first.await()
		tracklistFor = releaseMbid
		return tracks
	}

	// ---- the request box ------------------------------------------------------ //

	/** The release's name as lb-bot's index, then its detail, then the mirror, then the route have it. */
	private fun releaseTitle(): String {
		val ui = _state.value
		return ui.indexed?.title?.ifBlank { null }
			?: ui.detail?.title?.ifBlank { null }
			?: ui.mirrorTitle.ifBlank { null }
			?: title
	}

	// The pressing the pickers have already settled on. Sending it stops lb-bot re-resolving
	// the release-group to "official, earliest" — which both ignored the user's choice silently and
	// dragged the five-minute MusicBrainz failure cooldown into a button press.
	private fun chosenEdition(): LbResolvedEdition? {
		val request = _state.value.request
		val mbid = request.editionMbid.ifBlank { return null }
		val detail = _state.value.detail
		val variant = detail?.releases?.getOrNull(request.variantIndex)
		val tracklist = _state.value.tracklist.takeIf { tracklistFor == mbid }
		return LbResolvedEdition(
			releaseMbid = mbid,
			artist = detail?.artist.orEmpty(),
			title = variant?.title?.ifBlank { null } ?: releaseTitle(),
			// The loaded tracklist is for this exact edition; the variant's count is for the
			// variant, so it is the fallback rather than the source of truth.
			totalTracks = tracklist?.tracks?.size?.takeIf { it > 0 } ?: variant?.trackCount ?: 0
		)
	}

	/**
	 * Pick a version or an edition. The page's tracklist follows it, and sources found for the
	 * previous edition stop being selectable (`requestStage` reads a search for another edition as
	 * idle); what is shown stays until the new tracklist lands.
	 */
	fun selectEdition(variantIndex: Int, editionMbid: String) {
		if (editionMbid.isBlank() || editionMbid == _state.value.request.editionMbid) return
		updateRequest {
			val kept = it.search as? SourceSearch.Found
			it.copy(
				variantIndex = variantIndex,
				editionMbid = editionMbid,
				// Back on the edition the kept answer is for: its preselection with it.
				selected = kept?.takeIf { found -> found.editionMbid == editionMbid }?.let { found -> preselectedSource(found.sources) },
				error = null,
				refused = false
			)
		}
		viewModelScope.launch {
			val tracks = followTracklist(editionMbid)
			if (_state.value.request.editionMbid == editionMbid) {
				_state.value = _state.value.copy(tracklist = tracks)
			}
		}
	}

	/**
	 * Search Soulseek for this edition, once per tap. A live slskd fan-out, tens of seconds; the
	 * hub caches it briefly, so asking again soon is cheap. An answer that comes back for an
	 * edition no longer on screen is dropped: its sources are for the other pressing.
	 */
	/** P6: Statistics' "Find sources" asked for the search on arrival; [startArrivalSearchIfDue] runs it once. */
	private var arrivalSearchWanted = false
	private var arrivalSearchDone = false

	/**
	 * P6: Statistics' "Find sources" on a played album the library lacks. The tap is the consent the
	 * search's cost asks for (P4), so the page starts the one search itself, once, when the edition
	 * is known; never over a fill or a search already running for it.
	 */
	fun findSourcesOnArrival() {
		arrivalSearchWanted = true
		updateRequest { it.copy(arrivalWanted = true) }
		startArrivalSearchIfDue()
	}

	private fun startArrivalSearchIfDue() {
		val request = _state.value.request
		if (!arrivalSearchShouldStart(
				wanted = arrivalSearchWanted,
				done = arrivalSearchDone,
				editionKnown = _state.value.detail != null && request.editionMbid.isNotBlank(),
				searching = request.search is SourceSearch.Searching,
				fillRunning = lbBotManager.isFilling(rgid),
				supported = lbBotManager.supportsSourcePicker
			)
		) return
		arrivalSearchDone = true
		updateRequest { it.copy(arrivalWanted = false) }
		findSources()
	}

	fun findSources() {
		val request = _state.value.request
		val edition = request.editionMbid
		val search = request.search
		if (search is SourceSearch.Searching && search.editionMbid == edition) return
		val asked = chosenEdition()
		updateRequest { it.copy(search = SourceSearch.Searching(edition), selected = null, error = null, refused = false) }
		viewModelScope.launch {
			val result = lbBotManager.albumSources(rgid, asked)
			if (_state.value.request.editionMbid != edition) {
				updateRequest { if (it.search == SourceSearch.Searching(edition)) it.copy(search = SourceSearch.Idle) else it }
				return@launch
			}
			when (result) {
				is LbBotManager.LbResult.Ok -> {
					val sources = result.value.sources
					updateRequest { it.copy(search = SourceSearch.Found(edition, sources), selected = preselectedSource(sources)) }
				}
				is LbBotManager.LbResult.Failed -> updateRequest { it.copy(search = SourceSearch.Failed(result.error)) }
			}
		}
	}

	fun selectSource(index: Int) {
		updateRequest { it.copy(selected = index) }
	}

	/** The quality preference. Sticky: someone who wants CD-quality FLAC wants it every time,
	 *  and re-picking per album is a chore nobody does twice. */
	fun setQuality(quality: String) {
		lbBotManager.preferredQuality = quality
		updateRequest { it.copy(quality = quality) }
	}

	/**
	 * Ask lb-bot for this album from [source]. [allFiles] is the selected card's second action
	 * (B-084): the same request, plus "every audio file in this folder". What comes of it is read
	 * off lb-bot's echo, never off the tap.
	 */
	fun request(source: LbGapSource?, allFiles: Boolean = false) {
		// Taken in the tap itself, not inside the coroutine: a card's two actions both stay
		// live until the next frame, and a second tap in that time must start nothing.
		if (_state.value.request.starting) return
		// A request that is already running (from here, another device, before a restart) is
		// the box's Active stage; a POST now would be a second request for it.
		if (lbBotManager.ledger.value.any { it.key == rgid && !it.settled }) return
		updateRequest { it.copy(starting = true, error = null, refused = false, allFilesNote = AllFilesNote.None) }
		val quality = _state.value.request.quality
		val edition = chosenEdition()
		val artist = _state.value.detail?.artist.orEmpty()
		val album = releaseTitle()
		viewModelScope.launch {
			// Not fast even with a source chosen: lb-bot resolves the release-group
			// against MusicBrainz before answering.
			when (val result = lbBotManager.download(rgid, quality, source, edition, allFiles = allFiles)) {
				is LbBotManager.LbResult.Failed -> updateRequest { it.copy(error = result.error) }
				is LbBotManager.LbResult.Ok -> {
					updateRequest {
						it.copy(
							alreadyRunning = result.value.existing,
							allFilesNote = allFilesNote(
								asked = allFiles,
								ok = result.value.ok,
								existing = result.value.existing,
								echo = result.value.allFiles,
								peer = source?.peer.orEmpty(),
								fileCount = source?.fileCount ?: 0
							)
						)
					}
					if (result.value.ok && result.value.releaseMbid.isNotBlank()) {
						// Artist and title are carried into the watch, not looked up
						// later: the downloads view may be opened days afterwards, on a
						// screen with no artist page behind it, and by then lb-bot's own
						// (in-memory, capped) ledger may have forgotten the fill entirely.
						lbBotManager.startAlbumFill(
							rgid = rgid,
							releaseMbid = result.value.releaseMbid,
							quality = quality,
							artist = artist,
							album = album,
							// Completes the edition snapshot the watch keeps, so Retry can
							// re-request THIS pressing instead of letting lb-bot re-resolve.
							totalTracks = edition?.totalTracks ?: 0,
							source = source
						)
					}
				}
			}
			updateRequest { it.copy(starting = false) }
		}
	}

	/**
	 * One of the box's recovery rows, through the same [FillActions] the Downloads page uses. Run
	 * to completion whether or not the page is still there: the request is the user's decision,
	 * not the page's (the Downloads page learned it with a Cancel tapped just before backing out).
	 */
	fun fillAction(button: FillButton) {
		val entry = lbBotManager.ledger.value.firstOrNull { it.key == rgid } ?: return
		if (_state.value.request.acting) return
		updateRequest { it.copy(acting = true, error = null, refused = false) }
		viewModelScope.launch {
			withContext(NonCancellable) {
				var error: LbBotManager.LbError? = null
				var refused = false
				fun read(result: LbBotManager.LbResult<LbOk>?) {
					when (result) {
						null -> refused = true
						is LbBotManager.LbResult.Failed -> error = result.error
						is LbBotManager.LbResult.Ok -> when {
							result.value.error.isNotBlank() -> error = LbBotManager.LbError.Rejected(200, result.value.error)
							!result.value.ok -> refused = true
						}
					}
				}
				when (button) {
					FillButton.RETRY -> read(fillActions.retry(entry.key))
					FillButton.TRY_ANOTHER -> read(fillActions.retryAnotherSource(entry.key))
					FillButton.CANCEL -> read(fillActions.cancel(entry.key))
					FillButton.ALLOW_MP3 -> read(fillActions.allowMp3AndRetry(entry))
					FillButton.WISHLIST -> {
						if (fillActions.addToWishlist(entry)) updateRequest { it.copy(wishlisted = true) }
						else refused = true
					}
					FillButton.DISMISS -> fillActions.dismiss(entry.key)
					FillButton.OPEN_ALBUM -> Unit
				}
				updateRequest { it.copy(acting = false, error = error, refused = refused) }
			}
		}
	}

	/** Whether this release-group is on the wishlist: one read when the page opens, if the hub carries it. */
	private fun loadWishlisted() {
		if (!lbBotManager.isConfigured || !lbBotManager.supportsWishlist) return
		viewModelScope.launch {
			val listed = lbBotManager.wishlist().any { it.rgid == rgid }
			updateRequest { it.copy(wishlisted = listed) }
		}
	}

	private var wishlistBusy = false

	/** Add to or take off lb-bot's wishlist (per release-group; there is no artist wishlist). */
	fun toggleWishlist() {
		val listed = _state.value.request.wishlisted ?: return
		if (wishlistBusy) return
		wishlistBusy = true
		val artist = _state.value.detail?.artist.orEmpty().ifBlank { _state.value.mirrorArtist.ifBlank { artistName } }
		val album = releaseTitle()
		viewModelScope.launch {
			withContext(NonCancellable) {
				val list = if (listed) lbBotManager.wishlistRemove(rgid) else lbBotManager.wishlistAdd(rgid, artist, album)
				// Null is a write that failed: the button stays as it was.
				if (list != null) updateRequest { it.copy(wishlisted = list.any { item -> item.rgid == rgid }) }
				wishlistBusy = false
			}
		}
	}

	// ---- the About ------------------------------------------------------------- //

	/**
	 * lb-bot's About for the release-group, the one an unowned album can have (the owner asked for
	 * it, 2026-10-20). The cached article first, then, once online, `albumMeta`; [releaseMbid] (the
	 * first answer's first release) saves lb-bot two MusicBrainz seconds on a cold call. An answer
	 * replaces what is shown only if it has something, or nothing is shown: a failure never
	 * blanks a good article.
	 */
	private fun loadMeta(releaseMbid: String?) {
		metaJob?.cancel()
		metaJob = viewModelScope.launch {
			if (!lbBotManager.isConfigured) return@launch
			lbBotManager.albumMeta(rgid, releaseMbid, LbCachePolicy.CACHE_ONLY)
				.collect { cached -> if (cached != null) _state.value = _state.value.copy(meta = cached) }
			val online = connectivityManager.isOnline
			if (!online.value) online.first { it }
			if (!lbBotManager.ensureAvailability()) return@launch
			lbBotManager.albumMeta(rgid, releaseMbid).collect { meta ->
				if (meta != null || _state.value.meta == null) _state.value = _state.value.copy(meta = meta)
			}
		}
	}

	fun openAbout() {
		_state.value = _state.value.copy(aboutOpen = true)
	}

	fun dismissAbout() {
		_state.value = _state.value.copy(aboutOpen = false)
	}

	/**
	 * The index row, title and artist straight out of lb-bot's index mirror — a Room read, so
	 * it lands before `/lb/album/releases`, and offline too.
	 *
	 * The row is what this page otherwise had to ask the network for after the detail and the
	 * tracklist: whether the library holds this release-group (its Navidrome album ids — the
	 * screen then redirects to the owned album straight away), and the `incomplete` row's gap
	 * handle. The artist row behind it names lb-bot's artist, which [resolveArtistTarget] uses.
	 * A release-group filed under several artists prefers the one this page was opened for.
	 */
	private suspend fun applyMirror() {
		if (!lbBotManager.isConfigured) return
		val found = runCatching { lbIndexSync.releasesByRgid(rgid, preferArtistKey = artistMbid) }
			.onFailure { Logger.w("ExternalAlbumViewModel", "index mirror read failed", it) }
			.getOrNull()
			?.firstOrNull()
			?: return
		mirrored = found
		_state.value = _state.value.copy(
			indexed = found.release,
			ownedAlbumId = found.release.navidromeAlbumIds.firstOrNull { it.isNotBlank() },
			mirrorTitle = found.release.title,
			mirrorArtist = found.artist?.name.orEmpty()
		)
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

		// 1b. lb-bot's index files this release-group under an artist it matched to a
		//     Navidrome id — the library's own id, no name matching involved. Only when Room
		//     has that artist: a featured artist can carry an id with no page (§5).
		val mirrorArtist = mirrored?.artist
		val mirrorNdId = mirrorArtist?.ndArtistId.orEmpty()
		if (mirrorNdId.isNotBlank() && !mirrorNdId.startsWith("mb:")) {
			val local = runCatching { artistDao.getArtistById(mirrorNdId) }.getOrNull()
			if (local != null) {
				_state.value = _state.value.copy(artistTarget = ArtistTarget.Library(local.artistId))
				return
			}
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

		// 3. MusicBrainz — the page's own parameter, else what lb-bot just answered, else
		//    the mirror's artist.
		val mbid = artistMbid.ifBlank { detailMbid }.ifBlank { mirrorArtist?.mbid.orEmpty() }
		if (mbid.isNotBlank()) {
			_state.value = _state.value.copy(
				artistTarget = ArtistTarget.External(mbid, names.firstOrNull().orEmpty())
			)
		}
		// 4. Nothing identifies them. The credit stays plain text rather than
		//    becoming a control that goes nowhere.
	}

	private suspend fun refreshIndexRow() {
		// The mirror already answered: that IS the index row, and it is kept current by the
		// feed, so the discography read (and the single-release add, which only runs for a
		// row the index lacks) has nothing to add.
		if (mirrored != null) return
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
}

/** How long a landed request keeps looking for its album in the library. */
private const val LANDING_FOLLOW_MS = 180_000L

/** How often it looks between library bumps. */
private const val LANDING_POLL_MS = 5_000L
