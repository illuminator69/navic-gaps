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

		return artists.filter { artist ->
			filters.all { filter ->
				when (filter) {
					DomainFilter.Starred -> artist.starredAt != null
					DomainFilter.Downloaded -> songDao
						.getSongsByArtistId(artist.id)
						.takeIf { it.isNotEmpty() }
						?.all {
							val download = downloadDao.getDownloadById(it.songId)
							return@all download?.status == DownloadStatus.DOWNLOADED
						} ?: false
				}
			}
		}.toImmutableList()
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
