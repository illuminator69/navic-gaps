package paige.navic.ui.screens.queue.components

import paige.navic.ui.util.rememberFoldMotion
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.autoplay_off
import navic.composeapp.generated.resources.queue_auto_dj
import navic.composeapp.generated.resources.queue_generated_via_audiomuse
import navic.composeapp.generated.resources.queue_station_busy
import navic.composeapp.generated.resources.queue_station_exhausted
import navic.composeapp.generated.resources.queue_station_line
import navic.composeapp.generated.resources.queue_station_unnamed
import navic.composeapp.generated.resources.queue_station_via_navidrome
import navic.composeapp.generated.resources.queue_your_mix
import org.jetbrains.compose.resources.stringResource
import paige.navic.domain.manager.QueueGenerator
import paige.navic.domain.manager.StationCatalogue

/**
 * What built the queue, on a line of its own under the queue's header (I-040): the station's
 * name and character ("Cinnamon Girl radio · Balanced", "Your mix · Explore"), "via Navidrome"
 * when only Navidrome answered the hub, "Auto DJ" when the fallback topped it up, and "Nothing
 * left that fits" with a one-tap Explore when the station is exhausted. A queue generated here
 * without a station is said by the name it was saved under: a genre mix "Rock", a radio without
 * the engine "Cinnamon Girl radio", and "· via AudioMuse" only where AudioMuse chose the songs
 * ("Search: rain · via AudioMuse"); it used to say "AudioMuse mix" over a genre's random draw.
 * Every word comes from the station's answer, what topped the queue up or what generated it
 * ([QueueGenerator]), never from the autoplay setting, which said "Mood Flow" over an album.
 * The chips under the header are the control (I-041); this line only says what built the
 * queue, a stopped station as "… · Off".
 *
 * The line starts with its words (B-152: the AudioMuse-coloured dot that led it is gone, in both
 * clients). While a request is out a small spinner eases in AFTER the words, so the words never
 * move, and a queue on its last song still shows that more is coming; with nothing to name yet
 * the words are "Finding more songs" themselves. A change of line crossfades in place.
 */
@Composable
fun QueueGeneratorLine(
	generator: QueueGenerator?,
	busy: Boolean,
	catalogue: StationCatalogue
) {
	AnimatedVisibility(visible = generator != null || busy, enter = fadeIn(), exit = fadeOut()) {
		Row(
			modifier = Modifier.heightIn(min = 24.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp)
		) {
			val busyText = stringResource(Res.string.queue_station_busy)
			// `fill = false`: a short line keeps its own width, so the spinner sits right after
			// its last word; a long one gives the spinner its 18dp at the end, eased.
			val fade = rememberFoldMotion()
			AnimatedContent(
				targetState = generator,
				transitionSpec = { fadeIn(fade.effects()) togetherWith fadeOut(fade.effects()) },
				label = "queueGenerator",
				modifier = Modifier.weight(1f, fill = false)
			) { shown ->
				Text(
					text = generatorText(shown, catalogue) ?: busyText,
					style = MaterialTheme.typography.labelLarge,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
			}
			AnimatedVisibility(visible = busy) {
				CircularProgressIndicator(
					// The words already say it when there is nothing else to name.
					modifier = Modifier.size(12.dp).then(
						if (generator != null) Modifier.semantics { contentDescription = busyText } else Modifier
					),
					strokeWidth = 1.5.dp
				)
			}
		}
	}
}

@Composable
internal fun generatorText(generator: QueueGenerator?, catalogue: StationCatalogue): String? = when (generator) {
	null -> null
	is QueueGenerator.Station -> {
		val name = stationName(generator.name, generator.yourMix)
		// A character this device has not heard yet is not said (B-139: never a guessed default).
		val line = generator.character?.let { character ->
			stringResource(Res.string.queue_station_line, name, catalogue.infoFor(character).label.ifBlank { character.label })
		} ?: name
		if (generator.viaNavidrome) stringResource(Res.string.queue_station_via_navidrome, line) else line
	}
	is QueueGenerator.Stopped ->
		stringResource(Res.string.queue_station_line, stationName(generator.name, generator.yourMix), stringResource(Res.string.autoplay_off))
	is QueueGenerator.Exhausted -> stringResource(Res.string.queue_station_exhausted)
	QueueGenerator.AutoDj -> stringResource(Res.string.queue_auto_dj)
	is QueueGenerator.Generated ->
		if (generator.audioMuse) stringResource(Res.string.queue_generated_via_audiomuse, generator.name)
		else generator.name
}

@Composable
private fun stationName(name: String, yourMix: Boolean): String = when {
	yourMix && name.isBlank() -> stringResource(Res.string.queue_your_mix)
	name.isBlank() -> stringResource(Res.string.queue_station_unnamed)
	else -> name
}
