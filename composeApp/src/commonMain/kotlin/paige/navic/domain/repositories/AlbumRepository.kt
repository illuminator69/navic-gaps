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
import paige.navic.util.albumListSql
import paige.navic.util.idsAsJson
import paige.navic.util.rowsInOrder
import paige.navic.util.toSqlQuery
import kotlin.time.Clock
import kotlinx.coroutines.withContext

class AlbumRepository(
	private val albumDao: AlbumDao,
	private val downloadDao: DownloadDao,
	private val syncManager: SyncManager,
	private val dbRepository: DbRepository
) {
	/**
	 * A whole-library album list: rows only, sorted and filtered in SQL (row 4a). It used to read
	 * every album WITH every song (`AlbumWithSongs`, the whole library twice over once the Songs
	 * chip had been opened too, since both view models live for the process) and filter in Kotlin.
	 * The rows come back in the same order as before (`SongAndAlbumListSqlTest`).
	 */
	private suspend fun getLocalData(
		listType: DomainAlbumListType,
		reversed: Boolean,
		filters: Set<DomainFilter> = emptySet()
	): ImmutableList<DomainAlbum> {
		val downloadedSongIdsJson = if (filters.contains(DomainFilter.Downloaded)) {
			idsAsJson(downloadDao.getSongIdsByStatus(DownloadStatus.DOWNLOADED))
		} else null

		// Random is still a shuffle of the A–Z rows, not `ORDER BY RANDOM()`: one random order
		// per read, the same as before. (Rows carry no relation now, so RANDOM() would be safe;
		// the shuffle is kept so nothing about the draw changes.)
		val query = (if (listType == DomainAlbumListType.Random) DomainAlbumListType.AlphabeticalByName else listType)
			.albumListSql(
				starredOnly = filters.contains(DomainFilter.Starred),
				downloadedSongIdsJson = downloadedSongIdsJson,
				select = "albumId"
			)
		val ids = albumDao.getAlbumIdsByQuery(query.toRoomRawQuery())
		val rows = rowsInOrder(ids, { albumDao.getAllAlbumRows() }, { albumDao.getAlbumRowsByIds(it) }) { it.albumId }
			.map { it.toDomainModel() }
			.let { if (listType == DomainAlbumListType.Random) it.shuffled() else it }

		return (if (reversed) rows.asReversed() else rows).toImmutableList()
	}

	/** One album with its songs: what a list row's sheet acts on (the rows carry none). */
	suspend fun getAlbumWithSongs(albumId: String): DomainAlbum? = withContext(Dispatchers.IO) {
		albumDao.getAlbumById(albumId)?.toDomainModel()
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

	/** navi-connect: the library's albums for a handful of ids (a recommendation row), in one query. */
	suspend fun getAlbumsByIds(ids: List<String>): List<DomainAlbum> = withContext(Dispatchers.IO) {
		if (ids.isEmpty()) return@withContext emptyList()
		albumDao.getAlbumsByIds(ids).map { it.toDomainModel() }
	}

	suspend fun isAlbumStarred(album: DomainAlbum) = albumDao.isAlbumStarred(album.id)
	suspend fun getAlbumRating(album: DomainAlbum) = albumDao.getAlbumRating(album.id) ?: 0

	suspend fun starAlbum(album: DomainAlbum) {
		val now = Clock.System.now()
		if (albumDao.setAlbumStarredAt(album.id, now.toEpochMilliseconds()) == 0) {
			albumDao.insertAlbum(album.toEntity().copy(starredAt = now))
		}
		syncManager.enqueueAction(SyncActionType.STAR, album.id)
	}

	suspend fun unstarAlbum(album: DomainAlbum) {
		if (albumDao.setAlbumStarredAt(album.id, null) == 0) {
			albumDao.insertAlbum(album.toEntity().copy(starredAt = null))
		}
		syncManager.enqueueAction(SyncActionType.UNSTAR, album.id)
	}

	suspend fun rateAlbum(album: DomainAlbum, rating: Int) {
		if (albumDao.setAlbumRating(album.id, rating) == 0) {
			albumDao.insertAlbum(album.toEntity().copy(userRating = rating))
		}
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
