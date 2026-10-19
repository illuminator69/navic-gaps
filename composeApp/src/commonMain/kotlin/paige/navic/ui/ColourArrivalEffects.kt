package paige.navic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import org.koin.compose.koinInject
import paige.navic.domain.manager.PreferenceManager
import paige.navic.ui.util.rememberNowPlayingCoverArtId
import paige.navic.ui.util.rememberPlayerRestored
import paige.navic.util.CoverPlaceholder

/**
 * What keeps cover colours ready before a page needs them (I-043), composed once at the root of
 * `App.kt` — a fork line a merge can drop without an error: the cold start then goes back to a
 * neutral page that flips to the wash.
 *
 * Remembers the playing cover's id, so the next cold start wears it (`washCoverArtId`) until the
 * player has restored, and forgets it once the restored player has nothing playing (a cleared
 * queue): the next cold start would otherwise wear the old cover for a moment and then go neutral.
 * Before the restore, null means "not known yet", and the id is kept.
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
}
