package paige.navic.ui.screens.artist.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_more
import org.jetbrains.compose.resources.stringResource
import paige.navic.ui.components.common.CoverArt
import paige.navic.ui.components.common.MarqueeText
import paige.navic.ui.util.onAmbientColor
import paige.navic.ui.util.teaserText

/** Three lines of prose in the photo header. The old value was 200 characters
 *  of raw HTML, which is a different thing entirely.
 *
 *  Not private: the screen decides whether an About sheet is worth offering,
 *  and "is this bio truncated" has to be the same question there as here — two
 *  copies of the limit is two chances for the affordance and the truncation to
 *  disagree. */
const val ARTIST_TEASER_LIMIT = 220

@Composable
fun ArtistDetailScreenHeading(
	artistName: String,
	coverArtId: String?,
	subtitle: String?,
	lastfm: String?,
	innerPadding: PaddingValues,
	scrolled: Boolean,
	ambientColor: Color,
	/** Opens the full About section. Null when there is nothing more to show, in
	 *  which case the teaser falls back to the Last.fm link as the only route out. */
	onAboutClick: (() -> Unit)? = null
) {
	val layoutDirection = LocalLayoutDirection.current
	val progress by animateFloatAsState(if (scrolled) 0f else 1f)
	BoxWithConstraints(
		modifier = Modifier.fillMaxWidth()
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.height((400.dp / (maxWidth / 300.dp)) + innerPadding.calculateTopPadding())
				.background(MaterialTheme.colorScheme.surfaceContainer)
		) {
			CoverArt(
				coverArtId = coverArtId,
				modifier = Modifier.fillMaxSize(),
				shape = RectangleShape,
				square = false
			)
			Box(
				modifier = Modifier
					.fillMaxSize()
					.background(
						Brush.linearGradient(
							0.025f to ambientColor,
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
				subtitle?.let { subtitle ->
					// HTML is stripped and the cut lands on a word boundary. Both matter:
					// the old `take(200)` rendered a *short* Last.fm bio as the literal
					// `<a href="https://www.last.fm/...">` markup, because nothing here
					// ever stripped it, and cut a long one mid-word.
					val teaser = teaserText(subtitle, ARTIST_TEASER_LIMIT)
					val truncated = teaser.endsWith("\u2026")
					Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
						Text(
							text = teaser,
							style = MaterialTheme.typography.bodySmall,
							color = onAmbientColor(ambientColor, MaterialTheme.colorScheme),
							maxLines = 3,
							overflow = TextOverflow.Ellipsis,
							modifier = Modifier.widthIn(max = 500.dp)
						)
						// The affordance is its OWN line, not appended to the teaser.
						// Appending it put it inside a `maxLines = 3` + Ellipsis Text that
						// the 220-character teaser already fills, so it was laid out and
						// then clipped — present in the string, invisible on the phone.
						// Measured on a device; it is exactly the kind of thing that reads
						// as correct in the source.
						//
						// WHICH way out it offers is decided by the bio alone, never by
						// whether lb-bot has answered — deciding that on `meta` is what
						// made the link appear and then silently vanish a second later.
						// An in-app About beats a link that leaves the app, and a truncated
						// bio always has one worth opening: the rest of itself. Last.fm is
						// the fallback for when there is no sheet at all.
						if (truncated) {
							val more = stringResource(Res.string.action_more)
							if (onAboutClick != null) {
								Text(
									text = more,
									style = MaterialTheme.typography.bodySmall,
									fontWeight = FontWeight.Medium,
									color = MaterialTheme.colorScheme.primary,
									modifier = Modifier.clickable(
										onClickLabel = more,
										role = Role.Button,
										onClick = onAboutClick
									)
								)
							} else if (lastfm != null) {
								Text(
									text = buildAnnotatedString {
										withLink(LinkAnnotation.Url(lastfm)) { append(more) }
									},
									style = MaterialTheme.typography.bodySmall
								)
							}
						}
					}
				}
				MarqueeText(
					text = artistName,
					style = MaterialTheme.typography.displaySmall.copy(
						fontWeight = FontWeight.Bold,
						color = MaterialTheme.colorScheme.primary,
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
