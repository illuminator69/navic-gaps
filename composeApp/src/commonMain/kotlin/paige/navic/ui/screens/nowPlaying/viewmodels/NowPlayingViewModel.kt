package paige.navic.ui.screens.nowPlaying.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import paige.navic.domain.models.SavedQueueSource
import paige.navic.domain.repositories.SavedQueueRepository
import paige.navic.domain.repositories.SongRepository
import paige.navic.ui.screens.nowPlaying.staged.PlayingFrom
import paige.navic.ui.screens.nowPlaying.staged.playingFromText
import paige.navic.shared.MediaPlayerViewModel
import kotlinx.coroutines.flow.StateFlow

class NowPlayingViewModel(
	private val player: MediaPlayerViewModel,
	private val songRepository: SongRepository,
	private val savedQueueRepository: SavedQueueRepository
) : ViewModel(), KoinComponent {
	val songIsStarred: StateFlow<Boolean>
		field = MutableStateFlow(false)

	val songRating: StateFlow<Int>
		field = MutableStateFlow(0)

	/**
	 * The staged player's "Playing from" (P2): the queue's saved-queue record, by the id the queue
	 * carries (local, or the hub session's when another device plays), named as the user renamed it
	 * or as it was born ([playingFromText]). Null when nothing is known. Read when the queue changes;
	 * a rename happens on the saved-queues page, with the player closed, and this view model lives
	 * with the player's entry.
	 */
	val playingFrom: StateFlow<PlayingFrom?>
		field = MutableStateFlow(null)

	init {
		viewModelScope.launch {
			player.steadyState
				.map { Triple(it.savedQueueId, it.savedQueueKind, it.savedQueueName) }
				.distinctUntilChanged()
				.collect { (id, kind, bornName) ->
					val record = id?.let { runCatching { savedQueueRepository.get(it) }.getOrNull() }
					playingFrom.value = playingFromText(
						kind = record?.sourceKind?.takeIf { it != SavedQueueSource.MANUAL } ?: kind,
						name = record?.name,
						sourceName = record?.sourceName ?: bornName
					)
				}
		}
		viewModelScope.launch {
			// Distinct on the song, NOT the whole state: `uiState` re-emits every 200 ms with a
			// new playhead, and this used to fire two Room queries on each of those — twice a
			// second's worth of database work forever, to answer a question that can only change
			// when the track does.
			player.uiState
				.map { it.currentSong }
				.distinctUntilChangedBy { it?.id }
				.collect { song ->
					song?.let {
						songIsStarred.value = songRepository.isSongStarred(it)
						songRating.value = songRepository.getSongRating(it)
					}
				}
		}
	}

	fun starSong(starred: Boolean) {
		viewModelScope.launch {
			runCatching {
				player.uiState.value.currentSong?.let { song ->
					songIsStarred.value = starred
					if (starred) {
						songRepository.starSong(song)
					} else {
						songRepository.unstarSong(song)
					}
				}
			}
		}
	}

	fun rateSong(rating: Int) {
		viewModelScope.launch {
			runCatching {
				player.uiState.value.currentSong?.let { song ->
					songRating.value = rating
					songRepository.rateSong(song, rating)
				}
			}
		}
	}
}
