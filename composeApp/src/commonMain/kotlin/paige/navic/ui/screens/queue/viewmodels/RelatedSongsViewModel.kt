package paige.navic.ui.screens.queue.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import paige.navic.domain.manager.RadioManager
import paige.navic.domain.models.DomainSong
import paige.navic.shared.MediaPlayerViewModel
import paige.navic.ui.core.UiState

/** How many suggestions the Related tab shows at once. */
private const val RELATED_MAX = 20

/**
 * Backs the queue sheet's "Related" tab. The song source is [RadioManager.fetchQueueRelatedSongs]
 * — recommendations derived from the WHOLE queue that aren't already queued. Row selection, star,
 * rating and downloads go through [QueueViewModel], so a related row's sheet is the queue's own.
 *
 * The fetch returns more candidates than are shown, and what is shown is those candidates minus
 * whatever is queued NOW. So adding a suggestion to the queue just drops that row and lets the next
 * candidate in — it used to refetch, which blanked the list behind a spinner and came back reseeded
 * in a different order. A refetch happens only when the queue gains a song the candidates don't
 * know about (autoplay top-up, a new queue), and the current list stays up until it lands.
 */
@OptIn(FlowPreview::class)
class RelatedSongsViewModel(
	private val radioManager: RadioManager,
	private val mediaPlayer: MediaPlayerViewModel
) : ViewModel() {
	/** Null until the first fetch lands. */
	private val candidates = MutableStateFlow<List<DomainSong>?>(null)

	/** The queue ids the current [candidates] were fetched for. */
	private var fetchedFor: Set<String> = emptySet()

	// steadyState, not uiState: the queue does not change with the playhead.
	private val queueIds = mediaPlayer.steadyState
		.map { state -> state.queue.map { it.id } }
		.distinctUntilChanged()

	val songsState: StateFlow<UiState<ImmutableList<DomainSong>>> =
		combine(candidates, queueIds) { songs, ids ->
			if (songs == null) {
				UiState.Loading<ImmutableList<DomainSong>>()
			} else {
				val queued = ids.toHashSet()
				UiState.Success(songs.filterNot { it.id in queued }.take(RELATED_MAX).toImmutableList())
			}
		}.stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading())

	init {
		viewModelScope.launch {
			queueIds
				// Coalesce autoplay bursts (several appends in quick succession) into one fetch.
				.debounce(400)
				// collectLatest cancels an in-flight fetch when the queue changes again.
				.collectLatest { ids ->
					val known = candidates.value?.mapTo(HashSet(fetchedFor)) { it.id }
					if (known != null && ids.all { it in known }) return@collectLatest
					val queue = mediaPlayer.steadyState.value.queue
					val related = radioManager.fetchQueueRelatedSongs(queue)
					fetchedFor = ids.toSet()
					candidates.value = related
				}
		}
	}
}
