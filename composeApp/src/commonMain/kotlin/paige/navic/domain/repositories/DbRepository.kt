package paige.navic.domain.repositories

import androidx.room3.concurrent.AtomicInt
import dev.zt64.subsonic.api.model.Album as ApiAlbum
import dev.zt64.subsonic.api.model.AlbumListType as ApiAlbumListType
import dev.zt64.subsonic.api.model.SubsonicException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.info_syncing
import navic.composeapp.generated.resources.info_syncing_albums
import navic.composeapp.generated.resources.info_syncing_artists
import navic.composeapp.generated.resources.info_syncing_finished
import navic.composeapp.generated.resources.info_syncing_genres
import navic.composeapp.generated.resources.info_syncing_playlists
import navic.composeapp.generated.resources.info_syncing_radios
import navic.composeapp.generated.resources.info_syncing_saved
import navic.composeapp.generated.resources.title_sync_control
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import paige.navic.data.database.dao.AlbumDao
import paige.navic.data.database.dao.ArtistDao
import paige.navic.data.database.dao.GenreDao
import paige.navic.data.database.dao.LyricDao
import paige.navic.data.database.dao.PlaylistDao
import paige.navic.data.database.dao.RadioDao
import paige.navic.data.database.dao.SongDao
import paige.navic.data.database.dao.SyncActionDao
import paige.navic.data.database.entities.AlbumEntity
import paige.navic.data.database.entities.PlaylistEntity
import paige.navic.data.database.entities.PlaylistSongCrossRef
import paige.navic.data.database.entities.SongEntity
import paige.navic.data.database.mappers.toDomainModel
import paige.navic.data.database.mappers.toEntity
import paige.navic.domain.manager.NotificationIds
import paige.navic.domain.manager.NotificationManager
import paige.navic.domain.manager.SessionManager
import paige.navic.domain.models.DomainArtist
import paige.navic.util.Logger
import kotlin.coroutines.cancellation.CancellationException

class DbRepository(
	private val albumDao: AlbumDao,
	private val playlistDao: PlaylistDao,
	private val songDao: SongDao,
	private val genreDao: GenreDao,
	private val artistDao: ArtistDao,
	private val radioDao: RadioDao,
	private val lyricDao: LyricDao,
	private val syncDao: SyncActionDao,
	private val sessionManager: SessionManager,
	private val notificationManager: NotificationManager
) {
	private val concurrentRequestLimit = Semaphore(20)

	private val dbChunkSize = 500 // should be enough

	private suspend fun <T> runDbOp(block: suspend () -> T): Result<T> =
		withContext(Dispatchers.IO) {
			try {
				Result.success(block())
			} catch (e: Exception) {
				if (e is CancellationException) throw e
				Result.failure(e)
			}
		}

	suspend fun removeEverything(): Result<Unit> = runDbOp {
		albumDao.clearAllAlbums()
		playlistDao.clearAllPlaylists()
		songDao.clearAllSongs()
		genreDao.clearAllGenres()
		artistDao.clearAllArtists()
		radioDao.clearAllRadios()
		lyricDao.clearAllLyrics()
		syncDao.clearAllActions()
		Logger.i("DbRepository", "Database wiped completely.")
	}

	suspend fun syncEverything(
		onProgress: (Float, StringResource) -> Unit = { _, _ -> }
	): Result<Unit> = runDbOp {
		// Deliberately not logged. This fires once per album, tens of times a second on a full
		// pull, and each line was a bare float plus a StringResource's toString — no diagnostic
		// value, and enough volume to bury every other tag in logcat. The per-section
		// "- X Synced" lines below carry the actual progress information. Upstream's Logger.i
		// here is deliberately not taken; its progress NOTIFICATION below is.
		coroutineScope {
			val progressCallback = suspend { progress: Float, message: StringResource ->
				onProgress(progress, message)

				notificationManager.showProgressNotification(
					id = NotificationIds.SYNC_LIBRARY,
					title = "${getString(Res.string.title_sync_control)} (${(progress * 100).toInt()}%)",
					message = getString(message),
					progress = progress,
					indeterminate = progress <= 0f
				)
			}

			try {
				progressCallback(0.0f, Res.string.info_syncing)

				progressCallback(0.01f, Res.string.info_syncing_genres)
				syncGenres().getOrThrow()

				progressCallback(0.02f, Res.string.info_syncing_radios)
				try {
					syncRadios().getOrThrow()
				} catch (ex: SubsonicException) {
					Logger.e(
						tag = "DbRepository",
						msg = "could not sync radio stations, maybe this server doesn't support it",
						tr = ex
					)
				}

				progressCallback(0.04f, Res.string.info_syncing_artists)
				syncArtists().getOrThrow()

				progressCallback(0.07f, Res.string.info_syncing_playlists)
				val playlists = syncPlaylists().getOrThrow()

				val validAlbumIds = mutableSetOf<String>()
				val validSongIds = mutableSetOf<String>()

				val libraryResult = syncLibrarySongs { localProgress, message ->
					val globalProgress = 0.10f + (localProgress * 0.65f)
					progressCallback(globalProgress, message)
				}.getOrThrow()

				validAlbumIds.addAll(libraryResult.first)
				validSongIds.addAll(libraryResult.second)

				val totalPlaylists = playlists.size
				if (totalPlaylists > 0) {
					val completedPlaylists = AtomicInt(0)

					playlists.map { playlist ->
						async {
							concurrentRequestLimit.withPermit {
								val playlistSongIds =
									syncPlaylistSongs(playlist.playlistId).getOrThrow()
								validSongIds.addAll(playlistSongIds)

								val done = completedPlaylists.incrementAndGet()
								val globalProgress = 0.75f + (0.25f * (done.toFloat() / totalPlaylists))
								progressCallback(globalProgress, Res.string.info_syncing_playlists)
							}
						}
					}.awaitAll()
				}

				albumDao.deleteObsoleteAlbums(validAlbumIds)
				songDao.deleteObsoleteSongs(validSongIds)

				progressCallback(1.0f, Res.string.info_syncing_finished)
			} finally {
				notificationManager.cancelNotification(NotificationIds.SYNC_LIBRARY)
			}
		}
	}

	suspend fun syncLibrarySongs(
		onProgress: suspend (Float, StringResource) -> Unit = { _, _ -> }
	): Result<Pair<Set<String>, Set<String>>> = runDbOp {
		val pageSize = 500
		var offset = 0
		val allAlbumSummaries = mutableListOf<ApiAlbum>()

		onProgress(0.0f, Res.string.info_syncing_albums)
		while (true) {
			val batch =
				sessionManager.api.getAlbums(ApiAlbumListType.AlphabeticalByName, pageSize, offset)
			if (batch.isEmpty()) break
			allAlbumSummaries.addAll(batch)
			if (batch.size < pageSize) break
			offset += pageSize
		}

		if (allAlbumSummaries.isEmpty()) return@runDbOp emptySet<String>() to emptySet()

		val totalAlbums = allAlbumSummaries.size
		val completedAlbums = AtomicInt(0)
		val failedAlbums = AtomicInt(0)
		var finalSongsSynced = 0

		val allValidAlbumIds = mutableSetOf<String>()
		val allValidSongIds = mutableSetOf<String>()

		onProgress(0.1f, Res.string.info_syncing_albums)

		val albumChannel = Channel<ApiAlbum>(capacity = 100)

		coroutineScope {
			launch(Dispatchers.IO) {
				allAlbumSummaries.map { summary ->
					launch {
						concurrentRequestLimit.withPermit {
							try {
								// Retry once on transient failures (timeouts
								// through the tunnel etc.); skip the album on a
								// second failure rather than aborting the whole
								// sync — which previously meant new albums were
								// never inserted at all.
								val album = try {
									sessionManager.api.getAlbum(summary.id)
								} catch (first: Exception) {
									if (first is SerializationException) throw first
									Logger.e("DbRepository", "retrying album ${summary.id} (${summary.name}): ${first.message}")
									sessionManager.api.getAlbum(summary.id)
								}

								val done = completedAlbums.incrementAndGet()
								// Thousands of getAlbum calls used to log nothing, so a stall read the same as a failure.
								if (done % 250 == 0) Logger.i("DbRepository", "- Albums fetched: $done/$totalAlbums")
								val fetchProgress = 0.1f + (0.8f * (done.toFloat() / totalAlbums))
								onProgress(fetchProgress, Res.string.info_syncing_albums)

								albumChannel.send(album)
							} catch (e: Exception) {
								completedAlbums.incrementAndGet()
								failedAlbums.incrementAndGet()
								Logger.e("DbRepository", "skipping album ${summary.id} (${summary.name}) after failure", e)
							}
						}
					}
				}.joinAll()
				albumChannel.close()
			}

			launch(Dispatchers.IO) {
				val albumBatch = mutableListOf<AlbumEntity>()
				val songBatch = mutableListOf<SongEntity>()
				val summariesMap = allAlbumSummaries.associateBy { it.id }

				for (album in albumChannel) {
					val (albumEntity, songEntities) = album.toEntities(summariesMap[album.id])
					albumBatch.add(albumEntity)
					allValidAlbumIds.add(albumEntity.albumId)

					songEntities.forEach { songEntity ->
						songBatch.add(songEntity)
						allValidSongIds.add(songEntity.songId)
					}

					if (albumBatch.size >= dbChunkSize || songBatch.size >= 1500) {
						albumDao.insertAlbums(albumBatch)
						songDao.insertSongs(songBatch)

						finalSongsSynced += songBatch.size
						albumBatch.clear()
						songBatch.clear()
					}
				}

				if (albumBatch.isNotEmpty() || songBatch.isNotEmpty()) {
					if (albumBatch.isNotEmpty()) albumDao.insertAlbums(albumBatch)
					if (songBatch.isNotEmpty()) songDao.insertSongs(songBatch)
					finalSongsSynced += songBatch.size
				}
			}
		}

		if (failedAlbums.get() == 0) {
			albumDao.deleteObsoleteAlbums(allValidAlbumIds)
			songDao.deleteObsoleteSongs(allValidSongIds)
		} else {
			Logger.w(
				"DbRepository",
				"skipping obsolete cleanup because ${failedAlbums.get()} album fetches failed"
			)
		}

		Logger.i(
			"DbRepository",
			"- Songs Synced: $totalAlbums albums, $finalSongsSynced songs"
		)

		onProgress(1.0f, Res.string.info_syncing_saved)
		allValidAlbumIds to allValidSongIds
	}

	/**
	 * One album and its songs as Room rows. Shared by the full pull and the targeted
	 * one below, so an album synced either way comes out identical.
	 *
	 * [summary] is the album-list entry when there is one; its artist wins over the
	 * detail call's, as it always has in the full pull.
	 */
	private fun ApiAlbum.toEntities(summary: ApiAlbum?): Pair<AlbumEntity, List<SongEntity>> {
		val albumEntity = toEntity(
			artistIdOverride = summary?.artistId,
			artistNameOverride = summary?.artistName
		)
		val songEntities = songs.map { song ->
			// The album's artist is a FALLBACK for a track that doesn't name one, not an
			// override. Forcing it onto every track threw away the credit the server
			// actually sent — on Navidrome the full "A feat. B" string — which is why a
			// featured artist was invisible everywhere in the app, and why the same song
			// could show a different artist depending on whether this sync or the
			// playlist/search path (neither of which overrides) wrote the row last.
			// Still preferred over the mapper's "unknown artist" default, which would
			// otherwise leave such a track with no artist page to open.
			song.toEntity(
				artistIdOverride = song.artistId?.takeIf { it.isNotBlank() }
					?: albumEntity.artistId,
				artistNameOverride = song.artistName?.takeIf { it.isNotBlank() }
					?: albumEntity.artistName
			)
		}
		return albumEntity to songEntities
	}

	/**
	 * Pull exactly these albums into Room — the landing path for an lb-bot fill.
	 *
	 * A fill used to buy a full library pull (one `getAlbum` per album in the library)
	 * after a 90 s debounce, which is minutes between Navidrome having an album and this
	 * app showing it. lb-bot now names the album ids once Navidrome has indexed them, so
	 * the same result costs one request per album.
	 *
	 * Upsert only. An album that gained tracks (a gap fill) is re-read whole, and
	 * nothing here deletes: removals stay the full sync's job.
	 */
	suspend fun syncAlbumsById(ids: Collection<String>): Result<Int> = runDbOp {
		val wanted = ids.filter { it.isNotBlank() }.distinct()
		if (wanted.isEmpty()) return@runDbOp 0
		val albums = coroutineScope {
			wanted.map { id ->
				async {
					concurrentRequestLimit.withPermit {
						try {
							sessionManager.api.getAlbum(id)
						} catch (e: Exception) {
							if (e is CancellationException) throw e
							Logger.w("DbRepository", "targeted sync: album $id failed: ${e.message}")
							null
						}
					}
				}
			}.awaitAll().filterNotNull()
		}
		if (albums.isEmpty()) error("none of ${wanted.size} album(s) could be fetched")
		val entities = albums.map { it.toEntities(it) }
		albumDao.insertAlbums(entities.map { it.first })
		entities.flatMap { it.second }.chunked(1500).forEach { songDao.insertSongs(it) }
		Logger.i("DbRepository", "- Targeted sync: ${albums.size} album(s)")
		albums.size
	}

	/**
	 * Pull albums Navidrome added since the last sync, for a landing that didn't name
	 * its album ids (an older lb-bot, or files placed some other way).
	 *
	 * Walks the `newest` list until a page is entirely known to Room, bounded by
	 * [maxPages] — a first run on a stale cache is the full sync's job, not this one's.
	 */
	suspend fun syncNewestAlbums(pageSize: Int = 50, maxPages: Int = 4): Result<Int> = runDbOp {
		val known = albumDao.getAllAlbumIds().toHashSet()
		val unknown = mutableListOf<String>()
		var offset = 0
		for (page in 0 until maxPages) {
			val batch = sessionManager.api.getAlbums(ApiAlbumListType.Newest, pageSize, offset)
			val fresh = batch.map { it.id }.filterNot { it in known }
			unknown += fresh
			if (fresh.isEmpty() || batch.size < pageSize) break
			offset += pageSize
		}
		if (unknown.isEmpty()) 0 else syncAlbumsById(unknown).getOrThrow()
	}

	/**
	 * Bring Room up to date with whatever changed in Navidrome, from anywhere.
	 *
	 * The lb-bot events only cover fills lb-bot announces, and only while the hub is
	 * reachable: an album downloaded from lb-bot's own page, a gap filled from Feishin, or
	 * files matched by hand arrived in Navidrome and stayed invisible here until the hourly
	 * full sync. Worse for a gap fill: the lb-bot row said the album was whole again while
	 * Room still held the old tracklist, and [syncNewestAlbums] can't see it because the
	 * album isn't new.
	 *
	 * Cheap by construction: the album *list* is a handful of paged requests even for a
	 * large library (the full sync's cost is one `getAlbum` per album), and only albums that
	 * are new, or whose track count or cover changed, are re-read. Above [maxChanged] it
	 * gives up and leaves the job to the full sync. Upsert only; removals stay its job too.
	 */
	suspend fun syncChangedAlbums(maxChanged: Int = 300): Result<Int> = runDbOp {
		val pageSize = 500
		val remote = mutableListOf<ApiAlbum>()
		var offset = 0
		while (true) {
			val batch = sessionManager.api.getAlbums(ApiAlbumListType.AlphabeticalByName, pageSize, offset)
			remote += batch
			if (batch.size < pageSize) break
			offset += pageSize
		}
		val local = albumDao.getAlbumFingerprints().associateBy { it.albumId }
		val changed = remote.filter { album ->
			val known = local[album.id]
			known == null || known.songCount != album.songCount || known.coverArtId != album.coverArtId
		}.map { it.id }
		when {
			changed.isEmpty() -> 0
			changed.size > maxChanged -> {
				Logger.i("DbRepository", "- ${changed.size} changed albums: leaving it to the full sync")
				0
			}
			else -> {
				Logger.i("DbRepository", "- Changed albums: ${changed.size}")
				syncAlbumsById(changed).getOrThrow()
			}
		}
	}

	suspend fun syncPlaylists(): Result<List<PlaylistEntity>> = runDbOp {
		val remotePlaylists = sessionManager.api.getPlaylists()
		val playlistEntities = remotePlaylists.map { it.toEntity() }
		val validPlaylistIds = playlistEntities.map { it.playlistId }.toSet()

		playlistEntities.chunked(dbChunkSize).forEach { chunk ->
			playlistDao.insertPlaylists(chunk)
		}

		playlistDao.deleteObsoletePlaylists(validPlaylistIds)

		Logger.i("DbRepository", "- Playlists Synced: ${playlistEntities.size} playlists found")

		playlistEntities
	}

	suspend fun syncPlaylistSongs(playlistId: String): Result<Set<String>> = runDbOp {
		val playlist = try {
			sessionManager.api.getPlaylist(playlistId)
		} catch (e: Exception) {
			if (e is SerializationException) {
				Logger.e(
					"DbRepository",
					"could not deserialize playlist $playlistId; skipping it",
					e
				)
				return@runDbOp emptySet<String>()
			} else {
				throw e
			}
		}
		val songEntities = playlist.songs.map { it.toEntity() }
		val songIds = songEntities.map { it.songId }.toSet()

		if (songEntities.isNotEmpty()) {
			songEntities.chunked(dbChunkSize).forEach { chunk ->
				songDao.insertSongs(chunk)
			}

			val crossRefs = songEntities.mapIndexed { index, it ->
				PlaylistSongCrossRef(playlistId = playlistId, songId = it.songId, position = index)
			}

			playlistDao.replacePlaylistSongs(playlistId, crossRefs)
		} else {
			playlistDao.deletePlaylistSongCrossRefs(playlistId)
		}

		Logger.i("DbRepository", "- Playlist [$playlistId] synced: ${songEntities.size} songs")
		songIds
	}

	suspend fun syncGenres(): Result<Unit> = runDbOp {
		val remoteGenres = sessionManager.api.getGenres()
		val entities = remoteGenres.map { it.toEntity() }

		entities.chunked(dbChunkSize).forEach { chunk ->
			genreDao.insertGenres(chunk)
		}
		genreDao.deleteObsoleteGenres(entities.map { it.genreName }.toSet())

		Logger.i("DbRepository", "- Genres Synced: ${entities.size} genres found")
	}

	suspend fun syncArtists(): Result<Unit> = runDbOp {
		// ALBUM artists via getArtists (canonical list, like Feishin). search3
		// returns every track/featured artist too — a 7k mess — and its
		// albumCount is populated for those, so an albumCount filter doesn't
		// distinguish them. fetchAlbumArtists hits getArtists with a lenient
		// raw parse (the library's getArtists deserializer was broken).
		val flatArtists = sessionManager.fetchAlbumArtists()
		val entities = flatArtists.map { it.toEntity() }

		entities.chunked(dbChunkSize).forEach { chunk ->
			artistDao.insertArtists(chunk)
		}
		artistDao.deleteObsoleteArtists(entities.map { it.artistId }.toSet())

		Logger.i("DbRepository", "- Artists Synced: ${entities.size} artists found")
	}

	suspend fun syncRadios(): Result<Unit> = runDbOp {
		val remoteRadios = sessionManager.api.getInternetRadioStations()
		val entities = remoteRadios.map { it.toEntity() }

		entities.chunked(dbChunkSize).forEach { chunk ->
			radioDao.insertRadios(chunk)
		}
		radioDao.deleteObsoleteRadios(entities.map { it.radioId }.toSet())

		Logger.i("DbRepository", "- Radios Synced: ${entities.size} stations found")
	}

	/**
	 * Biography, Last.fm link and similar artists for one artist.
	 *
	 * Goes through [SessionManager.fetchArtistInfo2], not the bundled client. The
	 * client's `getArtistInfo` is the **non-ID3** endpoint and was being handed
	 * ID3 ids — Navidrome answers, so nothing ever errored; it simply answered
	 * about the wrong thing. Its `getArtistInfoID3` is not a fix either: it
	 * issues `getArtistInfo` too.
	 *
	 * The ID3 endpoint also returns full `similarArtist[]` entries rather than
	 * bare ids. Only the ones carrying an `id` are in the library and can be
	 * stored here; an entry with no id is an artist the library does not have,
	 * which is a discovery lead rather than noise — but this table holds local
	 * ids, so it is filtered out at the boundary rather than stored as "".
	 */
	suspend fun fetchArtistMetadata(artistId: String): Result<DomainArtist> = runDbOp {
		val artistInfo = sessionManager.fetchArtistInfo2(artistId)
			?: throw Exception("Artist info unavailable")
		val simIds = artistInfo.similarArtist.map { it.id }.filter { it.isNotBlank() }

		val currentEntity = artistDao.getArtistById(artistId)
			?: throw Exception("Artist not found in local DB")

		val updatedEntity = currentEntity.copy(
			biography = artistInfo.biography,
			similarArtistIds = simIds,
			lastFmUrl = artistInfo.lastFmUrl
		)

		artistDao.insertArtist(updatedEntity)

		updatedEntity.toDomainModel()
	}
}
