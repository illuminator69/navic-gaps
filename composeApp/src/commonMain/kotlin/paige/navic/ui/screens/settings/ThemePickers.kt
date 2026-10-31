package paige.navic.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.info_theme_artwork_note
import org.jetbrains.compose.resources.stringResource

/** How the Theme mode and Theme pickers stand while "Colours from artwork" is [dynamicTheming]. */
data class ThemePickers(
	/** Whether they can be changed. */
	val enabled: Boolean,
	/** Whether the one-line note ("Pages with album art take their colours from it") is drawn. */
	val noteShown: Boolean
)

/**
 * Artwork colours decide pages that have album art; the stored mode and theme still decide every
 * page without (settings, dialogs, a browsing page with nothing playing). So they are never greyed:
 * they are enabled, and the note says where artwork wins.
 */
fun themePickers(dynamicTheming: Boolean): ThemePickers =
	ThemePickers(enabled = true, noteShown = dynamicTheming)

/** The one-line note under the Theme mode and Theme rows, drawn while [ThemePickers.noteShown]. */
@Composable
fun ThemeArtworkNote(pickers: ThemePickers) {
	AnimatedVisibility(visible = pickers.noteShown) {
		Text(
			stringResource(Res.string.info_theme_artwork_note),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.padding(start = 12.dp, top = 4.dp)
		)
	}
}
