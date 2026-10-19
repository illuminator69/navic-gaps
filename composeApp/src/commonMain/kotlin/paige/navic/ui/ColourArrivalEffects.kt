package paige.navic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.models.DomainSong
import paige.navic.shared.MediaPlayerViewModel
import paige.navic.ui.util.CoverSeedPrefetcher
import paige.navic.ui.util.rememberNowPlayingCoverArtId
import paige.navic.ui.util.rememberPlayerRestored
import paige.navic.util.CoverPlaceholder

/**
 * What keeps cover colours ready before a page needs them (I-043), composed once at the root of
 * `App.kt` — a fork line a merge can drop without an error: the cold start then goes back to a
 * neutral page that flips to the wash.
 *
 * Two jobs. It remembers the playing cover's id, so the next cold start wears it
 * (`washCoverArtId`) until the player has restored, and forgets it once the restored player has
 * nothing playing (a cleared queue): the next cold start would otherwise wear the old cover for a
 * moment and then go neutral. Before the restore, null means "not known yet", and the id is kept.
 * And it asks [CoverSeedPrefetcher] for the next songs' colours.
 */
@Composable
fun ColourArrivalEffects() {
	val preferences = koinInject<PreferenceManager>()
	val playing = rememberNowPlayingCoverArtId()
	val restored = rememberPlayerRestored()
	LaunchedEffect(playing, restored) {
		val last = when {
			playing.isNullOrBlank() -> if (restored) "" else return@LaunchedEffect
			CoverPlaceholder.isPlaceholder(playing) -> return@LaunchedEffect
			else -> playing
		}
		if (preferences.lastNowPlayingCoverArtId != last) preferences.lastNowPlayingCoverArtId = last
	}
	// The next songs' colours, worked out while this one plays, so a song change finds them stored.
	// `steadyState`, never `uiState`: the latter re-emits on every playhead tick (CLAUDE.md §7).
	val player = koinInject<MediaPlayerViewModel>()
	val prefetcher = koinInject<CoverSeedPrefetcher>()
	LaunchedEffect(player, prefetcher) {
		player.steadyState
			.map { upcomingCoverArtIds(it.queue, it.currentIndex, UPCOMING_PREFETCH) }
			.distinctUntilChanged()
			.collect { ids -> ids.forEach(prefetcher::request) }
	}
}

/** How many of the next songs' covers are worked out ahead. */
private const val UPCOMING_PREFETCH = 3

/**
 * The cover ids of the songs after [currentIndex], in order: none blank, none Navidrome's generic
 * avatar, each once, at most [count]. Nothing for an index of -1 or past the end.
 */
internal fun upcomingCoverArtIds(queue: List<DomainSong>, currentIndex: Int, count: Int): List<String> {
	if (currentIndex < 0 || currentIndex >= queue.size) return emptyList()
	return queue.asSequence()
		.drop(currentIndex + 1)
		.mapNotNull { it.coverArtId }
		.filter { it.isNotBlank() && !CoverPlaceholder.isPlaceholder(it) }
		.distinct()
		.take(count)
		.toList()
}
