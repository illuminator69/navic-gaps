package paige.navic.ui.screens.external.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.artist_scanning
import navic.composeapp.generated.resources.title_missing_album
import paige.navic.ui.components.common.FillText
import paige.navic.ui.components.common.MarqueeText
import paige.navic.ui.components.common.Pill
import paige.navic.ui.components.common.PillTone
import paige.navic.ui.components.common.StatePill
import paige.navic.ui.components.common.monogramInitials
import paige.navic.ui.theme.defaultFont
import paige.navic.ui.util.placeholderBrush

/**
 * The unowned artist page's heading (P4): no portrait, on purpose (call 9; lb-bot's `imageUrl` is a
 * Wikipedia page image, not a picture chosen for this use). A box tinted from the name, the
 * monogram large and quiet, the name over a gradient into the page, and one pill saying where the
 * artist stands: not in the library, or being scanned. Laid out as the owned artist page's
 * heading is (`ArtistDetailScreenHeading`), so the two pages read as one kind of page.
 */
@Composable
fun ArtistHero(
	name: String,
	scanning: Boolean,
	innerPadding: PaddingValues,
	scrolled: Boolean,
	modifier: Modifier = Modifier
) {
	val layoutDirection = LocalLayoutDirection.current
	val progress by animateFloatAsState(if (scrolled) 0f else 1f)
	val page = MaterialTheme.colorScheme.surface
	BoxWithConstraints(modifier.fillMaxWidth()) {
		Box(
			Modifier
				.fillMaxWidth()
				.height((400.dp / (maxWidth / 300.dp)) + innerPadding.calculateTopPadding())
				.background(placeholderBrush(name))
		) {
			// The placeholder's gradient is fixed and dark (`placeholderColors`), so the monogram
			// is light at any scheme, as the browse cards' titles over the same tint are.
			Text(
				monogramInitials(name),
				fontFamily = defaultFont(grade = 100, round = 100f),
				fontSize = 120.sp,
				fontWeight = FontWeight.Bold,
				color = Color.White.copy(alpha = 0.35f),
				modifier = Modifier
					.align(Alignment.Center)
					.padding(top = innerPadding.calculateTopPadding())
					.semantics { hideFromAccessibility() }
			)
			Box(
				Modifier
					.fillMaxSize()
					.background(
						Brush.linearGradient(
							0.025f to page,
							1.0f to Color.Transparent,
							start = Offset(0f, Float.POSITIVE_INFINITY),
							end = Offset(0f, 0f)
						)
					)
			)
			Column(
				modifier = Modifier
					.align(Alignment.BottomStart)
					.padding(horizontal = 20.dp)
					.padding(start = innerPadding.calculateStartPadding(layoutDirection))
					.padding(end = innerPadding.calculateEndPadding(layoutDirection)),
				verticalArrangement = Arrangement.spacedBy(8.dp)
			) {
				StatePill(
					if (scanning) Pill(FillText.Res(Res.string.artist_scanning), PillTone.RUN)
					else Pill(FillText.Res(Res.string.title_missing_album), PillTone.MUTE)
				)
				MarqueeText(
					text = name,
					style = MaterialTheme.typography.displaySmall.copy(
						fontWeight = FontWeight.Bold,
						color = MaterialTheme.colorScheme.primary
					),
					modifier = Modifier
						.fillMaxWidth()
						.alpha(progress)
						.scale(progress)
				)
			}
		}
	}
}
