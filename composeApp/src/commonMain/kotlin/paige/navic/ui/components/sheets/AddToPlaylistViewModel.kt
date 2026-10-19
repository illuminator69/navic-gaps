package paige.navic.ui.components.sheets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import paige.navic.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import paige.navic.data.database.dao.PlaylistDao
import paige.navic.data.database.mappers.toDomainModel
import paige.navic.domain.manager.SessionManager
import paige.navic.domain.models.DomainPlaylist
import paige.navic.domain.models.DomainSong
import paige.navic.domain.repositories.DbRepository

/*
 * navi-connect P2 notes (you, 2026-10-19): "Save to playlist" from the player. One song, every
 * playlist it can go in, a + per playlist that adds it and turns into a bin that takes it out again,
 * and the sheet stays open so one song can go into as many playlists as you like. A playlist made
 * from the sheet comes first. Every add and remove is one `updatePlaylist` on the server; the
 * local cache of that playlist is synced after it.
 */

/** One playlist in the sheet: whether it holds the song, and whether a change to it is on its way. */
data class PlaylistChoice(val playlist: DomainPlaylist, val holdsSong: Boolean, val busy: Boolean = false)

/**
 * The sheet's rows: the playlists made from this sheet first (newest first), then the rest by name.
 * Read-only playlists (smart playlists, other people's) are left out: nothing can be added to them.
 */
fun playlistChoices(
	playlists: List<DomainPlaylist>,
	songId: String,
	createdHere: List<String>,
	holding: Map<String, Boolean> = emptyMap(),
	busy: Set<String> = emptySet()
): List<PlaylistChoice> {
	val editable = playlists.filter { it.readOnly != true }
	val byId = editable.associateBy { it.id }
	val first = createdHere.asReversed().mapNotNull { byId[it] }
	val rest = editable.filter { it.id !in createdHere }.sortedBy { (it.name ?: "").lowercase() }
	return (first + rest).map { p ->
		PlaylistChoice(
			playlist = p,
			holdsSong = holding[p.id] ?: p.songs.any { it.id == songId },
			busy = p.id in busy
		)
	}
}

class AddToPlaylistViewModel(
	private val song: DomainSong,
	private val playlistDao: PlaylistDao,
	private val dbRepository: DbRepository,
	private val sessionManager: SessionManager
) : ViewModel() {
	private val playlists = MutableStateFlow<List<DomainPlaylist>?>(null)
	private val createdHere = MutableStateFlow<List<String>>(emptyList())
	/** What this sheet has done, over the cache's word, until the cache catches up. */
	private val holding = MutableStateFlow<Map<String, Boolean>>(emptyMap())
	private val busy = MutableStateFlow<Set<String>>(emptySet())

	/** The rows, or null while the playlists are first read. */
	val choices: StateFlow<List<PlaylistChoice>?>
		field = MutableStateFlow<List<PlaylistChoice>?>(null)

	init {
		viewModelScope.launch {
			publish(readLocal())
			// The cache may be behind the server: read the list again, then show it.
			dbRepository.syncPlaylists().onSuccess { publish(readLocal()) }
		}
	}

	private suspend fun readLocal(): List<DomainPlaylist> =
		playlistDao.getAllPlaylistsByName().map { it.toDomainModel() }

	private fun publish(list: List<DomainPlaylist>? = playlists.value) {
		playlists.value = list
		choices.value = list?.let { playlistChoices(it, song.id, createdHere.value, holding.value, busy.value) }
	}

	fun add(playlistId: String) = change(playlistId, holds = true) {
		sessionManager.api.updatePlaylist(playlistId, songIdsToAdd = listOf(song.id))
	}

	/** Takes out the last copy of the song in the playlist: the one an add just put at the end. */
	fun remove(playlistId: String) = change(playlistId, holds = false) {
		val songs = sessionManager.api.getPlaylist(playlistId).songs
		val index = songs.indexOfLast { it.id == song.id }
		if (index >= 0) sessionManager.api.updatePlaylist(playlistId, songIndicesToRemove = listOf(index))
	}

	private fun change(playlistId: String, holds: Boolean, call: suspend () -> Unit) {
		if (playlistId in busy.value) return
		busy.update { it + playlistId }
		publish()
		viewModelScope.launch {
			try {
				call()
				holding.update { it + (playlistId to holds) }
				dbRepository.syncPlaylistSongs(playlistId)
				dbRepository.syncPlaylists()
				playlists.value = readLocal()
			} catch (e: Exception) {
				Logger.e("AddToPlaylist", "playlist $playlistId: ${if (holds) "add" else "remove"} failed", e)
			} finally {
				busy.update { it - playlistId }
				publish()
			}
		}
	}

	/** A playlist made from the sheet, already holding the song: shown first. */
	fun onCreated(playlist: DomainPlaylist) {
		createdHere.update { (it - playlist.id) + playlist.id }
		holding.update { it + (playlist.id to true) }
		viewModelScope.launch {
			val list = readLocal()
			publish(if (list.any { it.id == playlist.id }) list else list + playlist)
		}
	}
}
