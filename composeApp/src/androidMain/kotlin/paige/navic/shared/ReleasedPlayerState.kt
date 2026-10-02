package paige.navic.shared

import paige.navic.domain.models.DomainSong
import paige.navic.ui.core.PlayerUiState

/*
 * Q-040: the local player state while the media controller is released (after a swipe), and what a
 * reconnect puts back into the new service's empty player.
 *
 * While released, the UI state is the only copy of the local queue. The hub keeps this phone as
 * its paused receiver and goes on sending queue edits, clears, repeat and shuffle; each must land
 * in that state, or the reconnect restores a queue the session has moved on from and the next
 * publish sends it back over the session.
 */

/**
 * What a (re)connect restores into an empty player: the UI state as it is NOW, paused, the same
 * shape a launch restores. Null when there is no queue to restore.
 */
internal fun restoreOnConnect(state: PlayerUiState): PlayerUiState? =
	state.takeIf { it.queue.isNotEmpty() }?.copy(isPaused = true, isLoading = false)

/**
 * A hub queue edit (`queueChanged`) arriving with no controller: the new queue and cursor, paused.
 * The position survives only when the current track did; a different one starts from 0, as the
 * connected path's reload does.
 */
internal fun PlayerUiState.withQueueWhileReleased(songs: List<DomainSong>, index: Int): PlayerUiState {
	val newCurrent = songs.getOrNull(index)
	val sameTrack = currentSong != null && newCurrent?.id == currentSong.id
	return copy(
		queue = songs,
		currentIndex = index,
		currentSong = newCurrent,
		isPaused = true,
		isLoading = false,
		progress = if (sameTrack) progress else 0f
	)
}
