package paige.navic.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.home_mood_from_library
import navic.composeapp.generated.resources.home_mood_opening
import navic.composeapp.generated.resources.home_mood_play
import navic.composeapp.generated.resources.home_mood_saved_queues
import navic.composeapp.generated.resources.home_mood_songs_approx
import org.jetbrains.compose.resources.stringResource
import paige.navic.data.database.entities.SavedQueueEntity
import paige.navic.domain.manager.MOOD_MIX_SIZE
import paige.navic.domain.manager.MoodEntry
import paige.navic.domain.manager.MoodVariant
import paige.navic.domain.manager.tileColors
import paige.navic.icons.Icons
import paige.navic.icons.filled.Play
import paige.navic.ui.screens.library.components.ContinueListeningCard
import paige.navic.ui.theme.defaultFont
import paige.navic.ui.util.ArrivalGate
import paige.navic.ui.util.arrive

/**
 * A mood's body on Home (P7, board HomeMood): its name and "From your library", its four tiles in a
 * 2x2 grid, then the saved queues its tiles played (`queuesFromMood`), when there are any. [busy] is
 * the tile whose play button is drawing its mix right now.
 */
@Composable
fun MoodBody(
	mood: MoodEntry,
	queues: List<SavedQueueEntity>,
	playingQueueId: String?,
	busy: String?,
	gate: ArrivalGate,
	onOpen: (MoodVariant) -> Unit,
	onPlay: (MoodVariant) -> Unit,
	onResume: (SavedQueueEntity) -> Unit,
	onMenu: (SavedQueueEntity) -> Unit,
	modifier: Modifier = Modifier
) {
	Column(modifier.fillMaxWidth()) {
		Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 4.dp)) {
			Text(
				mood.label,
				style = MaterialTheme.typography.headlineSmall,
				fontWeight = FontWeight(600),
				modifier = Modifier.semantics { heading() }
			)
			Text(
				stringResource(Res.string.home_mood_from_library),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				fontFamily = defaultFont()
			)
		}
		mood.variants.chunked(2).forEachIndexed { row, pair ->
			Row(
				modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
				horizontalArrangement = Arrangement.spacedBy(12.dp)
			) {
				pair.forEachIndexed { col, variant ->
					MoodTile(
						variant = variant,
						busy = busy == variant.id,
						onOpen = { onOpen(variant) },
						onPlay = { onPlay(variant) },
						modifier = Modifier
							.weight(1f)
							.arrive(gate, "mood/${mood.id}/${variant.id}", row * 2 + col)
					)
				}
				// An odd last tile keeps its column's width.
				if (pair.size == 1) Box(Modifier.weight(1f))
			}
		}
		if (queues.isNotEmpty()) {
			HomeShelfHeader(stringResource(Res.string.home_mood_saved_queues), null, null)
			LazyRow(
				horizontalArrangement = Arrangement.spacedBy(10.dp),
				contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
			) {
				items(queues, key = { it.id }) { queue ->
					ContinueListeningCard(
						modifier = Modifier.width(MOOD_QUEUE_CARD),
						queue = queue,
						isActive = queue.id == playingQueueId,
						onClick = { onResume(queue) },
						onLongClick = { onMenu(queue) }
					)
				}
			}
		}
	}
}

private val MOOD_QUEUE_CARD = 120.dp

/**
 * One tile: its generated art with the title over it, the title and "~50 songs" under it, and a play
 * button that plays a draw without opening the page ("Opening…" while it draws).
 */
@Composable
private fun MoodTile(
	variant: MoodVariant,
	busy: Boolean,
	onOpen: () -> Unit,
	onPlay: () -> Unit,
	modifier: Modifier = Modifier
) {
	val playLabel = stringResource(Res.string.home_mood_play, variant.title)
	Column(modifier.clickable(role = Role.Button, onClick = onOpen)) {
		Box {
			MoodArt(variant, Modifier.fillMaxWidth().aspectRatio(1f))
			FilledIconButton(
				onClick = onPlay,
				enabled = !busy,
				modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(40.dp),
				colors = IconButtonDefaults.filledIconButtonColors(
					containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
					contentColor = MaterialTheme.colorScheme.onSurface,
					disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
					disabledContentColor = MaterialTheme.colorScheme.onSurface
				),
				shape = CircleShape
			) {
				if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
				else Icon(Icons.Filled.Play, contentDescription = playLabel, modifier = Modifier.size(20.dp))
			}
		}
		Text(
			variant.title,
			style = MaterialTheme.typography.titleSmallEmphasized,
			modifier = Modifier.padding(top = 6.dp),
			maxLines = 1,
			overflow = TextOverflow.Ellipsis
		)
		Text(
			if (busy) stringResource(Res.string.home_mood_opening)
			else stringResource(Res.string.home_mood_songs_approx, MOOD_MIX_SIZE),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			fontFamily = defaultFont(),
			maxLines = 1
		)
	}
}

/**
 * A tile's art: a diagonal gradient of its two colours (`tileColors`, white-readable at 4.5:1 by
 * test) with the title in white. Artwork, so the same on every theme; [corner] follows the size.
 */
@Composable
fun MoodArt(variant: MoodVariant, modifier: Modifier = Modifier, corner: Dp = 16.dp, large: Boolean = false) {
	val colors = remember(variant) { tileColors(variant) }
	Box(
		modifier = modifier
			.clip(RoundedCornerShape(corner))
			.background(Brush.linearGradient(listOf(colors.top, colors.bottom))),
		contentAlignment = Alignment.BottomStart
	) {
		Text(
			variant.title,
			color = Color.White,
			style = if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
			fontWeight = FontWeight.Bold,
			maxLines = 3,
			overflow = TextOverflow.Ellipsis,
			// Clear of the play button's corner on a Home tile.
			modifier = Modifier.padding(start = 12.dp, end = if (large) 12.dp else 52.dp, bottom = 12.dp, top = 12.dp)
		)
	}
}
