package paige.navic.ui.screens.nowPlaying.components.controls

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import paige.navic.icons.outlined.Check
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.blurEffect
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.delay
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.option_station_character
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.domain.manager.RadioManager
import paige.navic.domain.models.settings.StationCharacter
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Speed

/**
 * Small circular frosted button beside [NowPlayingAutoplaySelector], shown while the hub's
 * station engine is there and there is something for it to steer: autoplay on, or a radio
 * playing, which keeps going with autoplay off (I-040). Its menu picks the character
 * ([StationCharacter]) in the hub catalogue's words: the one new stations start with, and the
 * playing station's from its next batch (`RadioManager.setCharacter`), so the queue on screen
 * stays as it is. The check mark is the PLAYING station's character when one is bound, read
 * through a flow; it used to read the preference directly and kept showing the old one.
 */
@Composable
fun NowPlayingCharacterButton(modifier: Modifier = Modifier, hazeState: HazeState? = null) {
	val radioManager = koinInject<RadioManager>()

	val autoplayOn by radioManager.autoplayOn.collectAsState()
	val radioKeepsGoing by radioManager.radioKeepsGoing.collectAsState()
	val stationsAvailable by radioManager.stationsAvailable.collectAsState()
	val catalogue by radioManager.catalogue.collectAsState()
	val character by radioManager.shownCharacter.collectAsState()

	var expanded by remember { mutableStateOf(false) }
	// Same reopen-race guard as the autoplay selector.
	var justDismissed by remember { mutableStateOf(false) }

	LaunchedEffect(justDismissed) {
		if (justDismissed) {
			delay(250)
			justDismissed = false
		}
	}

	val frost = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f)
	val frostBorder = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)

	// Eased in and out beside the autoplay selector rather than appearing in one frame: the row
	// is centred, so the selector slides aside instead of jumping (I-039).
	AnimatedVisibility(
		// A radio keeps going with autoplay off, and its character still steers it.
		visible = stationsAvailable && (autoplayOn || radioKeepsGoing),
		modifier = modifier,
		enter = fadeIn() + expandHorizontally(),
		exit = fadeOut() + shrinkHorizontally()
	) {
		Box {
			// A 48dp target around the 38dp frosted circle, as an IconButton holds its icon:
			// the circle alone was a 38dp target, under the minimum.
			Box(
				modifier = Modifier
					.size(48.dp)
					.clip(CircleShape)
					.clickable {
						if (!justDismissed) expanded = !expanded
					},
				contentAlignment = Alignment.Center
			) {
				Box(
					modifier = Modifier
						.size(38.dp)
						.clip(CircleShape)
						.then(
							if (hazeState != null) {
								Modifier.hazeEffect(state = hazeState) { blurEffect { blurRadius = 20.dp } }
							} else {
								Modifier
							}
						)
						.background(if (hazeState != null) frost.copy(alpha = 0.5f) else frost)
						.border(1.dp, frostBorder, CircleShape),
					contentAlignment = Alignment.Center
				) {
					Icon(
						imageVector = Icons.Outlined.Speed,
						contentDescription = stringResource(Res.string.option_station_character),
						modifier = Modifier.size(18.dp)
					)
				}
			}
			// M3's menu, as upstream's are; it was a hand-made popup placed by a pixel offset.
			DropdownMenu(
				expanded = expanded,
				onDismissRequest = {
					expanded = false
					justDismissed = true
				}
			) {
				StationCharacter.entries.forEach { option ->
					val info = catalogue.infoFor(option)
					DropdownMenuItem(
						text = { Text(info.label.ifBlank { option.label }) },
						onClick = {
							radioManager.setCharacter(option)
							expanded = false
						},
						leadingIcon = {
							if (option == character) Icon(Icons.Outlined.Check, contentDescription = null)
							else Spacer(Modifier.size(24.dp))
						}
					)
				}
			}
		}
	}
}
