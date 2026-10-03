package paige.navic.ui.screens.settings.viewmodels

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import paige.navic.data.database.dao.AlbumDao
import paige.navic.data.database.dao.ArtistDao
import paige.navic.data.database.dao.PlaylistDao
import paige.navic.data.database.dao.SongDao
import paige.navic.data.database.dao.SyncActionDao
import paige.navic.data.database.entities.SyncActionEntity
import paige.navic.data.database.entities.SyncActionType
import paige.navic.domain.manager.ConnectivityManager
import paige.navic.domain.manager.PendingActionKind
import paige.navic.domain.manager.SyncManager
import paige.navic.domain.manager.formatListenedAt
import paige.navic.domain.manager.pendingActionKind
import paige.navic.domain.manager.pendingActionTitle
import paige.navic.util.Logger

/** One queued action as the browser shows it. [id] is the queue row's own key. */
@Immutable
data class PendingActionRow(
	val id: Int,
	val kind: PendingActionKind,
	/** The item's title out of Room, else its id. */
	val title: String,
	/** When it was queued, in the device's zone. */
	val queuedAt: String
)

/**
 * The pending-actions browser (I-020): what is still waiting to be sent to the server — stars,
 * ratings, scrobbles and playlist deletes made while it could not be reached.
 *
 * The list is the queue table observed, so a row leaves it the moment the sync sends it or the
 * user removes it. Titles come from the local tables and fall back to the id: an action on
 * something Room no longer holds still has to say which thing it was.
 *
 * **Removing a row and a sync draining the queue do not disturb each other.**
 * `SyncManager.processQueue` reads the queue once and then walks its own copy, deleting each row
 * by id after it is sent. A delete from here is the same statement on one id: it cannot fail the
 * drain's delete of that row (deleting an absent id is a no-op), reorder what the drain holds, or
 * skip a row. The one consequence worth knowing: an action the drain has already read may still
 * be sent in the moment after it is removed here.
 */
class PendingActionsViewModel(
	private val syncDao: SyncActionDao,
	private val songDao: SongDao,
	private val albumDao: AlbumDao,
	private val artistDao: ArtistDao,
	private val playlistDao: PlaylistDao,
	private val syncManager: SyncManager,
	connectivityManager: ConnectivityManager
) : ViewModel() {
	val isOnline = connectivityManager.isOnline

	/** Null until Room has answered once: "nothing waiting" is only said of a queue that was read. */
	val rows: StateFlow<List<PendingActionRow>?> = syncDao.observePendingActions()
		.map { actions -> rowsFor(actions) }
		.flowOn(Dispatchers.IO)
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

	private suspend fun rowsFor(actions: List<SyncActionEntity>): List<PendingActionRow> {
		val titles = titlesFor(actions)
		val zone = TimeZone.currentSystemDefault()
		return actions.map { action ->
			PendingActionRow(
				id = action.id,
				kind = pendingActionKind(action.actionType),
				title = pendingActionTitle(action.itemId, titles),
				queuedAt = formatListenedAt(action.time.epochSeconds.toDouble(), zone)
			)
		}
	}

	/**
	 * Titles for the ids in the queue, from whichever local table holds each. A star or a rating
	 * can be on a song, an album or an artist, and the queue row does not say which, so each id
	 * is looked for in that order. A scrobble is always a song, a delete always a playlist.
	 */
	private suspend fun titlesFor(actions: List<SyncActionEntity>): Map<String, String> {
		val titles = mutableMapOf<String, String>()
		val playlistIds = actions
			.filter { it.actionType == SyncActionType.DELETE_PLAYLIST }
			.mapTo(mutableSetOf()) { it.itemId }
		var unresolved = actions.mapTo(mutableSetOf()) { it.itemId } - playlistIds
		try {
			// Chunked: a queue grown over a long time offline can pass SQLite's variable limit.
			unresolved.chunked(ID_CHUNK).forEach { chunk ->
				songDao.getSongsByIds(chunk).forEach { song ->
					titles[song.songId] = listOfNotNull(song.title, song.artistName?.ifBlank { null })
						.joinToString(" • ")
				}
			}
			unresolved = unresolved - titles.keys
			unresolved.chunked(ID_CHUNK).forEach { chunk ->
				albumDao.getAlbumsByIds(chunk).forEach { titles[it.album.albumId] = it.album.name.orEmpty() }
			}
			unresolved = unresolved - titles.keys
			unresolved.chunked(ID_CHUNK).forEach { chunk ->
				artistDao.getArtistsByIds(chunk).forEach { titles[it.artistId] = it.name }
			}
			playlistIds.forEach { id ->
				playlistDao.getPlaylistById(id)?.let { titles[id] = it.playlist.name.orEmpty() }
			}
		} catch (e: kotlin.coroutines.cancellation.CancellationException) {
			throw e
		} catch (e: Exception) {
			// The rows still render, by id.
			Logger.w("PendingActionsViewModel", "title lookup failed", e)
		}
		return titles
	}

	/** Take one action out of the queue. It is not sent; see the class note on a running sync. */
	fun remove(id: Int) {
		viewModelScope.launch(Dispatchers.IO) { syncDao.removeAction(id) }
	}

	/** Send what is waiting now (and, as on Data & Storage, re-sync the library). */
	fun syncNow() {
		syncManager.triggerManualSync()
	}

	private companion object {
		const val ID_CHUNK = 500
	}
}
