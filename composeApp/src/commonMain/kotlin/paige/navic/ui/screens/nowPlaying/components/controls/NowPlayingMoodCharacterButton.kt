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
import org.koin.compose.koinInject
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.RadioManager
import paige.navic.domain.models.settings.AutoplayMode
import paige.navic.domain.models.settings.MoodCharacter
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Speed

/**
 * Small circular frosted button that sits next to [NowPlayingAutoplaySelector]
 * ONLY while Mood Flow (Adaptive) is the active autoplay mode. Tapping it springs
 * open a popup (same frosted style as the autoplay selector) to pick the Mood
 * Flow tuning preset ([MoodCharacter]) without going to Settings. Writes
 * `preferenceManager.moodCharacter`, which RadioManager reads fresh on each
 * Adaptive top-up — no extra wiring.
 */
@Composable
fun NowPlayingMoodCharacterButton(modifier: Modifier = Modifier, hazeState: HazeState? = null) {
	val radioManager = koinInject<RadioManager>()
	val preferenceManager = koinInject<PreferenceManager>()

	val mode by radioManager.autoplayMode.collectAsState()

	val character = preferenceManager.moodCharacter

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
		visible = mode == AutoplayMode.Adaptive,
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
						contentDescription = "Mood Flow character",
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
				MoodCharacter.entries.forEach { option ->
					DropdownMenuItem(
						text = { Text(option.label) },
						onClick = {
							preferenceManager.moodCharacter = option
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
