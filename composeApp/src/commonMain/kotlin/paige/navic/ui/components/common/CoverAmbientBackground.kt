package paige.navic.ui.components.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import paige.navic.di.ForceSystemBars
import paige.navic.ui.theme.NavicTheme
import paige.navic.ui.util.CoverColors
import paige.navic.ui.util.coverAmbientGradient
import paige.navic.ui.util.onAmbientColor
import paige.navic.ui.util.rememberAppIsDark
import paige.navic.ui.util.rememberCoverColorScheme
import paige.navic.ui.util.rememberNowPlayingCoverArtId

/**
 * The cover-ambient page background: the blurred artwork with the page's own
 * [coverAmbientGradient] drawn over it as a scrim.
 *
 * ONE implementation, because four surfaces want exactly this and three of them had grown their
 * own copy of it. The album page's version was the one that read correctly on every cover — it is
 * the only page whose colours never fought the app theme — so it is the one everything else now
 * uses (see [BrowsingAmbient]).
 *
 * The gradient is BlendBackground's scrim rather than a separate layer, so the colour landing
 * under the text is the known `coverAmbientGradient` one that `onAmbientColor` derives contrast
 * from, while the artwork shows through only as texture.
 *
 * Two performance rules are baked in and are not incidental:
 * - `isPaused = true` pins BlendBackground's rotation. These sit behind scrolling content, and an
 *   always-animating 80dp blur there is precisely the jank a flat gradient was once chosen to
 *   avoid.
 * - the eased seed is read inside `drawWithCache`, i.e. in the DRAW phase. Read in composition, a
 *   song change would recompose the whole page — blur node included — on every frame of the
 *   450 ms ease.
 */
@Composable
fun CoverAmbientBackground(
	coverArtId: String?,
	seed: Color,
	isDark: Boolean,
	modifier: Modifier = Modifier,
	/** Passed straight to [BlendBackground]; null keeps its default black-0.4 wash. */
	artworkScrim: Brush? = null
) {
	val animatedSeed = animateColorAsState(seed, animationSpec = tween(450))
	BlendBackground(
		coverArtId = coverArtId,
		isPaused = true,
		scrim = artworkScrim,
		modifier = modifier.drawWithCache {
			val (top, bottom) = coverAmbientGradient(animatedSeed.value, isDark)
			// Mostly opaque: what reaches the text stays essentially the ambient colour that
			// `onAmbientColor` guarantees contrast against.
			val wash = Brush.verticalGradient(
				listOf(top.copy(alpha = 0.82f), bottom.copy(alpha = 0.92f))
			)
			onDrawWithContent {
				drawContent()
				drawRect(wash)
			}
		}
	)
}

/**
 * The browsing pages' theming — the library home and every tab and list screen behind
 * `App.kt`'s nav entries — using the SAME pipeline as the album and artist detail pages.
 *
 * It used to be different in the two ways that made the browsing pages look inconsistent:
 *
 * 1. **It kept the app's light/dark brightness** (`followArtworkBrightness = false`) while the
 *    detail pages follow the artwork's. So a bright sleeve under a dark app theme, or a dark one
 *    under a light theme, was mixed into a surface of the opposite polarity and came out as a
 *    muddy near-grey — the same cover that rendered cleanly on its own album page. Brightness
 *    follows the ARTWORK here now, which is why the detail pages never had the problem.
 * 2. **The wash was a flat surface colour**, not a gradient: `surface`/`background` were replaced
 *    by one uniform 32% mix. That was chosen because a *drawn* background leaves every component
 *    painting `surface` on top of it as a slab. Following the artwork's brightness is what removes
 *    that constraint — the scheme's own neutrals are now in the same tonal register as the
 *    gradient drawn behind them, which is exactly why the album page can draw one and look right.
 *
 * `background` is the only role overridden, to `Transparent`, so each screen's `Scaffold` (whose
 * container colour defaults to it) lets the gradient through. `surface` and the `surfaceContainer`
 * roles are deliberately left alone, so rows, cards and sheets still lift off the page in the
 * page's own hue.
 *
 * The background is drawn OUTSIDE the theme on purpose: [BlendBackground] fills from
 * `colorScheme.background`, and reading the transparent one would leave it with no base at all.
 */
@Composable
fun BrowsingAmbient(content: @Composable () -> Unit) {
	val coverArtId = rememberNowPlayingCoverArtId()
	val cover: CoverColors = rememberCoverColorScheme(coverArtId, isDark = rememberAppIsDark())
	// Nothing playing, theming off, or a palette that hasn't landed: the app's own scheme, and no
	// background of our own. `resolved` rather than `coverArtId != null` — see CoverColorScheme.
	if (!cover.themed || !cover.resolved) {
		NavicTheme(cover.scheme, content = content)
		return
	}
	val (ambientTop, _) = coverAmbientGradient(cover.seed, cover.isDark)
	// Status-bar icons follow the page's (cover-driven) brightness, as on the detail pages —
	// required now that a browsing page's brightness can differ from the app theme's.
	ForceSystemBars(cover.isDark)
	Box(Modifier.fillMaxSize()) {
		CoverAmbientBackground(coverArtId, cover.seed, cover.isDark)
		NavicTheme(
			cover.scheme.copy(background = Color.Transparent),
			contentColor = onAmbientColor(ambientTop, cover.scheme),
			content = content
		)
	}
}
