package paige.navic.ui.screens.nowPlaying.components.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.blurEffect
import dev.chrisbanes.haze.hazeEffect
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.autoplay_off
import navic.composeapp.generated.resources.autoplay_on
import navic.composeapp.generated.resources.now_playing_autoplay
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.domain.manager.RadioManager
import paige.navic.icons.Icons
import paige.navic.icons.outlined.PlaylistPlay

/**
 * Autoplay On / Off for the extended player, between the artwork and the progress bar: a
 * rounded, frosted switch. On, the queue continues when it runs out, as a radio of what is
 * playing when the hub has the station engine and as Auto DJ when it has not (I-040); which of
 * the two is the queue sheet's to say, not this control's. It used to open a menu of four modes.
 *
 * Meaningful in every configuration (Auto DJ needs nothing but Navidrome), so its place in the
 * row is never an empty AudioMuse slot (Q-100).
 */
@Composable
fun NowPlayingAutoplaySelector(modifier: Modifier = Modifier, hazeState: HazeState? = null) {
	val radioManager = koinInject<RadioManager>()
	val on by radioManager.autoplayOn.collectAsState()

	val shape = RoundedCornerShape(20.dp)
	// Heavy frost: a milky, mostly-opaque panel over the blurred background.
	val frost = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f)
	val frostBorder = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
	val state = stringResource(if (on) Res.string.autoplay_on else Res.string.autoplay_off)

	Box(modifier) {
		Row(
			modifier = Modifier
				.clip(shape)
				.then(
					if (hazeState != null) {
						Modifier.hazeEffect(state = hazeState) { blurEffect { blurRadius = 20.dp } }
					} else {
						Modifier
					}
				)
				.background(if (hazeState != null) frost.copy(alpha = 0.5f) else frost)
				.border(1.dp, frostBorder, shape)
				.toggleable(value = on, role = Role.Switch, onValueChange = { radioManager.setAutoplayOn(it) })
				.padding(horizontal = 16.dp, vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp)
		) {
			Icon(
				imageVector = Icons.Outlined.PlaylistPlay,
				contentDescription = null,
				modifier = Modifier.size(18.dp)
			)
			Text(
				text = stringResource(Res.string.now_playing_autoplay, state),
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.onSurface
			)
		}
	}
}
