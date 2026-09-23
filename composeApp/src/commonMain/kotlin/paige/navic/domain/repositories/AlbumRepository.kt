package paige.navic.domain.repositories

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import paige.navic.data.database.dao.AlbumDao
import paige.navic.data.database.dao.DownloadDao
import paige.navic.data.database.entities.DownloadStatus
import paige.navic.data.database.entities.SyncActionType
import paige.navic.data.database.mappers.toDomainModel
import paige.navic.data.database.mappers.toEntity
import paige.navic.domain.manager.SyncManager
import paige.navic.domain.models.DomainAlbum
import paige.navic.domain.models.DomainAlbumListType
import paige.navic.domain.models.DomainFilter
import paige.navic.ui.core.UiState
import paige.navic.util.toSqlQuery
import kotlin.time.Clock
import kotlinx.coroutines.withContext

class AlbumRepository(
	private val albumDao: AlbumDao,
	private val downloadDao: DownloadDao,
	private val syncManager: SyncManager,
	private val dbRepository: DbRepository
) {
	private suspend fun getLocalData(
		listType: DomainAlbumListType,
		reversed: Boolean,
		filters: Set<DomainFilter> = emptySet()
	): ImmutableList<DomainAlbum> {
		val downloadedSongIds = if (filters.contains(DomainFilter.Downloaded)) {
			downloadDao.getAllDownloadsList()
				.filter { it.status == DownloadStatus.DOWNLOADED }
				.map { it.songId }
				.toSet()
		} else null

		// Same trap as `getAlbumsLimited`: `ORDER BY RANDOM()` is re-evaluated on
		// each execution and `AlbumWithSongs` executes the parent query twice.
		// Shuffled here rather than drawn by id, because this path is unbounded and
		// an `IN (...)` over the whole library would exceed SQLite's bind-variable
		// ceiling — the list is read in full either way.
		val rows = if (listType == DomainAlbumListType.Random) {
			albumDao.getAlbumsByQuery(DomainAlbumListType.AlphabeticalByName.toSqlQuery()).shuffled()
		} else {
			albumDao.getAlbumsByQuery(listType.toSqlQuery())
		}

		return rows
			.map { it.toDomainModel() }
			.filter { album ->
				filters.all { filter ->
					when (filter) {
						DomainFilter.Starred -> album.starredAt != null
						DomainFilter.Downloaded -> downloadedSongIds != null && downloadedSongIds.containsAll(album.songs.map { it.id })
					}
				}
			}
			.let { if (reversed) it.asReversed() else it }
			.toImmutableList()
	}

	private suspend fun refreshLocalData(
		listType: DomainAlbumListType,
		reversed: Boolean,
		filters: Set<DomainFilter> = emptySet()
	): ImmutableList<DomainAlbum> {
		dbRepository.syncLibrarySongs().getOrThrow()
		return getLocalData(listType, reversed, filters)
	}

	fun getAlbumsFlow(
		fullRefresh: Boolean,
		listType: DomainAlbumListType,
		reversed: Boolean,
		filters: Set<DomainFilter> = emptySet()
	): Flow<UiState<ImmutableList<DomainAlbum>>> = flow {
		val localData = getLocalData(listType, reversed, filters)
		if (fullRefresh) {
			emit(UiState.Loading(data = localData))
			try {
				emit(UiState.Success(data = refreshLocalData(listType, reversed, filters)))
			} catch (error: Exception) {
				emit(UiState.Error(error = error, data = localData))
			}
		} else {
			emit(UiState.Success(data = localData))
		}
	}.flowOn(Dispatchers.IO)

	/** navi-connect: the library home's "frequent"/"newest" rows want a short, one-shot list. */
	suspend fun getAlbumsLimited(
		listType: DomainAlbumListType,
		limit: Int
	): ImmutableList<DomainAlbum> = withContext(Dispatchers.IO) {
		// Random is not a sort, it is a *draw*, and it cannot go through the raw
		// query: `ORDER BY RANDOM()` answers differently on each execution, and
		// `AlbumWithSongs` makes Room execute the parent query twice. The second
		// pass then meets an album the first never collected songs for, which is
		// the `NoSuchElementException: Key <albumId> is missing in the map` the
		// Quick Picks widget logs. Draw the ids once, then fetch deterministically.
		if (listType == DomainAlbumListType.Random) {
			val ids = albumDao.getRandomAlbumIds(limit)
			val byId = albumDao.getAlbumsByIds(ids).associateBy { it.album.albumId }
			return@withContext ids.mapNotNull { byId[it]?.toDomainModel() }.toImmutableList()
		}
		albumDao.getAlbumsByQuery(listType.toSqlQuery(limit))
			.map { it.toDomainModel() }
			.toImmutableList()
	}

	suspend fun isAlbumStarred(album: DomainAlbum) = albumDao.isAlbumStarred(album.id)
	suspend fun getAlbumRating(album: DomainAlbum) = albumDao.getAlbumRating(album.id) ?: 0

	suspend fun starAlbum(album: DomainAlbum) {
		val starredEntity = album.toEntity().copy(
			starredAt = Clock.System.now()
		)
		albumDao.insertAlbum(starredEntity)
		syncManager.enqueueAction(SyncActionType.STAR, album.id)
	}

	suspend fun unstarAlbum(album: DomainAlbum) {
		val unstarredEntity = album.toEntity().copy(
			starredAt = null
		)
		albumDao.insertAlbum(unstarredEntity)
		syncManager.enqueueAction(SyncActionType.UNSTAR, album.id)
	}

	suspend fun rateAlbum(album: DomainAlbum, rating: Int) {
		val ratedEntity = album.toEntity().copy(
			userRating = rating
		)
		albumDao.insertAlbum(ratedEntity)
		when (rating) {
			0 -> syncManager.enqueueAction(SyncActionType.STAR_0, album.id)
			1 -> syncManager.enqueueAction(SyncActionType.STAR_1, album.id)
			2 -> syncManager.enqueueAction(SyncActionType.STAR_2, album.id)
			3 -> syncManager.enqueueAction(SyncActionType.STAR_3, album.id)
			4 -> syncManager.enqueueAction(SyncActionType.STAR_4, album.id)
			5 -> syncManager.enqueueAction(SyncActionType.STAR_5, album.id)
		}
	}
}
