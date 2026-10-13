package paige.navic.ui.components.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.koinInject
import paige.navic.domain.manager.HubManager

/** How long the bar stays after the last press. */
private const val HUD_LINGER_MS = 1_800L

/**
 * The app's own volume bar for the device that is playing, shown when the phone's volume
 * buttons move that device's level while Navic is on screen (`VolumeKeys.kt`). Not every
 * Android build draws a slider for a remote session, and the app's own window takes the press
 * before the system sees it, so this is the one place the level is shown.
 *
 * Composed once, above the pages (`App.kt`), and drawn in a window of its own so that it is
 * also above an open sheet. It only shows the level: the slider to drag is the device picker's.
 */
@Composable
fun RemoteVolumeHud() {
	val hubManager = koinInject<HubManager>()
	val devices by hubManager.devices.collectAsState()
	val activeId by hubManager.activeDeviceId.collectAsState()
	val pin by hubManager.volumePin.collectAsState()
	val active = devices.firstOrNull { it.id == activeId }

	var commanded by remember { mutableIntStateOf(0) }
	val shown = remember { MutableTransitionState(false) }
	LaunchedEffect(hubManager) {
		hubManager.hardwareVolume.collectLatest { level ->
			commanded = level
			shown.targetState = true
			delay(HUD_LINGER_MS)
			shown.targetState = false
		}
	}
	// The hub's answer, once no commanded level stands in front of it: the device may have
	// rounded the level, or another controller may have set one since.
	val reported = active?.volume
	LaunchedEffect(reported, pin) {
		if (pin == null && reported != null && shown.targetState) commanded = reported
	}

	if (!shown.currentState && !shown.targetState) return
	val level = pin?.level ?: commanded
	val fraction by animateFloatAsState(level / 100f)
	Popup(alignment = Alignment.TopCenter, properties = PopupProperties(focusable = false)) {
		AnimatedVisibility(
			visibleState = shown,
			enter = fadeIn() + slideInVertically { -it },
			exit = fadeOut() + slideOutVertically { -it }
		) {
			Surface(
				modifier = Modifier
					// The popup's window already starts below the status bar.
					.padding(top = 8.dp, start = 24.dp, end = 24.dp, bottom = 12.dp)
					.widthIn(max = 420.dp)
					.fillMaxWidth(),
				shape = MaterialTheme.shapes.extraLarge,
				color = MaterialTheme.colorScheme.surfaceContainerHigh,
				shadowElevation = 6.dp
			) {
				Column(
					modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
					verticalArrangement = Arrangement.spacedBy(10.dp)
				) {
					Row(verticalAlignment = Alignment.CenterVertically) {
						Text(
							if (active != null) "Volume · ${active.name}" else "Volume",
							style = MaterialTheme.typography.labelLarge,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
							modifier = Modifier.weight(1f)
						)
						Text("$level", style = MaterialTheme.typography.labelLarge)
					}
					LinearProgressIndicator(
						progress = { fraction },
						modifier = Modifier.fillMaxWidth()
					)
				}
			}
		}
	}
}
