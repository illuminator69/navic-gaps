package paige.navic.ui.screens.queue.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_station_explore
import navic.composeapp.generated.resources.queue_auto_dj
import navic.composeapp.generated.resources.queue_generator_audiomuse
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
 * left that fits" with a one-tap Explore when the station is exhausted. Every word comes from the
 * station's answer or what topped the queue up ([QueueGenerator]), never from the autoplay
 * setting, which said "Mood Flow" over an album.
 *
 * The dot is the AudioMuse colour; while a request is out it is a small spinner instead, so a
 * queue on its last song shows that more is coming. A change of line crossfades in place.
 */
@Composable
fun QueueGeneratorLine(
	generator: QueueGenerator?,
	busy: Boolean,
	catalogue: StationCatalogue,
	onExplore: () -> Unit
) {
	AnimatedVisibility(visible = generator != null || busy, enter = fadeIn(), exit = fadeOut()) {
		Row(
			modifier = Modifier.heightIn(min = 24.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp)
		) {
			val busyText = stringResource(Res.string.queue_station_busy)
			Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
				if (busy) {
					CircularProgressIndicator(
						modifier = Modifier.size(12.dp).semantics { contentDescription = busyText },
						strokeWidth = 1.5.dp
					)
				} else {
					Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape))
				}
			}
			AnimatedContent(
				targetState = generator,
				transitionSpec = { fadeIn() togetherWith fadeOut() },
				label = "queueGenerator"
			) { shown ->
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text(
						text = generatorText(shown, catalogue) ?: busyText,
						style = MaterialTheme.typography.labelLarge,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.weight(1f, fill = false)
					)
					if (shown is QueueGenerator.Exhausted && shown.offerExplore) {
						TextButton(
							onClick = onExplore,
							contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
						) {
							Text(stringResource(Res.string.action_station_explore), maxLines = 1)
						}
					}
				}
			}
		}
	}
}

@Composable
private fun generatorText(generator: QueueGenerator?, catalogue: StationCatalogue): String? = when (generator) {
	null -> null
	is QueueGenerator.Station -> {
		val name = when {
			generator.yourMix && generator.name.isBlank() -> stringResource(Res.string.queue_your_mix)
			generator.name.isBlank() -> stringResource(Res.string.queue_station_unnamed)
			else -> generator.name
		}
		val character = catalogue.infoFor(generator.character).label.ifBlank { generator.character.label }
		val line = stringResource(Res.string.queue_station_line, name, character)
		if (generator.viaNavidrome) stringResource(Res.string.queue_station_via_navidrome, line) else line
	}
	is QueueGenerator.Exhausted -> stringResource(Res.string.queue_station_exhausted)
	QueueGenerator.AutoDj -> stringResource(Res.string.queue_auto_dj)
	QueueGenerator.AudioMuseMix -> stringResource(Res.string.queue_generator_audiomuse)
}
