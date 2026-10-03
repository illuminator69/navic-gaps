package paige.navic.ui.screens.settings.viewmodels

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import paige.navic.domain.manager.pendingTitlesToLookUp
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
 * be sent in the moment after it is removed here — the screen says so in a line, since a row
 * vanishing reads as "cancelled".
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

	/**
	 * Titles looked up so far, and every id that has been asked for, found or not. The queue
	 * re-emits on every row a drain deletes; without these each sent action cost the chunked
	 * Room reads for the whole queue again. Under [titlesLock]: the flow maps one emission at a
	 * time, but a restarted collection can overlap the one it replaces for a moment.
	 */
	private val titles = mutableMapOf<String, String>()
	private val titleIdsSeen = mutableSetOf<String>()
	private val titlesLock = Mutex()

	/** The last remove could not be written: the action is still queued, and the screen says so. */
	private val _removeFailed = MutableStateFlow(false)
	val removeFailed = _removeFailed.asStateFlow()

	private suspend fun rowsFor(actions: List<SyncActionEntity>): List<PendingActionRow> {
		val resolved = titlesFor(actions)
		val zone = TimeZone.currentSystemDefault()
		return actions.map { action ->
			PendingActionRow(
				id = action.id,
				kind = pendingActionKind(action.actionType),
				title = pendingActionTitle(action.itemId, resolved),
				queuedAt = formatListenedAt(action.time.epochSeconds.toDouble(), zone)
			)
		}
	}

	/**
	 * Titles for the ids in the queue, from whichever local table holds each. A star or a rating
	 * can be on a song, an album or an artist, and the queue row does not say which, so each id
	 * is looked for in that order. A scrobble is always a song, a delete always a playlist.
	 *
	 * Only ids not asked for before are looked up (`pendingTitlesToLookUp`); the rest come from
	 * what earlier emissions resolved. An id is marked as asked only once its lookups ran, so a
	 * failed read is tried again on the next emission.
	 */
	private suspend fun titlesFor(actions: List<SyncActionEntity>): Map<String, String> = titlesLock.withLock {
		val wanted = pendingTitlesToLookUp(actions.map { it.itemId }, titleIdsSeen)
		if (wanted.isEmpty()) return@withLock titles.toMap()
		val playlistIds = actions
			.filter { it.actionType == SyncActionType.DELETE_PLAYLIST }
			.mapTo(mutableSetOf()) { it.itemId }
			.intersect(wanted)
		var unresolved = wanted - playlistIds
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
			titleIdsSeen += wanted
		} catch (e: kotlin.coroutines.cancellation.CancellationException) {
			throw e
		} catch (e: Exception) {
			// The rows still render, by id, and these ids are asked for again next time.
			Logger.w("PendingActionsViewModel", "title lookup failed", e)
		}
		titles.toMap()
	}

	/**
	 * Take one action out of the queue. It is not sent; see the class note on a running sync.
	 * A delete that fails leaves the action queued, and [removeFailed] makes the screen say so
	 * rather than leave a tap with no visible result.
	 */
	fun remove(id: Int) {
		viewModelScope.launch(Dispatchers.IO) {
			_removeFailed.value = try {
				syncDao.removeAction(id)
				false
			} catch (e: kotlin.coroutines.cancellation.CancellationException) {
				throw e
			} catch (e: Exception) {
				Logger.w("PendingActionsViewModel", "could not remove a queued action", e)
				true
			}
		}
	}

	/** Send what is waiting now (and, as on Data & Storage, re-sync the library). */
	fun syncNow() {
		syncManager.triggerManualSync()
	}

	private companion object {
		const val ID_CHUNK = 500
	}
}
