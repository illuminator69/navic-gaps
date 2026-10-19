package paige.navic.ui.screens.home

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_see_all
import navic.composeapp.generated.resources.home_continue_listening
import navic.composeapp.generated.resources.home_saved_queues_count
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import paige.navic.data.database.entities.SavedQueueEntity
import paige.navic.icons.Icons
import paige.navic.icons.filled.Play
import paige.navic.icons.outlined.KeyboardArrowDown
import paige.navic.ui.screens.library.components.ContinueListeningCard
import paige.navic.ui.util.Fold
import paige.navic.ui.util.rememberFoldMotion

/** A saved queue's card in the box: the design's, smaller than a shelf card. */
private val BoxCardWidth = 104.dp

/**
 * "Continue listening" (board 1, 1h): a capsule that says how many saved queues there are and, tapped,
 * rolls out into a box of them at the same width. The capsule's corners ease from the pill's 21 dp to
 * the box's 18 dp as it opens, and the page below follows the height (a [Fold]). All of it moves as the
 * animation style says (`FoldMotion`): the motion scheme's springs, or Quick's short times.
 *
 * [queues] are the ones to show (the playing one first, [continueListening]); [totalCount] is how many
 * saved queues there are, which the capsule says and a trailing "See all" backs up when more than the
 * box holds.
 */
@Composable
fun ContinueListeningPill(
	queues: List<SavedQueueEntity>,
	totalCount: Int,
	playingId: String?,
	expanded: Boolean,
	onToggle: () -> Unit,
	onResume: (SavedQueueEntity) -> Unit,
	onMenu: (SavedQueueEntity) -> Unit,
	onSeeAll: () -> Unit,
	modifier: Modifier = Modifier
) {
	// The capsule's own motion is the fold's, so it follows the animation style with the box it opens.
	val motion = rememberFoldMotion(tall = true)
	val corner by animateDpAsState(
		targetValue = if (expanded) 18.dp else 21.dp,
		animationSpec = motion.spatial()
	)
	val turn by animateFloatAsState(
		targetValue = if (expanded) 180f else 0f,
		animationSpec = motion.spatial()
	)
	val count = pluralStringResource(Res.plurals.home_saved_queues_count, totalCount, totalCount)

	Surface(
		modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
		shape = RoundedCornerShape(corner),
		color = MaterialTheme.colorScheme.surfaceContainerHigh
	) {
		Column {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.height(42.dp)
					.semantics { if (expanded) collapse { onToggle(); true } else expand { onToggle(); true } }
					.clickable(role = Role.Button, onClick = onToggle)
					.padding(horizontal = 14.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(10.dp)
			) {
				Icon(Icons.Filled.Play, contentDescription = null, modifier = Modifier.size(20.dp))
				Text(
					text = stringResource(Res.string.home_continue_listening),
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.Bold,
					modifier = Modifier.weight(1f)
				)
				Text(
					text = count,
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
				Icon(
					Icons.Outlined.KeyboardArrowDown,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.size(20.dp).rotate(turn)
				)
			}
			Fold(visible = expanded, tall = true) {
				LazyRow(
					horizontalArrangement = Arrangement.spacedBy(10.dp),
					contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 14.dp)
				) {
					items(queues, key = { it.id }) { queue ->
						ContinueListeningCard(
							modifier = Modifier.width(BoxCardWidth),
							queue = queue,
							isActive = queue.id == playingId,
							onClick = { onResume(queue) },
							onLongClick = { onMenu(queue) }
						)
					}
					if (totalCount > queues.size) {
						item(key = "see-all") {
							Box(
								modifier = Modifier
									.width(BoxCardWidth)
									.height(BoxCardWidth)
									.clickable(role = Role.Button, onClick = onSeeAll),
								contentAlignment = Alignment.Center
							) {
								Text(
									text = stringResource(Res.string.action_see_all),
									style = MaterialTheme.typography.labelLarge,
									color = MaterialTheme.colorScheme.primary
								)
							}
						}
					}
				}
			}
		}
	}
}
