package paige.navic.ui.screens.stats.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.count_plays
import org.jetbrains.compose.resources.pluralStringResource
import org.koin.compose.koinInject
import paige.navic.domain.manager.PreferenceManager
import paige.navic.ui.components.common.CoverArt
import paige.navic.ui.screens.stats.viewmodels.ArtistStats
import paige.navic.ui.theme.defaultFont
import paige.navic.ui.util.rememberCoverColorScheme
import paige.navic.util.toSummaryString

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TopArtistItem(
	stats: ArtistStats,
	ratio: Float,
	onClick: () -> Unit,
	onLongClick: () -> Unit
) {
	val artist = stats.artist
	val preferenceManager = koinInject<PreferenceManager>()
	val listenTime = stats.listeningTime.toSummaryString()
	val plays = pluralStringResource(Res.plurals.count_plays, stats.playCount, stats.playCount)
	RankingItem(
		name = artist.name,
		overline = "$listenTime • $plays",
		ratio = ratio,
		barCoverArtId = artist.coverArtId,
		onClick = onClick,
		onLongClick = onLongClick,
		leading = {
			CoverArt(
				coverArtId = artist.coverArtId,
				modifier = Modifier.size(56.dp),
				shape = preferenceManager.artistImageShape.decreasedShape
			)
		}
	)
}

/**
 * navi-connect: [TopArtistItem]'s row for any ranking: [leading], the [overline] over the name,
 * and a bar at [ratio] of the top entry in the colours of [barCoverArtId]'s artwork, or the page's
 * accent where there is none. Stats' listening history ranks its artists and its genres with it.
 * With no [onClick] the row is plain text, not a dead control.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RankingItem(
	name: String,
	overline: String,
	ratio: Float,
	barCoverArtId: String?,
	onClick: (() -> Unit)?,
	modifier: Modifier = Modifier,
	onLongClick: (() -> Unit)? = null,
	leading: @Composable () -> Unit
) {
	// The fork's engine, not upstream's rememberColorSchemeFromCoverArt: that one is the
	// rememberDominantColorState path replaced at alpha58 (it seeds off dominantSwatch, a flat
	// minority patch) and it derives at the APP's themeMode, so on a page whose brightness
	// follows the artwork it returns a light-theme primary — a black bar on a dark page.
	// followArtworkBrightness = false because the PAGE's brightness is fixed here; a row must
	// not flip because one artist photo happens to be dark.
	val pageIsDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
	val cover = rememberCoverColorScheme(
		coverArtId = barCoverArtId,
		isDark = pageIsDark,
		followArtworkBrightness = false
	)
	// An unresolved palette is a stand-in, not a statement about this artwork, so fall back to
	// the page's own accent rather than painting a bar with a fabricated colour.
	val barColor = if (barCoverArtId != null && cover.resolved) cover.scheme.primary
	else MaterialTheme.colorScheme.primary
	val overlineContent: @Composable () -> Unit = {
		Text(
			text = overline,
			fontWeight = FontWeight.SemiBold,
			fontFamily = defaultFont(round = 100f)
		)
	}
	// Eased, so a ranking that changes under the row (Stats' period) slides to its new length.
	val shownRatio by animateFloatAsState(ratio, MaterialTheme.motionScheme.defaultSpatialSpec())
	val bar: @Composable () -> Unit = {
		LinearProgressIndicator(
			progress = { shownRatio },
			modifier = Modifier
				.fillMaxWidth()
				.padding(top = 4.dp)
				.height(8.dp)
				.clip(MaterialTheme.shapes.extraSmall),
			color = barColor,
			trackColor = barColor.copy(alpha = 0.2f),
			strokeCap = StrokeCap.Round
		)
	}
	if (onClick != null) {
		ListItem(
			onClick = onClick,
			onLongClick = onLongClick,
			modifier = modifier,
			leadingContent = leading,
			overlineContent = overlineContent,
			supportingContent = bar,
			verticalAlignment = Alignment.CenterVertically
		) {
			Text(name)
		}
	} else {
		ListItem(
			headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
			modifier = modifier,
			leadingContent = leading,
			overlineContent = overlineContent,
			supportingContent = bar
		)
	}
}
