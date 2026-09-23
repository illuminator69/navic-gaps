package paige.navic.ui.screens.artist.viewmodels

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.notice_deleted_download
import navic.composeapp.generated.resources.notice_download_started
import paige.navic.data.database.dao.AlbumDao
import paige.navic.data.database.dao.ArtistDao
import paige.navic.data.database.entities.DownloadStatus
import paige.navic.data.database.mappers.toDomainModel
import paige.navic.domain.manager.ConnectivityManager
import paige.navic.domain.manager.DownloadManager
import paige.navic.domain.manager.SnackBarManager
import paige.navic.domain.models.DomainAlbum
import paige.navic.domain.models.DomainArtist
import paige.navic.domain.models.DomainSong
import paige.navic.domain.repositories.AlbumRepository
import paige.navic.domain.repositories.ArtistRepository
import paige.navic.domain.repositories.DbRepository
import paige.navic.domain.repositories.SongRepository
import paige.navic.domain.manager.EVENT_ARTIST_SCANNED
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbCachePolicy
import paige.navic.domain.manager.LbDiscography
import paige.navic.domain.manager.LbIndexSync
import paige.navic.domain.manager.LbLibraryEvent
import paige.navic.domain.manager.LbMeta
import paige.navic.domain.manager.LbScanOutcome
import paige.navic.domain.manager.LbRelease
import paige.navic.domain.manager.NativeApiManager
import paige.navic.util.Logger
import paige.navic.util.albumTitleKey
import paige.navic.shared.MediaPlayerViewModel
import paige.navic.ui.core.UiState
import kotlinx.coroutines.flow.StateFlow
import paige.navic.domain.models.displayName

@Immutable
data class ArtistState(
	val artist: DomainArtist,
	val albums: List<DomainAlbum>,
	val topSongs: List<DomainSong>,
	val similarArtists: List<DomainArtist> = emptyList(),
	/** Albums this artist appears ON without being their album artist — guest/featured credits. */
	val appearsOn: List<DomainAlbum> = emptyList()
)

/**
 * One release on the artist's discography shelf.
 *
 * Owned, partly-owned and not-owned releases are the same kind of thing here on
 * purpose — the shelf is the artist's discography, not a shopping list bolted onto
 * a library list. [album] is set when Navidrome has it, [release] when lb-bot's
 * index knows it, and at least one of the two always is.
 */
@Immutable
data class DiscographyEntry(
	val key: String,
	val title: String,
	val year: String,
	val album: DomainAlbum? = null,
	val release: LbRelease? = null
) {
	val owned: Boolean get() = album != null

	/**
	 * lb-bot says the library holds this, but Navidrome hasn't given us an album row
	 * yet — which is exactly what a just-completed fill looks like, because lb-bot
	 * flips its index row to `present` the moment the files are placed and Room only
	 * learns about it on the next library sync.
	 *
	 * This state exists because without it the tile *disappeared on success*: it was
	 * no longer `missing`, so it dropped out of the absent list, and there was no
	 * album to put in the owned list either.
	 */
	val pendingSync: Boolean get() = album == null && release?.isMissing == false

	/** Partly owned: lb-bot has a Fill-gaps group for the tracks that are absent. */
	val gapGroupId: String? get() = release?.takeIf { it.isIncomplete }?.groupId
	val presentTracks: Int? get() = release?.present
	val totalTracks: Int? get() = release?.total
	val missingTracks: Int
		get() = ((release?.total ?: 0) - (release?.present ?: 0)).coerceAtLeast(0)
}

@Immutable
data class DiscographySection(
	val type: String,
	val entries: List<DiscographyEntry>
)

@Immutable
data class DiscographyUi(
	/** The lb-bot layer answered. False means render nothing at all. */
	val available: Boolean = false,
	/** lb-bot has never scanned this artist — offer to, don't treat it as an error. */
	val indexed: Boolean = false,
	val indexing: Boolean = false,
	/**
	 * When lb-bot last walked this artist, epoch **milliseconds** (its wire value is
	 * float seconds). 0 = never, or an lb-bot too old to report it.
	 *
	 * Shown beside an always-visible Rescan so the button is informed rather than
	 * blind: "last scanned 40 days ago" is the difference between a rescan being
	 * obviously worth a minute of MusicBrainz's patience and obviously not.
	 */
	val scannedAt: Long = 0L,
	/** lb-bot's own verdict: past its index TTL, or built by an older scan version. */
	val stale: Boolean = false,
	/** Why the last scan this page started failed, in lb-bot's words; blank otherwise. */
	val scanError: String = "",
	val sections: List<DiscographySection> = emptyList()
)

class ArtistDetailViewModel(
	private val artistId: String,
	private val repository: DbRepository,
	private val artistRepository: ArtistRepository,
	private val songRepository: SongRepository,
	private val albumRepository: AlbumRepository,
	private val artistDao: ArtistDao,
	private val albumDao: AlbumDao,
	private val downloadManager: DownloadManager,
	private val lbBotManager: LbBotManager,
	private val lbIndexSync: LbIndexSync,
	private val nativeApiManager: NativeApiManager,
	private val snackBarManager: SnackBarManager,
	connectivityManager: ConnectivityManager
) : ViewModel() {
	val artistState: StateFlow<UiState<ArtistState>>
		field = MutableStateFlow<UiState<ArtistState>>(UiState.Loading())

	val starred: StateFlow<Boolean>
		field = MutableStateFlow(false)

	val selectedSong: StateFlow<DomainSong?>
		field = MutableStateFlow(null)

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

	val isOnline = connectivityManager.isOnline

	val allDownloads = downloadManager.allDownloads
		.stateIn(
			scope = viewModelScope,
			started = SharingStarted.Lazily,
			initialValue = emptyList()
		)

	val scrollState = ScrollState(initial = 0)

	/**
	 * lb-bot's editorial "About": the full Wikipedia article with its CC BY-SA
	 * attribution, the Wikidata one-liner, band members and side projects.
	 *
	 * Null until it answers, and null forever when it cannot — no hub, no
	 * LBBOT_URL, or simply nobody having written about this artist. The section
	 * then does not render, which is the §7 rule: absent, never an error.
	 */
	private val _meta = MutableStateFlow<LbMeta?>(null)
	val meta = _meta.asStateFlow()

	/** Whether the About sheet is open. */
	private val _aboutOpen = MutableStateFlow(false)
	val aboutOpen = _aboutOpen.asStateFlow()

	private val _discography = MutableStateFlow(DiscographyUi())
	val discography = _discography.asStateFlow()

	/** The tile whose sheet is open, if it's a release the library doesn't have. */
	private val _selectedRelease = MutableStateFlow<LbRelease?>(null)
	val selectedRelease = _selectedRelease.asStateFlow()

	/** The release sheet is open purely to report on a fill, with no download
	 *  actions — see [openPendingSync]. */
	private val _selectedReleaseReadOnly = MutableStateFlow(false)
	val selectedReleaseReadOnly = _selectedReleaseReadOnly.asStateFlow()

	/** The tile whose sheet is open, if it's a partly-owned album with a gap. */
	private val _selectedGap = MutableStateFlow<DiscographyEntry?>(null)
	val selectedGap = _selectedGap.asStateFlow()

	// ----- where the shelf's lb-bot rows came from -------------------------------------------- //
	//
	// Two sources, and the mirror wins. The local index mirror (`LbIndexSync`, Room) is read
	// before the page's first frame and observed for as long as the page lives; the network read
	// (`GET /lb/artist/discography`) is only the fallback for an artist the mirror does not hold
	// yet — the first sync still running, an old hub with no change feed, an artist lb-bot has
	// never scanned. Once the mirror has answered for this artist the network is never asked
	// again, and a network answer that lands after it is dropped rather than painted over it.
	// Every write to [_discography] from either source goes through [discographyLock], so a
	// rebuild never interleaves with another.

	/** The lb-bot discography the shelf was last built from, from whichever source. */
	private var lbData: LbDiscography? = null

	/** True once the mirror has answered for this artist. */
	private var fromMirror = false

	/** The album list [lbData] was last built against — compared by identity, see [applyLbData]. */
	private var builtAlbums: List<DomainAlbum>? = null

	private val discographyLock = Mutex()
	private var mirrorJob: Job? = null

	init {
		loadArtistData()
		// A fill landing — here or on another client — changes two answers at once:
		// Navidrome has an album it didn't, and lb-bot no longer counts that
		// release-group as missing. Showing one without the other is exactly the
		// double-listing the reconciliation below exists to prevent.
		//
		// lb-bot's half now arrives through the mirror Flow on its own (see
		// [observeMirror]); what a bump still owes the page is Navidrome's half, and it
		// is filtered to THIS artist — see [concerns]. It used to be the bare
		// `libraryRevision`, which with every visited artist page kept alive made each
		// landing anywhere in the library a network refetch per page ever opened.
		viewModelScope.launch {
			lbBotManager.libraryBumps.collect { bump ->
				if ((artistState.value as? UiState.Success) == null) return@collect
				if (!concerns(bump)) return@collect
				// The page's album list is a snapshot of Room taken at open. Without
				// re-reading it, a landed album arrives in Room and the discography row
				// flips, but the tile has no album to resolve to — "Added — syncing".
				val albumsChanged = reloadAlbumsFromRoom()
				when {
					// The mirror carries lb-bot's side; only a changed album list needs the
					// shelf rebuilt, and that is local.
					fromMirror -> if (albumsChanged) rebuildDiscography()
					// Not mirrored yet: lb-bot's rows arrive only by asking.
					else -> loadDiscographyFromNetwork()
				}
			}
		}
	}

	/**
	 * Whether a library bump can change anything on THIS page ("filter library bumps by
	 * `ndArtistId`", navi-connect index-mirror plan §4).
	 *
	 * - An `artistScanned` event (it carries the `ndArtistId` the scan was started with): this
	 *   artist's or not at all. Only that event is decided by the artist id alone. An
	 *   `albumIndexed` also carries one — lb-bot sets it from the album's first track — but that
	 *   names whoever Navidrome filed the album under, and a collaboration filed under the other
	 *   artist is still a tile on THIS page; so for every other event a matching id is a yes and
	 *   a different one falls through to the checks below.
	 * - It names a release-group or albums: this page's if any of them is on it — an album tile,
	 *   a cross-artist album [buildSections] resolved, an album id or rgid on one of lb-bot's
	 *   rows — or if Room files one of the albums under this artist (a landing this page had no
	 *   tile for yet, e.g. a fill started from Fresh).
	 * - It names nothing (a changed-albums sweep): it cannot be ruled out. That costs a Room read
	 *   compared against what is shown, and nothing more unless the album list really moved.
	 */
	private suspend fun concerns(bump: LbLibraryEvent): Boolean {
		if (bump.event == EVENT_ARTIST_SCANNED) return bump.ndArtistId == artistId
		if (bump.ndArtistId == artistId) return true
		if (bump.rgid.isBlank() && bump.ndAlbumIds.isEmpty()) return bump.ndArtistId.isBlank()
		val releases = lbData?.releases.orEmpty()
		if (bump.rgid.isNotBlank() && releases.any { it.rgid == bump.rgid }) return true
		val onPage = (artistState.value as? UiState.Success)?.data?.albums.orEmpty()
			.mapTo(mutableSetOf()) { it.id }
		onPage.addAll(resolvedStrayAlbumIds)
		releases.forEach { onPage.addAll(it.navidromeAlbumIds) }
		if (bump.ndAlbumIds.any { it in onPage }) return true
		val unknown = bump.ndAlbumIds.filter { it.isNotBlank() && it !in onPage }
		if (unknown.isEmpty()) return false
		return runCatching { albumDao.getAlbumsByIds(unknown) }
			.getOrDefault(emptyList())
			.any { it.album.artistId == artistId }
	}

	private fun loadArtistData() {
		viewModelScope.launch {
			try {
				val artistEntity = artistDao.getArtistById(artistId)
					?: throw Exception("Artist not found in database")
				val domainArtist = artistEntity.toDomainModel()

				var albumsWithSongs =
					albumDao.getAlbumsByArtist(artistId).firstOrNull() ?: emptyList()

				if (albumsWithSongs.isEmpty()) {
					albumsWithSongs =
						albumDao.getAlbumsByArtistName(domainArtist.name).firstOrNull()
							?: emptyList()
				}

				val domainAlbums = albumsWithSongs.map { it.toDomainModel() }

				val domainSongs = albumsWithSongs.flatMap { it.songs }
					.map { it.toDomainModel() }
					.sortedByDescending { it.playCount }
					.take(12)

				val initialSimilarArtists = domainArtist.similarArtistIds.mapNotNull { id ->
					artistDao.getArtistById(id)?.toDomainModel()
				}

				starred.value = artistRepository.isArtistStarred(domainArtist)

				// lb-bot's discography, from the local index mirror — a Room read, no network,
				// no availability probe — and built into typed sections in the SAME pass,
				// BEFORE the page goes to Success. The screen's first Success frame therefore
				// already has the shelf, and the legacy album carousel (which renders only
				// while there is no shelf) is never shown and then swapped out: the "jump"
				// this page used to do as the rows spliced in after a probe and a fetch.
				val mirrored = readMirror(domainArtist)
				if (mirrored != null) {
					val sections = buildSections(domainAlbums, mirrored.releases)
					discographyLock.withLock {
						fromMirror = true
						lbData = mirrored
						builtAlbums = domainAlbums
						_discography.value = discographyUiFor(mirrored, sections, _discography.value)
					}
				}

				artistState.value = UiState.Success(
					ArtistState(
						artist = domainArtist,
						albums = domainAlbums,
						topSongs = domainSongs,
						similarArtists = initialSimilarArtists
					)
				)
				// The mirror keeps answering for as long as the page lives: a sync that
				// changes this artist re-emits, with no refetch and no bump involved.
				observeMirror(domainArtist)

				// Fire-and-forget, and strictly after the page has its own data: the
				// discography shelf is an enhancement, and an artist page must render
				// identically whether or not lb-bot is there. The network discography is
				// only asked for an artist the mirror does not hold (yet).
				if (mirrored == null) loadDiscographyFromNetwork()
				loadMeta()
				loadAppearsOn(domainAlbums)

				repository.fetchArtistMetadata(artistId)
					.onSuccess { updatedArtist ->
						val currentState = (artistState.value as? UiState.Success)?.data
						if (currentState != null) {

							val updatedSimilarArtists =
								updatedArtist.similarArtistIds.mapNotNull { id ->
									artistDao.getArtistById(id)?.toDomainModel()
								}

							artistState.value = UiState.Success(
								currentState.copy(
									artist = updatedArtist,
									similarArtists = updatedSimilarArtists
								)
							)
						}
					}
					.onFailure { error ->
						Logger.e("ArtistDetailViewModel", "Failed to fetch artist metadata", error)
					}
			} catch (e: Exception) {
				artistState.value = UiState.Error(e)
			}
		}
	}

	/**
	 * Albums the artist appears on without being credited as their album artist.
	 *
	 * Navidrome's native `artist_id` is a participation filter, so one query returns both kinds
	 * and subtracting the album-artist rows leaves exactly the guest credits — which is how
	 * Feishin does it. Subsonic cannot express this: `getArtist` is album-artist only.
	 *
	 * Ids come from the server; the albums themselves come from Room, so the tiles are the same
	 * cached rows as everywhere else and nothing has to parse Navidrome's album shape here.
	 *
	 * Fail-soft, like every other optional layer on this page: offline, an old server, or a
	 * failed login all leave the list empty and the section simply doesn't render.
	 */
	private fun loadAppearsOn(ownAlbums: List<DomainAlbum>) {
		viewModelScope.launch {
			val ownIds = ownAlbums.mapTo(mutableSetOf()) { it.id }
			val ids = nativeApiManager.albumIdsByParticipation(artistId)
				.getOrNull()
				?.filter { it !in ownIds }
				?: return@launch
			if (ids.isEmpty()) return@launch
			val albums = runCatching { albumDao.getAlbumsByIds(ids) }
				.getOrDefault(emptyList())
				.map { it.toDomainModel() }
				.sortedByDescending { it.year ?: 0 }
			if (albums.isEmpty()) return@launch
			val current = (artistState.value as? UiState.Success)?.data ?: return@launch
			artistState.value = UiState.Success(current.copy(appearsOn = albums))
		}
	}

	// ------------------------------------------------------------------ //
	// lb-bot discography
	// ------------------------------------------------------------------ //

	/** Re-read this artist's albums from Room; true when the list actually changed. */
	private suspend fun reloadAlbumsFromRoom(): Boolean {
		val current = (artistState.value as? UiState.Success)?.data ?: return false
		var rows = albumDao.getAlbumsByArtist(artistId).firstOrNull() ?: emptyList()
		if (rows.isEmpty()) {
			rows = albumDao.getAlbumsByArtistName(current.artist.name).firstOrNull() ?: emptyList()
		}
		val albums = rows.map { it.toDomainModel() }
		// Cover id included: an artwork re-sync changes nothing else about the album.
		if (albums.map { Triple(it.id, it.songCount, it.coverArtId) } ==
			current.albums.map { Triple(it.id, it.songCount, it.coverArtId) }) return false
		// Re-read the state: the metadata fetch may have replaced it while Room answered.
		val latest = (artistState.value as? UiState.Success)?.data ?: return false
		artistState.value = UiState.Success(latest.copy(albums = albums))
		return true
	}

	/**
	 * Fetch the editorial metadata. Fired once per page open beside the
	 * discography, never on the critical render path: the header and the album
	 * grid must look exactly as they do today while this is in flight, and
	 * exactly as they do today forever if it never answers.
	 *
	 * The MBID is sent when Navidrome carries one. Without it lb-bot falls back to
	 * a MusicBrainz name search and takes the top hit, which for a generically
	 * named artist is a coin toss — so the name is the fallback, not the default.
	 */
	fun loadMeta() {
		val state = (artistState.value as? UiState.Success)?.data ?: return
		viewModelScope.launch {
			if (!lbBotManager.isConfigured) return@launch
			val mbid = state.artist.musicBrainzId
			val name = state.artist.name
			// The About this device last saw, from Room — no probe, no network, offline too —
			// so a revisited artist has it in the page's first frames rather than after a
			// `/lb/status` round trip and a MusicBrainz-backed read.
			lbBotManager.artistMeta(mbid, name, LbCachePolicy.CACHE_ONLY)
				.collect { cached -> if (cached != null) _meta.value = cached }
			// `first { it }` rather than reading `isOnline.value` once: this fires
			// on page open, and opening a page while offline used to mean the About
			// never appeared for as long as you stayed on it, even once the network
			// came back. Suspending here costs nothing — the whole call is off the
			// critical path by design — and the coroutine dies with the ViewModel.
			if (!isOnline.value) isOnline.first { it }
			if (!lbBotManager.ensureAvailability()) return@launch
			// Revalidates only when the cached body is older than its max age (a month). A null
			// here means nothing was cached AND nothing came back; it never replaces a body.
			lbBotManager.artistMeta(mbid, name).collect { meta ->
				if (meta != null || _meta.value == null) _meta.value = meta
			}
		}
	}

	fun openAbout() {
		_aboutOpen.value = true
	}

	fun dismissAbout() {
		_aboutOpen.value = false
	}

	/**
	 * This artist from the index mirror, or null when the mirror does not hold them — or when no
	 * hub is configured at all ([LbBotManager.isConfigured]): the mirror answers offline and
	 * with lb-bot down, which is its point, but not for a user who has switched the layer off.
	 * Looked up exactly as lb-bot looks up the network read (MBID key first, then the Navidrome
	 * id — contract §1a), so both sources name the same artist.
	 */
	private suspend fun readMirror(artist: DomainArtist): LbDiscography? {
		if (!lbBotManager.isConfigured) return null
		return runCatching { lbIndexSync.discography(artistId, artist.musicBrainzId) }
			.onFailure { Logger.w("ArtistDetailViewModel", "index mirror read failed", it) }
			.getOrNull()
	}

	/**
	 * Collect the mirror for this artist for the life of the page. Its first emission repeats
	 * what [loadArtistData] already painted, and [applyLbData] drops it as unchanged; after that
	 * every emission is a sync that touched this artist — a rescan landing, a fill flipping a
	 * row, the first sync reaching an artist that was only on the network path — and it rebuilds
	 * the shelf in place. A null (not mirrored, or wiped by a `resync` and not re-pulled yet)
	 * leaves whatever is shown alone rather than blanking the shelf under the user.
	 *
	 * Started once: [loadArtistData] runs again after a star/unstar.
	 */
	private fun observeMirror(artist: DomainArtist) {
		if (mirrorJob != null) return
		mirrorJob = viewModelScope.launch {
			lbIndexSync.observeDiscography(artistId, artist.musicBrainzId)
				.catch { Logger.w("ArtistDetailViewModel", "index mirror observe failed", it) }
				.collect { data ->
					if (data == null || !lbBotManager.isConfigured) return@collect
					applyLbData(data, mirror = true)
				}
		}
	}

	/**
	 * The FALLBACK: `GET /lb/artist/discography`, for an artist the mirror does not hold. This
	 * is the old per-page `ensureAvailability` → `discography` waterfall, kept for exactly that
	 * case (plan §4) and never taken once the mirror has answered.
	 */
	private fun loadDiscographyFromNetwork() {
		val state = (artistState.value as? UiState.Success)?.data ?: return
		viewModelScope.launch {
			if (fromMirror) return@launch
			if (!isOnline.value || !lbBotManager.ensureAvailability()) {
				markUnavailable()
				return@launch
			}
			val data = lbBotManager.discography(state.artist.id, state.artist.musicBrainzId)
			if (data == null) {
				markUnavailable()
				return@launch
			}
			applyLbData(data, mirror = false)
		}
	}

	/** lb-bot is not answering and the mirror has nothing: render no shelf at all (§7). */
	private suspend fun markUnavailable() {
		discographyLock.withLock {
			if (fromMirror) return
			_discography.value = DiscographyUi(available = false)
		}
	}

	/**
	 * The one way lb-bot data reaches the shelf after the first frame, from either source.
	 *
	 * Skipped when nothing changed — same data, built against the very same album list (by
	 * identity: [reloadAlbumsFromRoom] only replaces the list when it really moved) — which is
	 * the mirror Flow's first emission, and any re-emission a sync makes for another artist's
	 * change that happened to touch this one's lookup. A network answer arriving after the mirror
	 * took over is dropped. The in-flight scan state ([DiscographyUi.indexing] / `scanError`)
	 * is carried across: a rebuild is not the scan finishing.
	 */
	private suspend fun applyLbData(data: LbDiscography, mirror: Boolean) {
		val albums = (artistState.value as? UiState.Success)?.data?.albums ?: return
		discographyLock.withLock {
			if (!mirror && fromMirror) return
			if (data == lbData && albums === builtAlbums && (!mirror || fromMirror)) return
			val sections = if (data.indexed) buildSections(albums, data.releases) else emptyList()
			if (mirror) fromMirror = true
			lbData = data
			builtAlbums = albums
			_discography.value = discographyUiFor(data, sections, _discography.value)
		}
	}

	/** The album list moved (a landing reached Room): rebuild from the data already held. */
	private suspend fun rebuildDiscography() {
		val data = lbData ?: return
		applyLbData(data, mirror = fromMirror)
	}

	private fun discographyUiFor(
		data: LbDiscography,
		sections: List<DiscographySection>,
		previous: DiscographyUi
	): DiscographyUi = if (!data.indexed) {
		DiscographyUi(
			available = true,
			indexed = false,
			indexing = previous.indexing,
			scanError = previous.scanError
		)
	} else {
		DiscographyUi(
			available = true,
			indexed = true,
			indexing = previous.indexing,
			scanError = previous.scanError,
			scannedAt = (data.scannedAt * 1000).toLong(),
			stale = data.stale,
			sections = sections
		)
	}

	/**
	 * Start the (slow, rate-limited) MusicBrainz walk, then wait for the index to fill.
	 *
	 * The wait is a race. The mirror is the feedback: lb-bot stores the scan, pushes an `index`
	 * frame, [LbIndexSync] pulls, and this artist's mirror Flow emits a newer `scannedAt` —
	 * seconds after the walk ends. [LbBotManager.awaitArtistScan] is the fallback, a 15 s poll
	 * (woken early by lb-bot's `artistScanned` frame) that is also the only thing able to say the
	 * scan FAILED, since the scan record is not index state. Whichever answers first wins.
	 */
	fun indexArtist() {
		val state = (artistState.value as? UiState.Success)?.data ?: return
		val mbid = state.artist.musicBrainzId
		if (mbid.isNullOrBlank()) return
		viewModelScope.launch {
			_discography.value = _discography.value.copy(indexing = true, scanError = "")
			// Watch `scanned_at`, not `indexed`. For an already-indexed artist `indexed` is true on
			// the very first tick, so the spinner cleared while the walk was still running — in
			// exactly the case a rescan button exists for. A first scan reads 0 here, so the same
			// "it moved" rule covers both. The float as lb-bot wrote it — from whichever source the
			// shelf is showing, else asked — never the page's millisecond copy, whose truncation
			// would read as "it moved" on an unchanged scan.
			val scannedAtBefore = lbData?.takeIf { it.indexed }?.scannedAt
				?: lbBotManager.discography(state.artist.id, mbid)?.scannedAt
				?: 0.0
			val taskId = lbBotManager.indexArtist(mbid, state.artist.name, state.artist.id)
			if (taskId == null) {
				_discography.value = _discography.value.copy(
					indexing = false,
					scanError = "lb-bot did not start the scan"
				)
				return@launch
			}
			// A big discography takes 10-60s at MusicBrainz's one request a second.
			val outcome = coroutineScope {
				val viaMirror = async<LbScanOutcome> {
					try {
						LbScanOutcome.Done(
							lbIndexSync.observeDiscography(state.artist.id, mbid)
								.filterNotNull()
								.first { it.indexed && it.scannedAt > scannedAtBefore }
						)
					} catch (e: CancellationException) {
						throw e
					} catch (e: Exception) {
						// A Room failure must not take the scan down with it: leave the
						// race to the poll.
						Logger.w("ArtistDetailViewModel", "index mirror watch failed", e)
						awaitCancellation()
					}
				}
				val viaPoll = async {
					lbBotManager.awaitArtistScan(state.artist.id, mbid, taskId, scannedAtBefore)
				}
				select<LbScanOutcome> {
					viaMirror.onAwait { it }
					viaPoll.onAwait { it }
				}.also {
					viaMirror.cancel()
					viaPoll.cancel()
				}
			}
			when (outcome) {
				is LbScanOutcome.Done -> {
					_discography.value = _discography.value.copy(indexing = false)
					// The mirror Flow may already have painted this; applyLbData skips it then.
					// A network Done for an artist the mirror does not hold yet is the one case
					// where this paints the result.
					applyLbData(outcome.data, mirror = fromMirror)
				}
				is LbScanOutcome.Failed -> _discography.value = _discography.value.copy(
					indexing = false,
					scanError = outcome.error
				)
				LbScanOutcome.TimedOut -> _discography.value = _discography.value.copy(indexing = false)
			}
		}
	}

	/**
	 * Navidrome album ids resolved out of Room that are NOT on this artist's album list.
	 *
	 * A collaboration record filed under the other credited artist, in practice. Kept so
	 * [openPendingSync] can accept the same ids [buildSections] already proved resolvable.
	 */
	private val resolvedStrayAlbumIds = mutableSetOf<String>()

	/**
	 * Build the discography shelf: everything Navidrome has by this artist, plus
	 * everything lb-bot's MusicBrainz index says exists and the library doesn't.
	 *
	 * Navidrome comes first and is never dropped. lb-bot's list is its own view of
	 * the artist, and an album whose Navidrome record its matcher couldn't claim has
	 * no row at all — so building the shelf out of lb-bot's list would silently hide
	 * albums the user owns.
	 *
	 * Pure list-crunching, so it runs on [Dispatchers.Default].
	 *
	 * `viewModelScope` dispatches on `Main.immediate`, so without this the whole matching pass —
	 * two maps over every lb-bot release, a pass over every owned album, then a groupBy and a sort
	 * per section — ran on the UI thread, landing precisely as the discography painted. A prolific
	 * artist with a large lb-bot index is where that was felt.
	 */
	private suspend fun buildSections(
		albums: List<DomainAlbum>,
		releases: List<LbRelease>
	): List<DiscographySection> = withContext(Dispatchers.Default) {
		val claimedRelease = mutableMapOf<String, LbRelease>()
		val usedRgids = mutableSetOf<String>()

		// Navidrome album ids are authoritative — lb-bot wrote them itself when it
		// matched the release-group. Fall back to a normalised title only for the rows
		// it couldn't attach an id to.
		val byNdId = mutableMapOf<String, LbRelease>()
		val byTitle = mutableMapOf<String, LbRelease>()
		for (release in releases) {
			release.navidromeAlbumIds.forEach { id ->
				if (id.isNotBlank() && id !in byNdId) byNdId[id] = release
			}
			val key = albumTitleKey(release.title)
			if (key.isNotEmpty() && key !in byTitle) byTitle[key] = release
		}
		for (album in albums) {
			val match = byNdId[album.id] ?: byTitle[albumTitleKey(album.displayName)]
			if (match != null && usedRgids.add(match.rgid)) claimedRelease[album.id] = match
		}

		// Now the other direction, for albums Navidrome filed under SOMEBODY ELSE.
		//
		// The loop above walks this artist's albums, so an id can only ever match if the album is
		// already on this page. A collaboration record — downloaded from A's page, tagged so that
		// Navidrome files it under B — is by definition not, and lb-bot's perfectly good album id
		// had nothing to match against. The row fell through to `absent` with album == null, which
		// is precisely `pendingSync`: "Added — syncing", forever, on an album the library holds.
		//
		// Room, not `albums`, is the right place to look those up: the album exists, it just isn't
		// one of this artist's.
		val knownIds = albums.mapTo(mutableSetOf()) { it.id }
		val strayIds = releases
			.filter { it.rgid.isNotBlank() && it.rgid !in usedRgids }
			.flatMap { release -> release.navidromeAlbumIds.map { it to release } }
			.filter { (id, _) -> id.isNotBlank() && id !in knownIds }
		val strayAlbums = if (strayIds.isEmpty()) {
			emptyList()
		} else {
			runCatching { albumDao.getAlbumsByIds(strayIds.map { it.first }.distinct()) }
				.getOrDefault(emptyList())
				.map { it.toDomainModel() }
		}
		val strayById = strayAlbums.associateBy { it.id }
		resolvedStrayAlbumIds.addAll(strayById.keys)
		val crossArtist = mutableListOf<DomainAlbum>()
		for ((id, release) in strayIds) {
			val album = strayById[id] ?: continue
			// One album, one tile: two releases can name the same Navidrome album id, and the
			// per-rgid guard below would let the second through as a duplicate row.
			if (album.id in claimedRelease) continue
			if (!usedRgids.add(release.rgid)) continue
			claimedRelease[album.id] = release
			crossArtist.add(album)
		}

		val owned = (albums + crossArtist).map { album ->
			val release = claimedRelease[album.id]
			DiscographyEntry(
				key = album.id,
				title = album.displayName,
				year = release?.year ?: album.year?.toString().orEmpty(),
				album = album,
				release = release
			)
		}
		// Every lb-bot row that didn't attach to a Navidrome album, whatever its
		// status — NOT just the `missing` ones.
		//
		// Filtering to `missing` here is what made a filled album vanish. lb-bot
		// flips its index row to `present` as soon as the files are placed, so a
		// successful download turned the row non-missing while Room still had no
		// album for it, and the tile fell out of both lists. A `missing` row that
		// matched an owned album is still absorbed into it above rather than
		// dropped, which is what stops the same record rendering twice.
		val absent = releases
			.filter { it.rgid.isNotBlank() && it.rgid !in usedRgids }
			.map { release ->
				DiscographyEntry(
					key = release.rgid,
					title = release.title,
					year = release.year,
					release = release
				)
			}

		(owned + absent)
			.groupBy { sectionType(it) }
			.map { (type, entries) ->
				DiscographySection(
					type = type,
					entries = entries.sortedWith(
						compareByDescending<DiscographyEntry> { it.year }
							.thenBy { it.title.lowercase() }
					)
				)
			}
			.sortedBy { TYPE_ORDER.indexOf(it.type).takeIf { i -> i >= 0 } ?: TYPE_ORDER.size }
	}

	/**
	 * lb-bot's `effective_type`, raw and lowercase. The display name is the
	 * composable's job — turning "ep" into a heading here produced "Ep", and a
	 * `replaceFirstChar` can never know that this particular vocabulary contains an
	 * initialism. An album lb-bot doesn't know about is an album.
	 */
	private fun sectionType(entry: DiscographyEntry): String =
		entry.release?.effectiveType?.takeIf { it.isNotBlank() }
			?: entry.release?.primaryType?.takeIf { it.isNotBlank() }
			?: "album"

	fun selectRelease(release: LbRelease?) {
		_selectedRelease.value = release
		if (release == null) _selectedReleaseReadOnly.value = false
	}

	/**
	 * Open a `pendingSync` tile — lb-bot has placed the files and Navidrome has not
	 * indexed them yet, so there is no album row to open but there is a fill to
	 * report on.
	 *
	 * Returns the Navidrome album id to open instead, when one has appeared (lb-bot
	 * wrote it into the index row itself, and Room may have caught up since the
	 * shelf was built). Otherwise it opens the sheet read-only. Before this the tile
	 * was simply inert — visible, and not tappable, which reads as broken.
	 */
	fun openPendingSync(entry: DiscographyEntry): String? {
		// Only an id Room actually has: lb-bot writes these into its index row, and
		// opening one Navidrome has not indexed yet lands on CollectionDetail's
		// not-in-the-local-DB error path — which is the same dead end as before,
		// wearing a different hat.
		//
		// "Room has it" is NOT the same as "this artist has it". A collaboration album can sit
		// under the other credited artist, so the id resolves perfectly well while being absent
		// from this page's album list — [resolvedStrayAlbumIds] is what buildSections found by
		// looking those up directly.
		val owned = (artistState.value as? UiState.Success)?.data?.albums.orEmpty()
			.mapTo(mutableSetOf()) { it.id }
		owned.addAll(resolvedStrayAlbumIds)
		val known = entry.release?.navidromeAlbumIds.orEmpty()
			.firstOrNull { it.isNotBlank() && it in owned }
		if (known != null) return known
		val release = entry.release ?: return null
		_selectedReleaseReadOnly.value = true
		_selectedRelease.value = release
		return null
	}

	fun selectGap(entry: DiscographyEntry?) {
		_selectedGap.value = entry
	}

	fun selectSong(song: DomainSong) {
		viewModelScope.launch {
			selectedSong.value = song
			selectedSongIsStarred.value = songRepository.isSongStarred(song)
			selectedSongRating.value = songRepository.getSongRating(song)
		}
	}

	fun clearSelection() {
		selectedSong.value = null
	}

	fun selectAlbum(album: DomainAlbum) {
		viewModelScope.launch {
			selectedAlbum.value = album
			selectedAlbumIsStarred.value = albumRepository.isAlbumStarred(album)
			selectedAlbumRating.value = albumRepository.getAlbumRating(album)
		}
	}

	fun rateSelectedAlbum(rating: Int) {
		viewModelScope.launch {
			val selection = selectedAlbum.value ?: return@launch
			runCatching {
				selectedAlbumRating.value = rating
				albumRepository.rateAlbum(selection, rating)
			}
		}
	}

	fun clearAlbumSelection() {
		selectedAlbum.value = null
	}

	fun starSelectedSong() {
		viewModelScope.launch {
			val selection = selectedSong.value ?: return@launch
			runCatching {
				selectedSongIsStarred.value = true
				songRepository.starSong(selection)
				loadArtistData()
			}
		}
	}

	fun unstarSelectedSong() {
		viewModelScope.launch {
			val selection = selectedSong.value ?: return@launch
			runCatching {
				selectedSongIsStarred.value = false
				songRepository.unstarSong(selection)
				loadArtistData()
			}
		}
	}

	fun rateSelectedSong(rating: Int) {
		viewModelScope.launch {
			val selection = selectedSong.value ?: return@launch
			runCatching {
				selectedSongRating.value = rating
				songRepository.rateSong(selection, rating)
			}
		}
	}

	fun starArtist(isStarred: Boolean) {
		val artist = (artistState.value as? UiState.Success)?.data?.artist ?: return
		viewModelScope.launch {
			runCatching {
				if (isStarred) {
					artistRepository.starArtist(artist)
				} else {
					artistRepository.unstarArtist(artist)
				}
				starred.value = isStarred
			}
		}
	}

	fun starAlbum(starred: Boolean) {
		viewModelScope.launch {
			val selection = selectedAlbum.value ?: return@launch
			runCatching {
				if (starred) {
					albumRepository.starAlbum(selection)
				} else {
					albumRepository.unstarAlbum(selection)
				}
				selectedAlbumIsStarred.value = starred
			}
		}
	}

	fun playArtistAlbums(player: MediaPlayerViewModel) {
		(artistState.value as? UiState.Success)?.data?.let { state ->
			player.clearQueue()
			state.albums.forEach { album ->
				player.addToQueue(album)
			}
			player.playAt(0)
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

	@OptIn(ExperimentalCoroutinesApi::class)
	fun collectionDownloadStatus(): Flow<DownloadStatus> {
		return artistState.flatMapLatest { state ->
			if (state is UiState.Success) {
				val allArtistSongIds = state.data.albums.flatMap { album ->
					album.songs.map { it.id }
				}

				if (allArtistSongIds.isEmpty()) {
					flowOf(DownloadStatus.NOT_DOWNLOADED)
				} else {
					downloadManager.getCollectionDownloadStatus(allArtistSongIds)
				}
			} else {
				flowOf(DownloadStatus.NOT_DOWNLOADED)
			}
		}
	}

	private companion object {

		/** Shelf order, in lb-bot's own vocabulary (`_TYPE_DEFINING_SECONDARY` plus the
		 *  primary types it browses). Anything new lands at the end rather than
		 *  disappearing. */
		val TYPE_ORDER = listOf(
			"album", "ep", "single", "compilation", "soundtrack", "live", "remix", "demo"
		)
	}
}
