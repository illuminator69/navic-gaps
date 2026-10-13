package paige.navic.ui.screens.nowPlaying.components.controls

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import ir.mahozad.multiplatform.wavyslider.WaveDirection
import ir.mahozad.multiplatform.wavyslider.WaveVelocity
import ir.mahozad.multiplatform.wavyslider.material3.Track
import ir.mahozad.multiplatform.wavyslider.material3.WaveAnimationSpecs
import ir.mahozad.multiplatform.wavyslider.material3.WaveVelocity
import ir.mahozad.multiplatform.wavyslider.material3.WavySlider
import org.koin.compose.koinInject
import paige.navic.domain.manager.HubManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.models.settings.NowPlayingSliderStyle
import paige.navic.shared.MediaPlayerViewModel
import paige.navic.ui.components.common.SlimSlider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingProgressBar() {
	val preferenceManager = koinInject<PreferenceManager>()
	val player = koinInject<MediaPlayerViewModel>()
	val hubManager = koinInject<HubManager>()
	val isRemoteActive by hubManager.isRemoteActive.collectAsState()
	// This one genuinely draws the playhead, so it takes the narrow [progress] flow and leaves the
	// rest on steadyState — it still ticks ~5x a second, but alone, instead of dragging every
	// sibling in the now-playing sheet along with it.
	val playerState by player.steadyState.collectAsState()
	val progress by player.progress.collectAsState()
	val enabled = playerState.currentSong != null

	// Where the finger is while a REMOTE drag is in progress, null otherwise. One for all the
	// slider styles below, since only one of them is composed.
	var dragValue by remember { mutableStateOf<Float?>(null) }
	val shown = dragValue ?: progress
	// A slider disabled under the finger may never report the end of that drag.
	LaunchedEffect(enabled) {
		if (!enabled) dragValue = null
	}

	// While remote, a drag is only previewed here and sent to the hub ONCE, on release: every
	// sample used to be an `act:seek`, i.e. a seek on the playing device and a session broadcast
	// from the hub to every client. HubManager.actSeek then holds the target in the mirror, so
	// the thumb does not go back to the old position while the hub's answer is on its way.
	// The local player still follows the finger, as upstream has it.
	val onSeek: (Float) -> Unit = { fraction ->
		if (isRemoteActive) {
			dragValue = fraction
		} else {
			dragValue = null
			player.seek(fraction)
		}
	}
	val onSeekFinished: () -> Unit = {
		val fraction = dragValue
		dragValue = null
		if (fraction != null && isRemoteActive) {
			val durationMs = playerState.currentSong?.duration?.inWholeMilliseconds ?: 0L
			hubManager.actSeek((fraction * durationMs).toLong())
		}
	}
	val waveHeight by animateDpAsState(
		if (!playerState.isPaused)
			6.dp
		else 0.dp
	)

	when (preferenceManager.nowPlayingSliderStyle) {
		NowPlayingSliderStyle.Flat -> {
			Slider(
				value = shown,
				onValueChange = onSeek,
				onValueChangeFinished = onSeekFinished,
				modifier = Modifier.padding(horizontal = 16.dp),
				enabled = enabled
			)
		}

		NowPlayingSliderStyle.Squiggly, NowPlayingSliderStyle.Yoyo -> {
			val isYoyo = preferenceManager.nowPlayingSliderStyle == NowPlayingSliderStyle.Yoyo
			WavySlider(
				value = shown,
				onValueChange = onSeek,
				onValueChangeFinished = onSeekFinished,
				modifier = Modifier.padding(
					horizontal = if (isYoyo) 7.dp else 14.dp
				),
				waveHeight = waveHeight,
				thumb = {
					SliderDefaults.Thumb(
						enabled = playerState.currentSong != null,
						thumbSize = if (isYoyo)
							DpSize(20.dp, 20.dp)
						else DpSize(4.dp, 32.dp),
						interactionSource = remember { MutableInteractionSource() }
					)
				},
				track = { sliderState ->
					SliderDefaults.Track(
						sliderState = sliderState,
						thumbTrackGapSize = if (isYoyo) 0.dp else 6.dp,
						waveLength = if (isYoyo) 32.dp else 26.dp,
						waveHeight = waveHeight,
						animationSpecs = SliderDefaults.WaveAnimationSpecs.copy(
							waveAppearanceAnimationSpec = snap()
						),
						waveVelocity = if (isYoyo)
							WaveVelocity(14.dp, WaveDirection.TAIL)
						else SliderDefaults.WaveVelocity
					)
				},
				enabled = enabled
			)
		}

		NowPlayingSliderStyle.Slim -> {
			SlimSlider(
				value = shown,
				onValueChange = onSeek,
				onValueChangeFinished = onSeekFinished,
				modifier = Modifier.padding(horizontal = 16.dp),
				enabled = enabled
			)
		}
	}
}
