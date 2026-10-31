package paige.navic.ui.screens.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.home_index_title
import org.jetbrains.compose.resources.stringResource
import paige.navic.icons.Icons
import paige.navic.icons.outlined.LibraryMusic
import paige.navic.ui.components.common.resolve
import paige.navic.ui.util.rememberFoldMotion

/**
 * Worbus P1 (N5): lb-bot's first index build on Home, under Continue listening. The capsule is the
 * Continue listening pill's (`ContinueListeningBox`): the same `Surface`, shape and colour, with the
 * fork's `LinearProgressIndicator` (as the fill rows draw it) filling as artists are scanned. It is
 * folded in and out by its `Fold` on Home, and its bar eases between pushes on the fold's motion, so both
 * follow the animation style and the system's animation scale (reduced motion is that scale).
 */
@Composable
fun IndexProgressCard(bar: IndexBar, modifier: Modifier = Modifier) {
	val motion = rememberFoldMotion()
	val fraction by animateFloatAsState(bar.fraction, motion.spatial(), label = "indexBar")
	val title = stringResource(Res.string.home_index_title)
	val line = indexBarLine(bar).resolve()
	Surface(
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 6.dp)
			.semantics(mergeDescendants = true) { contentDescription = "$title. $line" },
		shape = RoundedCornerShape(21.dp),
		color = MaterialTheme.colorScheme.surfaceContainerHigh
	) {
		Column(
			modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp)
		) {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
				Icon(Icons.Outlined.LibraryMusic, contentDescription = null, modifier = Modifier.size(20.dp))
				Column(Modifier.weight(1f)) {
					Text(
						text = title,
						style = MaterialTheme.typography.titleSmall,
						fontWeight = FontWeight.Bold
					)
					Text(
						text = line,
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis
					)
				}
			}
			LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
		}
	}
}
