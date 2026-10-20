package paige.navic.data.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow
import paige.navic.data.database.entities.PlaylistEntity
import paige.navic.data.database.entities.PlaylistSongCrossRef
import paige.navic.data.database.relations.PlaylistWithSongs
import paige.navic.util.Logger

@Dao
interface PlaylistDao {
	// Upsert, never REPLACE: a REPLACE deletes the row before inserting it, and that delete
	// cascades into PlaylistSongCrossRef, so every library sync emptied every playlist until its
	// songs were fetched again minutes later (or never, when the sync failed in between).
	@Upsert
	suspend fun insertPlaylist(playlist: PlaylistEntity)

	@Upsert
	suspend fun insertPlaylists(playlists: List<PlaylistEntity>)

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun insertPlaylistSongCrossRefs(crossRefs: List<PlaylistSongCrossRef>)

	@Transaction
	@Query("SELECT * FROM PlaylistEntity ORDER BY name ASC")
	suspend fun getAllPlaylistsByName(): List<PlaylistWithSongs>

	@Transaction
	@Query("SELECT * FROM PlaylistEntity ORDER BY createdAt DESC")
	suspend fun getAllPlaylistsByDateAdded(): List<PlaylistWithSongs>

	@Transaction
	@Query("SELECT * FROM PlaylistEntity ORDER BY duration DESC")
	suspend fun getAllPlaylistsByDuration(): List<PlaylistWithSongs>

	@Transaction
	@Query("SELECT * FROM PlaylistEntity ORDER BY RANDOM()")
	suspend fun getAllPlaylistsRandom(): List<PlaylistWithSongs>

	@Transaction
	@Query("SELECT * FROM PlaylistEntity ORDER BY name ASC")
	fun getAllPlaylistsFlow(): Flow<List<PlaylistWithSongs>>

	@Transaction
	@Query("SELECT * FROM PlaylistEntity WHERE playlistId = :playlistId LIMIT 1")
	suspend fun getPlaylistById(playlistId: String): PlaylistWithSongs?

	@Query("DELETE FROM PlaylistEntity WHERE playlistId = :playlistId")
	suspend fun deletePlaylist(playlistId: String)

	@Query("DELETE FROM PlaylistSongCrossRef WHERE playlistId = :playlistId")
	suspend fun deletePlaylistSongCrossRefs(playlistId: String)

	@Transaction
	suspend fun replacePlaylistSongs(playlistId: String, crossRefs: List<PlaylistSongCrossRef>) {
		deletePlaylistSongCrossRefs(playlistId)
		insertPlaylistSongCrossRefs(crossRefs)
	}

	/** Every playlist, without its songs (P5, the Library's Recents: a playlist's clock is its row). */
	@Query("SELECT * FROM PlaylistEntity")
	fun getPlaylistRows(): Flow<List<PlaylistEntity>>

	@Query("SELECT COUNT(*) FROM PlaylistEntity")
	suspend fun getPlaylistCount(): Int

	@Query("DELETE FROM PlaylistEntity")
	suspend fun clearAllPlaylists()

	@Query("SELECT playlistId FROM PlaylistEntity")
	suspend fun getAllPlaylistIds(): List<String>

	@Transaction
	@Query("SELECT * FROM PlaylistEntity WHERE name LIKE '%' || :query || '%' COLLATE NOCASE")
	suspend fun searchPlaylistsList(query: String): List<PlaylistWithSongs>

	@Transaction
	suspend fun updateAllPlaylists(remotePlaylists: List<PlaylistEntity>) {
		val remoteIds = remotePlaylists.map { it.playlistId }.toSet()
		getAllPlaylistIds().forEach { localId ->
			if (localId !in remoteIds) {
				Logger.w("PlaylistDao", "playlist $localId no longer exists remotely")
				deletePlaylist(localId)
			}
		}
		insertPlaylists(remotePlaylists)
	}

	@Transaction
	suspend fun deleteObsoletePlaylists(remoteIds: Set<String>) {
		getAllPlaylistIds().forEach { localId ->
			if (localId !in remoteIds) {
				Logger.w("PlaylistDao", "playlist $localId no longer exists remotely")
				deletePlaylist(localId)
			}
		}
	}
}
