package paige.navic.domain.repositories

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import paige.navic.data.database.dao.ArtistDao
import paige.navic.data.database.dao.DownloadDao
import paige.navic.data.database.dao.SongDao
import paige.navic.data.database.entities.DownloadStatus
import paige.navic.data.database.entities.SyncActionType
import paige.navic.data.database.mappers.toDomainModel
import paige.navic.data.database.mappers.toEntity
import paige.navic.domain.manager.SyncManager
import paige.navic.domain.models.DomainArtist
import paige.navic.domain.models.DomainArtistListType
import paige.navic.domain.models.DomainFilter
import paige.navic.ui.core.UiState
import paige.navic.util.CoverPlaceholder
import kotlin.time.Clock

class ArtistRepository(
	private val artistDao: ArtistDao,
	private val songDao: SongDao,
	private val downloadDao: DownloadDao,
	private val syncManager: SyncManager,
	private val dbRepository: DbRepository
) {
	/**
	 * Refresh which cover hash is Navidrome's generic avatar (see [CoverPlaceholder]). One indexed
	 * GROUP BY, next to a read that already pulls the whole artist table, and it has to run before
	 * the tiles are composed or the first pass shows the grey squares anyway.
	 *
	 * Lost in the alpha58 merge: upstream rewrote [getLocalData] (`reversed` -> `filters`) and the
	 * merge took its function whole, dropping this call and the helper with it. Nothing failed to
	 * compile — [CoverPlaceholder.learn] simply never ran, so `imageHash` stayed null,
	 * `isPlaceholder` always answered false, and both the tile fallback and (now) the theming skip
	 * were dead. Same failure class as SESSION-2026-09-21 §3, minus the crash that would have
	 * announced it.
	 */
	private suspend fun learnCoverPlaceholder() {
		val counts = artistDao.coverImageHashCounts()
		CoverPlaceholder.learn(
			topHash = counts.firstOrNull()?.hash,
			topCount = counts.firstOrNull()?.artistCount ?: 0,
			runnerUpCount = counts.getOrNull(1)?.artistCount ?: 0
		)
	}

	private suspend fun getLocalData(
		listType: DomainArtistListType,
		filters: Set<DomainFilter> = emptySet()
	): ImmutableList<DomainArtist> {
		learnCoverPlaceholder()
		val artists = when (listType) {
			DomainArtistListType.AlphabeticalByName -> artistDao.getArtistsAlphabeticalByName()
			DomainArtistListType.Random -> artistDao.getArtistsRandom()
		}.map { it.toDomainModel() }

		// Read once for the whole list, not per artist: the per-song lookups made a Downloaded filter
		// over 2,474 artists some 15,000 queries, slow enough that a later tap finished first (P5
		// round 3, seen on the AVD).
		val withDownloads = if (DomainFilter.Downloaded in filters) downloadedArtistIds() else emptySet()
		return artists.filter { artist ->
			filters.all { filter ->
				when (filter) {
					DomainFilter.Starred -> artist.starredAt != null
					DomainFilter.Downloaded -> artist.id in withDownloads
				}
			}
		}.toImmutableList()
	}

	/**
	 * Every artist credited on a downloaded song: the song's artist, its artists and its album's
	 * artists. An artist is "downloaded" when anything of theirs is on the device (it used to need
	 * every song of theirs, which almost no artist meets, so the filter showed nothing).
	 */
	private suspend fun downloadedArtistIds(): Set<String> {
		val ids = downloadDao.getAllDownloadsList()
			.filter { it.status == DownloadStatus.DOWNLOADED }
			.map { it.songId }
		return ids.chunked(SQL_CHUNK).flatMap { chunk -> songDao.getSongsByIds(chunk) }
			.flatMapTo(HashSet()) { song ->
				creditedArtistIds(song.artistId, song.artists.map { it.id }, song.albumArtists.map { it.id })
			}
	}

	private suspend fun refreshLocalData(
		listType: DomainArtistListType,
		filters: Set<DomainFilter> = emptySet()
	): ImmutableList<DomainArtist> {
		dbRepository.syncArtists().getOrThrow()
		return getLocalData(listType, filters)
	}

	fun getArtistsFlow(
		fullRefresh: Boolean,
		listType: DomainArtistListType,
		filters: Set<DomainFilter> = emptySet()
	): Flow<UiState<ImmutableList<DomainArtist>>> = flow {
		val localData = getLocalData(listType, filters)
		if (fullRefresh) {
			emit(UiState.Loading(data = localData))
			try {
				emit(UiState.Success(data = refreshLocalData(listType, filters)))
			} catch (error: Exception) {
				emit(UiState.Error(error = error, data = localData))
			}
		} else {
			emit(UiState.Success(data = localData))
		}
	}.flowOn(Dispatchers.IO)

	suspend fun isArtistStarred(artist: DomainArtist) = artistDao.isArtistStarred(artist.id)

	suspend fun starArtist(artist: DomainArtist) {
		val starredEntity = artist.toEntity().copy(
			starredAt = Clock.System.now()
		)
		artistDao.insertArtist(starredEntity)
		syncManager.enqueueAction(SyncActionType.STAR, artist.id)
	}

	suspend fun unstarArtist(artist: DomainArtist) {
		val unstarredEntity = artist.toEntity().copy(
			starredAt = null
		)
		artistDao.insertArtist(unstarredEntity)
		syncManager.enqueueAction(SyncActionType.UNSTAR, artist.id)
	}
}

/** A song's credited artist ids, blanks left out (`downloadedArtistIds`). */
internal fun creditedArtistIds(artistId: String, artists: List<String>, albumArtists: List<String>): Set<String> =
	(listOf(artistId) + artists + albumArtists).filterTo(LinkedHashSet()) { it.isNotBlank() }

/** SQLite's bound-variable limit is 999 on older Androids: an IN list goes in chunks below it. */
private const val SQL_CHUNK = 500
