package paige.navic.domain.repositories

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import paige.navic.domain.manager.SyncManager
import paige.navic.data.database.dao.ArtistDao
import paige.navic.data.database.dao.DownloadDao
import paige.navic.data.database.dao.SongDao
import paige.navic.data.database.entities.SyncActionType
import paige.navic.data.database.mappers.toDomainModel
import paige.navic.data.database.mappers.toEntity
import paige.navic.domain.models.DomainArtist
import paige.navic.domain.models.DomainArtistListType
import paige.navic.ui.core.UiState
import kotlin.time.Clock
import paige.navic.util.core.CoverPlaceholder

class ArtistRepository(
	private val artistDao: ArtistDao,
	private val downloadDao: DownloadDao,
	private val songDao: SongDao,
	private val syncManager: SyncManager,
	private val dbRepository: DbRepository
) {
	/**
	 * Refresh which cover hash is Navidrome's generic avatar (see [CoverPlaceholder]). One indexed
	 * GROUP BY, next to a read that already pulls the whole artist table, and it has to run before
	 * the tiles are composed or the first pass shows the grey squares anyway.
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
		reversed: Boolean
	): ImmutableList<DomainArtist> {
		learnCoverPlaceholder()
		val entities = when (listType) {
			DomainArtistListType.AlphabeticalByName -> artistDao.getArtistsAlphabeticalByName()
			DomainArtistListType.Random -> artistDao.getArtistsRandom()
			DomainArtistListType.Starred -> artistDao.getArtistsStarred()
			// Only artists that own at least one downloaded song. Downloads carry just a
			// songId, so resolve those to their artistIds and keep matching artists (in the
			// alphabetical order the DAO already returns).
			DomainArtistListType.Downloaded -> {
				val downloadedSongIds = downloadDao.getSongIdsByStatus()
				if (downloadedSongIds.isEmpty()) emptyList()
				else {
					// Chunk to stay under SQLite's bound-parameter limit (~999), same as SongRepository.
					val downloadedArtistIds = downloadedSongIds
						.chunked(500)
						.flatMap { songDao.getSongsByIds(it) }
						.map { it.artistId }
						.toSet()
					artistDao.getArtistsAlphabeticalByName()
						.filter { it.artistId in downloadedArtistIds }
				}
			}
		}
		return entities
			.map { it.toDomainModel() }
			.let { if (reversed) it.asReversed() else it }
			.toImmutableList()
	}

	private suspend fun refreshLocalData(
		listType: DomainArtistListType,
		reversed: Boolean
	): ImmutableList<DomainArtist> {
		dbRepository.syncArtists().getOrThrow()
		return getLocalData(listType, reversed)
	}

	fun getArtistsFlow(
		fullRefresh: Boolean,
		listType: DomainArtistListType,
		reversed: Boolean
	): Flow<UiState<ImmutableList<DomainArtist>>> = flow {
		val localData = getLocalData(listType, reversed)
		if (fullRefresh) {
			emit(UiState.Loading(data = localData))
			try {
				emit(UiState.Success(data = refreshLocalData(listType, reversed)))
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
