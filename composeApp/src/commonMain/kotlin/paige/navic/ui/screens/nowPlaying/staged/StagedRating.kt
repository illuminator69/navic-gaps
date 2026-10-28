package paige.navic.ui.screens.nowPlaying.staged

import paige.navic.ui.a11y.MIN_TOUCH_DP
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousCapsule
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.staged_rate_song
import navic.composeapp.generated.resources.staged_rate_stars
import org.jetbrains.compose.resources.stringResource
import paige.navic.domain.manager.PreviewManager
import paige.navic.icons.Icons
import paige.navic.icons.filled.Star
import paige.navic.icons.outlined.Star
import paige.navic.ui.util.rememberFoldMotion

/*
 * navi-connect P2 notes (you, 2026-10-19): the player's star is the song's rating, not a favourite.
 * Closed it is one star; a tap opens it to the left into five stars, and a rated song shows them
 * open. Favourites moved to the Save pill's heart. The rules are pure (`StagedRatingTest`).
 */

/** The rating after a tap on [tapped]: that rating, or none when it is the one already set. */
fun ratingAfterTap(current: Int, tapped: Int): Int = if (tapped == current) 0 else tapped.coerceIn(1, 5)

/** The five stars show when the song has a rating, or when they have been opened to give it one. */
fun ratingShownOpen(rating: Int, opened: Boolean): Boolean = rating > 0 || opened

fun starFilled(star: Int, rating: Int): Boolean = star <= rating

/**
 * Whether the playing song can be saved (favourite, playlist) or rated: a Navidrome song, not
 * nothing and not an `ext:` preview, which Navidrome does not hold (fix round P6).
 */
fun canSaveOrRate(songId: String?): Boolean = songId != null && !PreviewManager.isPreviewId(songId)

/** How long opened stars wait for a tap before closing again on an unrated song. */
private val OPEN_IDLE = 5.seconds

/**
 * The rating control at the end of the title row. [songKey] resets "opened" when the song changes.
 * Grows to the left: stars 1 to 4 roll out before the star that was there, which becomes the fifth.
 */
@Composable
fun ExpandingRating(
	rating: Int,
	onRate: (Int) -> Unit,
	songKey: Any?,
	enabled: Boolean,
	modifier: Modifier = Modifier
) {
	var opened by remember(songKey) { mutableStateOf(false) }
	val open = ratingShownOpen(rating, opened)
	LaunchedEffect(opened, rating) {
		if (opened && rating == 0) {
			delay(OPEN_IDLE)
			opened = false
		}
	}
	val motion = rememberFoldMotion()
	val container = IconButtonDefaults.filledTonalIconButtonColors().containerColor
	Surface(
		shape = ContinuousCapsule,
		color = container,
		modifier = modifier.height(32.dp)
	) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			AnimatedVisibility(
				visible = open,
				enter = expandHorizontally(motion.spatial<IntSize>(), expandFrom = Alignment.End) + fadeIn(motion.effects()),
				exit = shrinkHorizontally(motion.spatial<IntSize>(), shrinkTowards = Alignment.End) + fadeOut(motion.effects())
			) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					for (star in 1..4) {
						RatingStar(star, rating, enabled) { tapped ->
							val next = ratingAfterTap(rating, tapped)
							if (next == 0) opened = false
							onRate(next)
						}
					}
				}
			}
			if (open) {
				RatingStar(5, rating, enabled) { tapped ->
					val next = ratingAfterTap(rating, tapped)
					if (next == 0) opened = false
					onRate(next)
				}
			} else {
				IconButton(
					onClick = { opened = true },
					enabled = enabled,
					modifier = Modifier.size(MIN_TOUCH_DP.dp)
				) {
					Icon(Icons.Outlined.Star, contentDescription = stringResource(Res.string.staged_rate_song))
				}
			}
		}
	}
}

@Composable
private fun RatingStar(star: Int, rating: Int, enabled: Boolean, onTap: (Int) -> Unit) {
	val filled = starFilled(star, rating)
	IconButton(
		onClick = { onTap(star) },
		enabled = enabled,
		// 48 dp tall to touch; 40 wide, so five fit beside the title (audit L1-12: they were 30 x 32).
		modifier = Modifier.width(40.dp).height(MIN_TOUCH_DP.dp)
	) {
		Box(contentAlignment = Alignment.Center) {
			Icon(
				if (filled) Icons.Filled.Star else Icons.Outlined.Star,
				contentDescription = stringResource(Res.string.staged_rate_stars, star),
				tint = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(20.dp)
			)
		}
	}
}
