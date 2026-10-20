package paige.navic.ui.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
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
		PillTone.OK -> scheme.primaryContainer to scheme.onPrimaryContainer
		PillTone.GOOD -> scheme.primary to scheme.onPrimary
		PillTone.WARN -> scheme.tertiaryContainer to scheme.onTertiaryContainer
		PillTone.BAD -> scheme.errorContainer to scheme.onErrorContainer
		PillTone.MUTE -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
		PillTone.NEUTRAL -> scheme.surfaceContainerHigh to scheme.onSurface
	}
}
