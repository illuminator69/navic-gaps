package paige.navic.ui.screens.mixes

import androidx.compose.runtime.Composable
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.mix_preview_empty
import navic.composeapp.generated.resources.mix_preview_regenerates
import org.jetbrains.compose.resources.stringResource
import paige.navic.domain.models.DomainSong
import paige.navic.domain.models.Mix
import paige.navic.ui.components.sheets.GeneratedQueueSheet
import paige.navic.ui.util.rememberNowPlayingCoverAmbient

/**
 * What a mix would play, before it plays it.
 *
 * A mix has **no fixed contents** — that is the entire feature — so "what is in
 * this" has no answer until the engine has been run. Until now the only way to find
 * out was to tap it, which replaces whatever you were listening to. That is a
 * strange amount of commitment to ask for a thing whose contents you cannot see.
 *
 * Deliberately built on the same shape as `MoodSearchSheet`: that screen had this
 * exact problem first — a generated queue you would want to look at before
 * committing to — and solved it by showing the proposed tracks with Play and Add to
 * queue. Two generated-queue surfaces that behave differently would be the drift
 * this tree keeps paying for.
 *
 * The reason line matters. These tracks are a **sample**, not a tracklist: playing
 * runs the recipe again and gets a different answer, which is exactly what
 * distinguishes a mix from a saved queue. A preview that quietly implied otherwise
 * would teach the wrong model of the feature.
 */
@Composable
fun MixPreviewSheet(
	mix: Mix,
	songs: List<DomainSong>,
	loading: Boolean,
	onPlay: () -> Unit,
	onAddToQueue: () -> Unit,
	onDismissRequest: () -> Unit
) {
	GeneratedQueueSheet(
		title = mix.name,
		subtitle = mixKindLabel(mix),
		songs = songs,
		loading = loading,
		emptyText = stringResource(Res.string.mix_preview_empty),
		ambient = rememberNowPlayingCoverAmbient(),
		onPlay = onPlay,
		onAddToQueue = onAddToQueue,
		onDismissRequest = onDismissRequest,
		// A sample, not a tracklist: playing runs the recipe again.
		note = stringResource(Res.string.mix_preview_regenerates)
	)
}
