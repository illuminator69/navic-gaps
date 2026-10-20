package paige.navic.ui.screens.collection.viewmodels

import androidx.compose.foundation.lazy.LazyListState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import paige.navic.domain.manager.LbGapSource
import paige.navic.domain.manager.LbSourceFiles
import paige.navic.ui.screens.collection.GapView
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.notice_deleted_download
import navic.composeapp.generated.resources.notice_download_started
import navic.composeapp.generated.resources.notice_removed_from_playlist
import paige.navic.data.database.entities.DownloadStatus
import paige.navic.data.database.mappers.toDomainModel
import paige.navic.domain.manager.ConnectivityManager
import paige.navic.domain.manager.DownloadManager
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbIndexSync
import paige.navic.domain.manager.LbMeta
import paige.navic.domain.manager.aboutAfterRefresh
import paige.navic.domain.manager.aboutRefreshArgs
import paige.navic.domain.manager.aboutRefreshNotice
import paige.navic.domain.manager.aboutRefreshOutcome
import paige.navic.ui.components.sheets.aboutRefreshMessage
import paige.navic.domain.manager.LbSimilarAlbums
import paige.navic.domain.manager.SessionManager
import paige.navic.domain.manager.SnackBarManager
import paige.navic.domain.models.DomainAlbum
import paige.navic.domain.models.DomainAlbumInfo
import paige.navic.domain.models.DomainSong
import paige.navic.domain.models.DomainSongCollection
import paige.navic.domain.repositories.AlbumRepository
import paige.navic.domain.repositories.CollectionRepository
import paige.navic.domain.repositories.SongRepository
import paige.navic.ui.core.UiState
import paige.navic.util.Logger
import kotlinx.coroutines.Dispatchers

class CollectionDetailViewModel(
	private val collectionId: String,
	private val repository: CollectionRepository,
	private val songRepository: SongRepository,
	private val albumRepository: AlbumRepository,
	private val downloadManager: DownloadManager,
	private val sessionManager: SessionManager,
	private val snackBarManager: SnackBarManager,
	private val lbBotManager: LbBotManager,
	private val lbIndexSync: LbIndexSync,
	connectivityManager: ConnectivityManager
) : ViewModel() {
	// Starts empty and is filled by [refreshCollection]'s first emission, which comes off
	// `getCollectionFlow` on Dispatchers.IO within a frame or two.
	//
	// This used to be seeded with `runBlocking { repository.getLocalData(collectionId) }`. A new
	// instance of this ViewModel is built for every album or playlist opened, so that Room relation
	// query ran synchronously on the thread composing the screen — i.e. squarely inside the enter
	// transition, which is exactly when the stutter showed. The cached data it was fetching arrives
	// from the flow below anyway.
	private val _collectionState =
		MutableStateFlow<UiState<DomainSongCollection>>(UiState.Loading())
	val collectionState: StateFlow<UiState<DomainSongCollection>> = _collectionState.asStateFlow()

	/**
	 * Room does not hold this page's id (any more): a full sync pruned the album, or a retag
	 * gave it a new id and the dead row was dropped (I-022). The screen uses it to follow the
	 * album to where a finished job says it went (`albumLeave`).
	 */
	val missing: StateFlow<Boolean>
		field = MutableStateFlow(false)

	val starred: StateFlow<Boolean>
		field = MutableStateFlow(false)

	val selectedSong: StateFlow<DomainSong?>
		field = MutableStateFlow(null)

	val albumInfoState: StateFlow<UiState<DomainAlbumInfo>>
		field = MutableStateFlow<UiState<DomainAlbumInfo>>(UiState.Loading())

	val selectedSongIsStarred: StateFlow<Boolean>
		field = MutableStateFlow(false)

	val selectedSongRating: StateFlow<Int>
		field = MutableStateFlow(0)

	val selectedAlbum: StateFlow<DomainAlbum?>
		field = MutableStateFlow(null)

	val selectedAlbumIsStarred: StateFlow<Boolean>
		field = MutableStateFlow(false)

	val selectedAlbumRating: StateFlow<Int>
		field = MutableStateFlow(0)

	val rating: StateFlow<Int>
		field = MutableStateFlow(0)

	val listState = LazyListState()

	val isOnline = connectivityManager.isOnline

	val allDownloads = downloadManager.allDownloads
		.stateIn(
			scope = viewModelScope,
			started = SharingStarted.Lazily,
			initialValue = emptyList()
		)

	// Derived from the collection rather than read off it once at construction: the state is empty
	// at that point now that the blocking seed above is gone, so a one-shot read would always have
	// resolved to an empty list.
	@OptIn(ExperimentalCoroutinesApi::class)
	val otherAlbums: StateFlow<List<DomainAlbum>> = _collectionState
		.map { it.data as? DomainAlbum }
		.distinctUntilChangedBy { album -> album?.artistId to album?.id }
		.flatMapLatest { album ->
			if (album == null) flowOf(emptyList())
			else repository.getOtherAlbums(album.artistId, album.id)
		}
		.stateIn(
			scope = viewModelScope,
			started = SharingStarted.Lazily,
			initialValue = emptyList()
		)

	init {
		viewModelScope.launch {
			sessionManager.isLoggedIn.collect { if (it) refreshCollection(false) }
		}
	}

	/**
	 * lb-bot's editorial "About" for this record — the first album description
	 * this app has ever shown. Null while in flight and null forever when lb-bot
	 * is absent, in which case nothing renders (§7).
	 *
	 * [DomainAlbumInfo.notes] remains the fallback: it has been loaded on every
	 * album page since forever and read by nothing at all.
	 */
	private val _meta = MutableStateFlow<LbMeta?>(null)
	val meta = _meta.asStateFlow()

	/** "Similar albums" — records already in the library by similar artists. The
	 *  first shelf this screen has ever had. */
	private val _similar = MutableStateFlow<LbSimilarAlbums?>(null)
	val similar = _similar.asStateFlow()

	/** The library's own albums for [similar]'s rows, by album id, read in one Room query: their
	 *  covers, and the album sheet a long press opens (as on the "More by" row). */
	private val _similarAlbums = MutableStateFlow<Map<String, DomainAlbum>>(emptyMap())
	val similarAlbums = _similarAlbums.asStateFlow()

	private val _aboutOpen = MutableStateFlow(false)
	val aboutOpen = _aboutOpen.asStateFlow()

	/**
	 * The gap lb-bot's index records for this album (P4): the Fill-gaps group and how many tracks
	 * are missing, from the first mirrored release that names this album and is `incomplete`. Null
	 * with lb-bot off, with no mirror row, or for a complete album: no banner, and no gap where it
	 * would be.
	 */
	private val _gap = MutableStateFlow<AlbumGap?>(null)
	val gap = _gap.asStateFlow()

	/** The gaps box (P4 round 2): where the user has it, and what a tap is waiting on. */
	private val _gapBox = MutableStateFlow(GapBoxUi())
	val gapBox = _gapBox.asStateFlow()

	/** lb-bot's record for this album's gap group, read when the group is known; then watched. */
	private var gapRecordLoaded = false

	private val gapGroupId: String? get() = _gap.value?.groupId?.ifBlank { null }

	fun setGapView(view: GapView) {
		_gapBox.update { it.copy(view = view, error = null) }
	}

	/** The header's tap: fold and unfold. */
	fun toggleGap() {
		_gapBox.update { it.copy(view = if (it.view == GapView.FOLDED) GapView.TRACKS else GapView.FOLDED, error = null) }
	}

	/**
	 * "Fill gaps": the same box turns to the sources for the missing tracks, asking for them unless
	 * lb-bot already holds some (or is already looking).
	 */
	fun openGapSources() {
		val groupId = gapGroupId ?: return
		_gapBox.update { it.copy(view = GapView.SOURCES, error = null) }
		val gap = lbBotManager.gaps.value[groupId]
		val searching = gap?.sourceTask?.status.orEmpty() in LbBotManager.SEARCH_IN_FLIGHT
		if (gap == null || (gap.sources.isEmpty() && !searching)) searchGap()
	}

	/** Ask lb-bot to search; [GapBoxUi.searchAsked] covers the time until its record says so. */
	fun searchGap() {
		val groupId = gapGroupId ?: return
		_gapBox.update { it.copy(view = GapView.SOURCES, searchAsked = true, selected = null, error = null) }
		viewModelScope.launch {
			val result = lbBotManager.gapSearch(groupId)
			if (result is LbBotManager.LbResult.Failed) _gapBox.update { it.copy(error = result.error) }
			else lbBotManager.refreshGap(groupId)
			_gapBox.update { it.copy(searchAsked = false) }
		}
	}

	/** A card chosen. A tap also fetches its folder listing (the poll carries none); a preselection does not. */
	fun selectGapSource(index: Int, fetchListing: Boolean) {
		val groupId = gapGroupId ?: return
		_gapBox.update { it.copy(selected = index) }
		if (!fetchListing) return
		val source = lbBotManager.gaps.value[groupId]?.sources?.getOrNull(index) ?: return
		if (source.id in _gapBox.value.listings) return
		viewModelScope.launch {
			(lbBotManager.gapSourceFiles(groupId, source.id) as? LbBotManager.LbResult.Ok)?.let { ok ->
				_gapBox.update { it.copy(listings = it.listings + (source.id to ok.value)) }
			}
		}
	}

	/**
	 * Request the missing tracks from [source]. Held as committed until lb-bot's status shows the
	 * transfer, or for 30 s if it never does, so a fetch that quietly did not take gives the box back.
	 */
	fun requestGap(source: LbGapSource) {
		val groupId = gapGroupId ?: return
		_gapBox.update { it.copy(working = true, error = null) }
		viewModelScope.launch {
			when (val result = lbBotManager.gapFetch(groupId, source.id)) {
				is LbBotManager.LbResult.Failed -> _gapBox.update { it.copy(working = false, error = result.error) }
				is LbBotManager.LbResult.Ok -> {
					_gapBox.update { it.copy(working = false, committed = true, fetched = source) }
					withTimeoutOrNull(GAP_COMMIT_RELEASE_MS) {
						lbBotManager.gaps.first { it[groupId]?.status == "downloading" }
					}
					_gapBox.update { it.copy(committed = false) }
				}
			}
		}
	}

	/** "Pick the best source for me": lb-bot walks the same ranked list. */
	fun autoGap() {
		val groupId = gapGroupId ?: return
		_gapBox.update { it.copy(working = true, error = null) }
		viewModelScope.launch {
			val result = lbBotManager.gapAuto(groupId)
			_gapBox.update {
				if (result is LbBotManager.LbResult.Failed) it.copy(working = false, error = result.error)
				else it.copy(working = false, committed = true)
			}
			if (result is LbBotManager.LbResult.Ok) {
				withTimeoutOrNull(GAP_COMMIT_RELEASE_MS) {
					lbBotManager.gaps.first { it[groupId]?.status == "downloading" }
				}
				_gapBox.update { it.copy(committed = false) }
			}
		}
	}

	fun cancelGap() = gapAct(after = { it.copy(view = GapView.TRACKS, fetched = null, committed = false) }) {
		lbBotManager.gapCancel(it)
	}

	/** Also accept MP3 for this album, then search again with it. */
	fun allowMp3ForGap() {
		val groupId = gapGroupId ?: return
		_gapBox.update { it.copy(working = true, error = null) }
		viewModelScope.launch {
			val result = lbBotManager.allowMp3(groupId)
			_gapBox.update { it.copy(working = false, error = (result as? LbBotManager.LbResult.Failed)?.error) }
			if (result is LbBotManager.LbResult.Ok) searchGap()
		}
	}

	/** Re-check the album against Navidrome and its folder: files put there by hand count too. */
	fun recheckGap() = gapAct { lbBotManager.gapRescan(it) }

	/** A failed or waiting fill, put away: the ledger row goes and the box folds to its line. */
	fun dismissGapFill() {
		val groupId = gapGroupId ?: return
		lbBotManager.dismiss(groupId)
		_gapBox.update { it.copy(view = GapView.FOLDED, error = null) }
	}

	private fun gapAct(
		after: (GapBoxUi) -> GapBoxUi = { it },
		block: suspend (String) -> LbBotManager.LbResult<*>
	) {
		val groupId = gapGroupId ?: return
		_gapBox.update { it.copy(working = true, error = null) }
		viewModelScope.launch {
			val result = block(groupId)
			_gapBox.update { ui ->
				val settled = ui.copy(working = false)
				if (result is LbBotManager.LbResult.Failed) settled.copy(error = result.error) else after(settled)
			}
		}
	}

	/**
	 * Read lb-bot's record once the group is known. lb-bot serialises every route behind one lock, so
	 * a read during someone's search times out as "busy": absorbed twice, quietly, since nothing was
	 * pressed. A fill already running (here before a restart, or on another client) is watched.
	 */
	private fun loadGapRecord(groupId: String) {
		if (gapRecordLoaded) return
		gapRecordLoaded = true
		viewModelScope.launch {
			var attempt = 0
			while (true) {
				val result = lbBotManager.refreshGap(groupId)
				if (result !is LbBotManager.LbResult.Failed) break
				if (result.error !is LbBotManager.LbError.Busy || attempt >= 2) break
				attempt++
				delay(1_500L)
			}
			if (lbBotManager.gaps.value[groupId]?.status == "downloading") lbBotManager.startGapWatch(groupId)
		}
	}

	fun openAbout() {
		_aboutOpen.value = true
	}

	fun dismissAbout() {
		_aboutOpen.value = false
	}

	/** The release-group and release the About was read for; null until they are known. */
	private var aboutKey: Pair<String, String?>? = null
	private var metaJob: Job? = null

	/** Whether "Refresh About" can be offered: only once the album's release-group is known. */
	private val _aboutRefreshable = MutableStateFlow(false)
	val aboutRefreshable = _aboutRefreshable.asStateFlow()

	/**
	 * "Refresh About" (I-020): have lb-bot look the album's About up again, and say what came
	 * of it. As on the artist page (`aboutRefreshOutcome`): only an answer with content replaces
	 * what is shown, `found: false` leaves an About that has content where it is, an identical
	 * answer says so, and a failure says which it was and changes nothing.
	 */
	fun refreshAbout() {
		val (rgid, releaseMbid) = aboutKey ?: return
		viewModelScope.launch {
			val refresh = lbBotManager.refreshAlbumMeta(rgid, releaseMbid)
			// Against what is shown NOW: an ordinary read may have landed while this was out.
			val shown = _meta.value
			val outcome = aboutRefreshOutcome(shown, refresh)
			val next = aboutAfterRefresh(shown, outcome)
			if (next != shown) {
				// An ordinary read still in flight must not land its older answer on top.
				metaJob?.cancel()
				_meta.value = next
			}
			snackBarManager.notify(
				aboutRefreshMessage(aboutRefreshNotice(outcome)),
				*aboutRefreshArgs(outcome).toTypedArray()
			)
		}
	}

	/**
	 * Fire both lb-bot reads once the album is on screen. Strictly after the page
	 * has its own data, and never on the critical render path.
	 *
	 * **`getAlbumInfo2.musicBrainzId` is the RELEASE, not the release-group.** It
	 * was being passed straight to `/lb/meta/album`, which is keyed by the
	 * release-group — so every owned album asked lb-bot about an id that does not
	 * name a release-group, got an empty answer, and rendered the Navidrome notes
	 * with no article, no attribution and no way out. Verified on Obscured by
	 * Clouds: Navidrome says `d077cc98…` (release), lb-bot holds a six-paragraph
	 * article under `a6f0826e…` (release-group). Nothing errored; the answer was
	 * just always empty, which is indistinguishable from "nobody wrote about this".
	 *
	 * So the release-group is resolved from lb-bot's own discography index, which
	 * already maps release-groups to the Navidrome album ids they landed as. The
	 * release MBID still rides along as `release_mbid` — that is what it is, and
	 * it saves lb-bot resolving the canonical release itself.
	 *
	 * **That index is local now.** The rgid comes out of the index mirror (Room), so
	 * the two serial hops that used to stand in front of both requests — a
	 * `/lb/status` probe, then the artist's whole discography over the network just
	 * to find one row — are gone, and the two requests go out together. The mirror's
	 * row also carries the artist's MBID, which similarity is keyed by. The network
	 * path is kept for an album whose artist the mirror does not hold (yet).
	 */
	fun loadLbBotExtras(album: DomainAlbum, releaseMbid: String?) {
		viewModelScope.launch {
			// A Room read, so it is re-read with the album: a fill that completes the album flips
			// the mirrored row and the banner goes with it.
			_gap.value = if (!lbBotManager.isConfigured) null else runCatching {
				lbIndexSync.releasesForAlbum(collectionId, album.artistId)
					.firstOrNull { it.release.isIncomplete }
					?.release
					?.let { row ->
						AlbumGap(
							groupId = row.groupId.orEmpty(),
							missing = ((row.total ?: 0) - (row.present ?: 0)).coerceAtLeast(0)
						)
					}
					?.takeIf { it.missing > 0 }
			}.getOrNull()
				// Once the page has a gap it keeps the group for the visit: a fill that completes the
				// album flips the mirrored row, and the box still owes its result row before it goes.
				?: _gap.value?.copy(missing = 0)
			_gap.value?.groupId?.ifBlank { null }?.let { loadGapRecord(it) }
			val fromMirror = mirroredRelease(album)
			val rgid: String?
			val artistMbid: String?
			if (fromMirror != null) {
				rgid = fromMirror.first
				artistMbid = fromMirror.second
			} else {
				if (!lbBotManager.ensureAvailability()) return@launch
				rgid = releaseGroupIdFor(album)
				artistMbid = null
			}
			if (!rgid.isNullOrBlank()) {
				// What a "Refresh About" asks with: the same two ids the read below uses, so a
				// refreshed About lands under the key this page reads.
				aboutKey = rgid to releaseMbid
				_aboutRefreshable.value = true
				// The cached article first (Room), then a revalidation only if it is a month old;
				// a failure never replaces what was shown.
				metaJob?.cancel()
				metaJob = launch {
					lbBotManager.albumMeta(rgid, releaseMbid).collect { meta ->
						if (meta != null || _meta.value == null) _meta.value = meta
					}
				}
			}
			// Similarity is computed artist-to-artist, so this needs the artist,
			// not the album; `rgid` only excludes the record on screen.
			//
			// Room's album row does not carry the artist's MBID. The mirror's artist
			// row does, when this album came from it; otherwise lb-bot resolves it
			// from its own Navidrome artist index before asking ListenBrainz, whose
			// similar-artists endpoint is keyed by MBID and answers nothing for a
			// bare name.
			val similar = lbBotManager.similarAlbums(
				artistMbid = artistMbid,
				artistName = album.artistName,
				rgid = rgid
			)
			_similarAlbums.value = runCatching {
				albumRepository.getAlbumsByIds(
					similar?.albums.orEmpty().map { it.albumId }.filter { it.isNotBlank() }
				).associateBy { it.id }
			}.getOrDefault(emptyMap())
			_similar.value = similar
		}
	}

	/**
	 * `(rgid, artist MBID)` for this album out of the index mirror — the same two rules as
	 * [releaseGroupIdFor], in the same order: a row that names this Navidrome album id (this
	 * artist's first, for a collaboration filed under several), else a same-titled row in this
	 * artist's mirrored discography. Room reads only; nothing here waits on lb-bot.
	 *
	 * Null means the mirror does not know this artist at all, and only then does the caller
	 * fall back to the network. A mirrored artist with no matching row answers `(null, mbid)`:
	 * the network would read the very same index and say the same thing. The artist MBID is
	 * lb-bot's own when it has one — never the `nd:` fallback key — and only ever this album's
	 * own artist's, never a collaborator's.
	 */
	private suspend fun mirroredRelease(album: DomainAlbum): Pair<String?, String?>? {
		if (!lbBotManager.isConfigured) return null
		return runCatching {
			val byId = lbIndexSync.releasesForAlbum(collectionId, album.artistId)
				.firstOrNull { it.release.rgid.isNotBlank() }
			if (byId != null) {
				// The MBID only when the row is filed under THIS album's artist. A collaboration
				// can be claimed by the other credited artist's row alone, and sending that
				// artist's MBID would make "similar" mean similar to the collaborator; null keeps
				// the old behaviour (lb-bot resolves the artist from the name).
				val mbid = byId.artist
					?.takeIf { it.ndArtistId == album.artistId }
					?.mbid?.ifBlank { null }
				return@runCatching byId.release.rgid to mbid
			}
			val artist = lbIndexSync.findArtist(album.artistId) ?: return@runCatching null
			val artistMbid = artist.artist.mbid.ifBlank { null }
			val wanted = album.name?.trim()?.lowercase()
			val row = wanted?.let { title ->
				artist.releases.firstOrNull {
					it.rgid.isNotBlank() && it.title.trim().lowercase() == title
				}
			}
			row?.rgid to artistMbid
		}
			.onFailure { Logger.w("CollectionDetailViewModel", "index mirror read failed", it) }
			.getOrNull()
	}

	/**
	 * This album's MusicBrainz **release-group** id, from lb-bot's discography index.
	 *
	 * Matched on `navidrome_album_ids` first, which lb-bot backfills precisely so a
	 * client can go from a Navidrome album to the release-group it satisfies. Title
	 * is the fallback for a row the backfill has not reached — scoped to this one
	 * artist's discography, so it cannot collide with another artist's same-titled
	 * record.
	 *
	 * Null when the artist is not indexed, which is the ordinary fail-soft case: no
	 * About section rather than a wrong one.
	 */
	private suspend fun releaseGroupIdFor(album: DomainAlbum): String? {
		val releases = lbBotManager.discography(album.artistId, null)?.releases.orEmpty()
		if (releases.isEmpty()) return null
		releases.firstOrNull { collectionId in it.navidromeAlbumIds }
			?.rgid?.ifBlank { null }
			?.let { return it }
		val wanted = album.name?.trim()?.lowercase() ?: return null
		return releases.firstOrNull { it.title.trim().lowercase() == wanted }
			?.rgid?.ifBlank { null }
	}

	fun refreshCollection(fullRefresh: Boolean) {
		viewModelScope.launch {
			try {
				repository.getCollectionFlow(fullRefresh, collectionId).collect {
					_collectionState.value = it
					missing.value = false
					if (it.data is DomainAlbum) {
						starred.value = albumRepository.isAlbumStarred(it.data as DomainAlbum)
						rating.value = albumRepository.getAlbumRating(it.data as DomainAlbum)
						try {
							val albumInfo = repository.getAlbumInfo(collectionId)
							val domainInfo = albumInfo.toDomainModel()
							albumInfoState.value = UiState.Success(domainInfo)
							loadLbBotExtras(it.data as DomainAlbum, domainInfo.musicBrainzId)
						} catch (e: Exception) {
							albumInfoState.value = UiState.Error(e)
						}
					}
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				// Room no longer holds this id: `getLocalData` throws a bare `Error` for that,
				// and uncaught here it took the whole app down. It can happen under an open
				// page — a full sync prunes an album, or a retag gives it a new id and the dead
				// row is dropped (I-022) — so it is the page's error state, with what it showed.
				Logger.w("CollectionDetailViewModel", "collection $collectionId could not be read", e)
				missing.value = true
				_collectionState.value = UiState.Error(Exception(e.message, e), _collectionState.value.data)
			}
		}
	}

	fun selectSong(song: DomainSong) {
		viewModelScope.launch {
			selectedSong.value = song
			selectedSongIsStarred.value = songRepository.isSongStarred(song)
			selectedSongRating.value = songRepository.getSongRating(song)
		}
	}

	fun selectAlbum(album: DomainAlbum) {
		viewModelScope.launch {
			selectedAlbum.value = album
			selectedAlbumIsStarred.value = albumRepository.isAlbumStarred(album)
			selectedAlbumRating.value = albumRepository.getAlbumRating(album)
		}
	}

	fun clearSelection() {
		selectedSong.value = null
		selectedAlbum.value = null
	}

	fun clearError() {
		collectionState.value.data?.let {
			_collectionState.value = UiState.Success(it)
		}
	}

	fun removeFromPlaylist() {
		val song = selectedSong.value ?: return
		val songs = collectionState.value.data?.songs ?: return
		viewModelScope.launch {
			try {
				sessionManager.api.updatePlaylist(
					id = collectionId,
					songIndicesToRemove = listOf(songs.indexOf(song))
				)
				snackBarManager.notify(Res.string.notice_removed_from_playlist)
				refreshCollection(true)
			} catch (e: Exception) {
				Logger.e("CollectionDetailViewModel", "Failed to remove song from playlist", e)
			}
		}
		clearSelection()
	}

	fun starSelectedSong() {
		viewModelScope.launch {
			val selection = selectedSong.value ?: return@launch
			runCatching {
				songRepository.starSong(selection)
				selectedSongIsStarred.value = true
				refreshCollection(false)
			}
		}
	}

	fun unstarSelectedSong() {
		viewModelScope.launch {
			val selection = selectedSong.value ?: return@launch
			runCatching {
				songRepository.unstarSong(selection)
				selectedSongIsStarred.value = false
				refreshCollection(false)
			}
		}
	}

	/**
	 * Rate the song whose sheet is open.
	 *
	 * The flow is written BEFORE the suspend call, as `ArtistDetailViewModel` does:
	 * `rateSong` writes Room and then queues the Subsonic `setRating` through
	 * `SyncManager`, so setting it afterwards leaves the stars on the old value for
	 * the length of a database write.
	 *
	 * `refreshCollection` is what the row underneath needs. `SongRow` draws its
	 * `SmallRatingRow` from the `DomainSong` in `collectionState`, a snapshot taken
	 * when the list loaded — so without a re-read the sheet moves and the row it came
	 * from does not. `starSelectedSong` has always done this; this one did not.
	 */
	fun rateSelectedSong(rating: Int) {
		viewModelScope.launch {
			val selection = selectedSong.value ?: return@launch
			selectedSongRating.value = rating
			runCatching {
				songRepository.rateSong(selection, rating)
				refreshCollection(false)
			}
		}
	}

	fun rateAlbum(newRating: Int) {
		viewModelScope.launch {
			(collectionState.value.data as? DomainAlbum)?.let { album ->
				albumRepository.rateAlbum(album, newRating)
				rating.value = newRating
			}
		}
	}

	fun starAlbum(starred: Boolean) {
		viewModelScope.launch {
			runCatching {
				val collection = collectionState.value.data ?: return@launch
				if (collection !is DomainAlbum) return@launch
				if (starred) {
					albumRepository.starAlbum(collection)
				} else {
					albumRepository.unstarAlbum(collection)
				}
				refreshCollection(false)
			}
		}
	}

	fun rateSelectedAlbum(rating: Int) {
		viewModelScope.launch {
			selectedAlbum.value?.let { album ->
				albumRepository.rateAlbum(album, rating)
				selectedAlbumRating.value = rating
			}
		}
	}

	fun starSelectedAlbum(starred: Boolean) {
		viewModelScope.launch {
			runCatching {
				val collection = selectedAlbum.value ?: return@launch
				if (starred) {
					albumRepository.starAlbum(collection)
				} else {
					albumRepository.unstarAlbum(collection)
				}
				selectedAlbumIsStarred.value = starred
			}
		}
	}

	fun downloadSong(song: DomainSong) {
		downloadManager.downloadSong(song)
		snackBarManager.notify(Res.string.notice_download_started)
	}

	fun cancelDownload(songId: String) {
		downloadManager.cancelDownload(songId)
	}

	fun deleteDownload(songId: String) {
		downloadManager.deleteDownload(songId)
		snackBarManager.notify(Res.string.notice_deleted_download)
	}

	fun downloadAll() {
		val collection = collectionState.value.data ?: return
		viewModelScope.launch {
			downloadManager.downloadCollection(collection)
			snackBarManager.notify(Res.string.notice_download_started)
		}
	}

	fun cancelDownloadAll() {
		collectionState.value.data?.songs?.forEach {
			downloadManager.cancelDownload(it.id)
		}
	}

	fun collectionDownloadStatus(): Flow<DownloadStatus> {
		val songs = collectionState.value.data?.songs.orEmpty()
		return downloadManager.getCollectionDownloadStatus(songs.map { it.id })
	}
}

/** An owned album's gap as lb-bot's index records it: the Fill-gaps group, and the tracks missing. */
data class AlbumGap(val groupId: String, val missing: Int)

/** How long a fetch lb-bot accepted holds the box on its steps before lb-bot's status must show it. */
private const val GAP_COMMIT_RELEASE_MS = 30_000L

/** The gaps box's own state; which stage it shows is `gapStage`'s, from this and lb-bot's record. */
data class GapBoxUi(
	val view: GapView = GapView.FOLDED,
	/** A fetch lb-bot accepted that its status does not show yet. */
	val committed: Boolean = false,
	/** A search tap whose answer has not come back yet. */
	val searchAsked: Boolean = false,
	val selected: Int? = null,
	val working: Boolean = false,
	val error: LbBotManager.LbError? = null,
	/** The source this device requested from, for the steps' "FLAC · from …" line. */
	val fetched: LbGapSource? = null,
	/** Folder listings fetched for cards, by source id. */
	val listings: Map<Int, LbSourceFiles> = emptyMap()
)
