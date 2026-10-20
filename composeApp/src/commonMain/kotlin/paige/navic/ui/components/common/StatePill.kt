package paige.navic.ui.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * One state as a small rounded label (P4). Colours are the scheme's container / on-container pairs,
 * which Material builds for 4.5:1 and which follow the page's cover scheme like everything else; a
 * RUN pill carries a small spinner, and every pill carries its words.
 */
@Composable
fun StatePill(pill: Pill, modifier: Modifier = Modifier) {
	val (container, content) = pillColors(pill.tone)
	Row(
		modifier = modifier
			.clip(CircleShape)
			.background(container)
			.padding(horizontal = 10.dp, vertical = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(6.dp)
	) {
		if (pill.tone == PillTone.RUN) {
			CircularProgressIndicator(modifier = Modifier.size(11.dp), strokeWidth = 1.5.dp, color = content)
		}
		// The mockups' Owned: a filled accent pill with a check, the one a column of them reads by.
		if (pill.tone == PillTone.OK) {
			Icon(Icons.Outlined.Check, contentDescription = null, tint = content, modifier = Modifier.size(13.dp))
		}
		Text(
			pill.label.resolve(),
			style = MaterialTheme.typography.labelMedium,
			color = content,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis
		)
	}
}

@Composable
private fun pillColors(tone: PillTone): Pair<Color, Color> {
	val scheme = MaterialTheme.colorScheme
	return when (tone) {
		PillTone.RUN -> scheme.secondaryContainer to scheme.onSecondaryContainer
		// Round 2, from the AVD: on a dark washed page the container roles sit a step off the row's
		// own surface and the pills read faint. The mockups fill Owned with the accent and draw a
		// warning as its own colour on a tint of it, which holds on light and dark schemes alike.
		PillTone.OK -> scheme.primary to scheme.onPrimary
		PillTone.GOOD -> scheme.primary to scheme.onPrimary
		PillTone.WARN -> scheme.tertiary.copy(alpha = 0.18f) to scheme.tertiary
		PillTone.BAD -> scheme.errorContainer to scheme.onErrorContainer
		PillTone.MUTE -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
		// A tint of the text, not a surface role: a format pill sits on a source card, which is itself
		// surfaceContainerHigh, and a pill of the same role vanished into it (seen on the AVD).
		PillTone.NEUTRAL -> scheme.onSurface.copy(alpha = 0.10f) to scheme.onSurface
	}
}
