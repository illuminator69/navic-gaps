package paige.navic.domain.repositories

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import paige.navic.data.database.entities.SongEntity
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import paige.navic.data.database.dao.AlbumDao
import paige.navic.data.database.dao.DownloadDao
import paige.navic.data.database.dao.SongDao
import paige.navic.data.database.entities.DownloadStatus
import paige.navic.data.database.entities.SyncActionType
import paige.navic.data.database.mappers.toDomainModel
import paige.navic.data.database.mappers.toEntity
import paige.navic.domain.manager.SyncManager
import paige.navic.domain.models.DomainFilter
import paige.navic.domain.models.DomainSong
import paige.navic.domain.models.DomainSongListType
import paige.navic.ui.core.UiState
import paige.navic.util.sortedByListType
import kotlin.time.Clock

/** A song was starred ([starred]) or unstarred on this device. */
data class StarChange(val songId: String, val starred: Boolean)

/**
 * The artist a song list is scoped to. Upstream's paging revert (d6671510) dropped the artist from
 * the song list, so an artist's "see all" songs opened the whole library; it is a type of its own
 * so Koin can tell it from the other parameters.
 */
data class SongListArtist(val id: String, val name: String = "")

class SongRepository(
	private val songDao: SongDao,
	private val albumDao: AlbumDao,
	private val downloadDao: DownloadDao,
	private val dbRepository: DbRepository,
	private val syncManager: SyncManager
) {
	private val _starChanges = MutableSharedFlow<StarChange>(extraBufferCapacity = 16)

	/**
	 * Every star given or taken back here, as it is made. A station bound to the queue is steered
	 * by it (`RadioManager`, a `like` / `unlike` signal): the star goes to Navidrome, which the
	 * hub's station engine never hears from (navi-connect PROTOCOL §18.1).
	 */
	val starChanges: SharedFlow<StarChange> = _starChanges.asSharedFlow()
	suspend fun getAllSongs(): List<DomainSong> {
		return songDao.getAllSongs().map { it.toDomainModel() }
	}

	suspend fun getRandomSongs(count: Int): List<DomainSong> {
		return songDao.getRandomSongs(count).map { it.toDomainModel() }
	}

	private suspend fun getLocalData(
		listType: DomainSongListType,
		reversed: Boolean,
		filters: Set<DomainFilter> = emptySet(),
		artist: SongListArtist? = null
	): ImmutableList<DomainSong> {
		val songs = (if (artist == null) songDao.getAllSongs() else artistSongs(artist))
			.map { it.toDomainModel() }

		val downloadedIds = if (filters.contains(DomainFilter.Downloaded)) {
			downloadDao.getAllDownloadsList()
				.filter { it.status == DownloadStatus.DOWNLOADED }
				.map { it.songId }
				.toSet()
		} else emptySet()

		val filteredByFilters = songs.filter { song ->
			filters.all { filter ->
				when (filter) {
					DomainFilter.Starred -> song.starredAt != null
					DomainFilter.Downloaded -> downloadedIds.contains(song.id)
				}
			}
		}

		val sorted = filteredByFilters
			.toImmutableList()
			.sortedByListType(
				listType,
				albums = albumDao.getAllAlbumsList().map { it.toDomainModel() }
			)

		return if (reversed) {
			sorted.reversed().toImmutableList()
		} else {
			sorted
		}
	}

	/**
	 * The songs on [artist]'s albums, as the artist page's Frequently played picks them (by the
	 * album's artist id, else by name for an album filed under another spelling).
	 */
	private suspend fun artistSongs(artist: SongListArtist): List<SongEntity> {
		val byId = albumDao.getAlbumsByArtist(artist.id).first()
		val albums = byId.ifEmpty {
			artist.name.takeIf { it.isNotBlank() }?.let { albumDao.getAlbumsByArtistName(it).first() }.orEmpty()
		}
		return albums.flatMap { it.songs }
	}

	private suspend fun refreshLocalData(
		listType: DomainSongListType,
		reversed: Boolean,
		filters: Set<DomainFilter>,
		artist: SongListArtist?
	): ImmutableList<DomainSong> {
		dbRepository.syncLibrarySongs().getOrThrow()
		return getLocalData(listType, reversed, filters, artist)
	}

	fun getSongsFlow(
		fullRefresh: Boolean,
		listType: DomainSongListType,
		reversed: Boolean,
		filters: Set<DomainFilter>,
		/** One artist's songs (an artist page's "See more"); null for the whole library. */
		artist: SongListArtist? = null
	): Flow<UiState<ImmutableList<DomainSong>>> = flow {
		val localData = getLocalData(listType, reversed, filters, artist)
		if (fullRefresh) {
			emit(UiState.Loading(data = localData))
			try {
				emit(UiState.Success(data = refreshLocalData(listType, reversed, filters, artist)))
			} catch (error: Exception) {
				emit(UiState.Error(error = error, data = localData))
			}
		} else {
			emit(UiState.Success(data = localData))
		}
	}.flowOn(Dispatchers.IO)

	suspend fun isSongStarred(song: DomainSong) = songDao.isSongStarred(song.id)
	suspend fun getSongRating(song: DomainSong) = songDao.getSongRating(song.id) ?: 0
	suspend fun starSong(song: DomainSong) {
		val starredEntity = song.toEntity().copy(
			starredAt = Clock.System.now()
		)
		songDao.insertSong(starredEntity)
		syncManager.enqueueAction(SyncActionType.STAR, song.id)
		_starChanges.tryEmit(StarChange(song.id, starred = true))
	}

	suspend fun unstarSong(song: DomainSong) {
		val unstarredEntity = song.toEntity().copy(
			starredAt = null
		)
		songDao.insertSong(unstarredEntity)
		syncManager.enqueueAction(SyncActionType.UNSTAR, song.id)
		_starChanges.tryEmit(StarChange(song.id, starred = false))
	}

	suspend fun rateSong(song: DomainSong, rating: Int) {
		val ratedEntity = song.toEntity().copy(
			userRating = rating
		)
		songDao.insertSong(ratedEntity)
		when (rating) {
			0 -> syncManager.enqueueAction(SyncActionType.STAR_0, song.id)
			1 -> syncManager.enqueueAction(SyncActionType.STAR_1, song.id)
			2 -> syncManager.enqueueAction(SyncActionType.STAR_2, song.id)
			3 -> syncManager.enqueueAction(SyncActionType.STAR_3, song.id)
			4 -> syncManager.enqueueAction(SyncActionType.STAR_4, song.id)
			5 -> syncManager.enqueueAction(SyncActionType.STAR_5, song.id)
		}
	}
}
