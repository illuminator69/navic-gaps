package paige.navic.ui.util

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kmpalette.generatePalette
import com.kmpalette.loader.rememberNetworkLoader
import com.kmpalette.palette.graphics.Palette
import com.kmpalette.palette.graphics.Palette.Swatch
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koin.compose.koinInject
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.SessionManager
import paige.navic.domain.models.settings.ThemeMode
import paige.navic.shared.MediaPlayerViewModel
import paige.navic.util.CoverPlaceholder
import paige.navic.util.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first

/**
 * One client for ALL cover-art palette extraction. Built OUTSIDE composition on
 * purpose: constructing an [HttpClient] inside a `@Composable` allocated a fresh
 * client — and its connection pool + engine threads — on every recomposition, and
 * nothing ever closed them.
 */
internal val paletteHttpClient: HttpClient by lazy {
	HttpClient {
		install(HttpTimeout) {
			requestTimeoutMillis = 60_000
			connectTimeoutMillis = 60_000
			socketTimeoutMillis = 60_000
		}
	}
}

/**
 * Extracted palettes, kept for the app's lifetime and keyed by cover id.
 *
 * Without this, every composable that asks for a cover's colours re-downloads and re-quantises the
 * image — and a composable inside a lazy list gets disposed and recreated as you scroll, so its
 * colours would pop back to the neutral default and fade in again on every pass. Bounded because a
 * long session browses a lot of covers; entries are cheap (a handful of swatches each).
 *
 * Only ever touched from the main thread (composition + `LaunchedEffect` bodies).
 */
private const val PALETTE_CACHE_MAX = 128
private val paletteCache = object {
	// Insertion-ordered, so the first key is the oldest.
	private val entries = mutableMapOf<String, Palette>()

	operator fun get(id: String): Palette? = entries[id]

	operator fun set(id: String, palette: Palette) {
		entries[id] = palette
		while (entries.size > PALETTE_CACHE_MAX) {
			entries.remove(entries.keys.first())
		}
	}
}

/**
 * Derived Material schemes, keyed by the inputs that produce them.
 *
 * [paletteCache] above stops the artwork being re-downloaded and re-quantised, but the step AFTER
 * it — materialKolor's HCT/CAM16 derivation of ~30 colour roles from the seed — is a `remember`
 * inside `rememberDynamicColorScheme`, so it dies with the composition that made it. That matters
 * because `RootBottomBar` (and so the mini-player, which reads the now-playing cover) is built
 * fresh inside EVERY screen's own Scaffold: navigating anywhere tore its composition down and paid
 * for the whole derivation again on the UI thread, mid-transition, for a song that hadn't changed.
 *
 * Small, because the key is (seed, brightness, style) and a session only plays so many covers.
 * Main-thread only, like [paletteCache].
 */
private const val SCHEME_CACHE_MAX = 64
private data class SchemeKey(
	val seed: Color,
	/** The neutral/surface seed — a scheme's backgrounds and its accent come from different swatches. */
	val neutral: Color,
	val isDark: Boolean,
	val style: PaletteStyle
)
private val schemeCache = object {
	private val entries = mutableMapOf<SchemeKey, ColorScheme>()

	operator fun get(key: SchemeKey): ColorScheme? = entries[key]

	operator fun set(key: SchemeKey, scheme: ColorScheme) {
		entries[key] = scheme
		while (entries.size > SCHEME_CACHE_MAX) {
			entries.remove(entries.keys.first())
		}
	}
}

/** The pixel size the palette is extracted from. Quantisation sees no more than this anyway. */
private const val PALETTE_PX = 128

/** How many times a palette fetch is attempted before the cover is left unresolved. */
private const val PALETTE_ATTEMPTS = 3
private const val PALETTE_RETRY_DELAY_MS = 400L

/** A palette swatch's packed ARGB as a Compose [Color]. */
private fun Swatch.toColor(): Color = Color(rgb)

/** Shortest distance between two hues on the colour wheel, in degrees (0-180). */
private fun hueDistance(a: Float, b: Float): Float {
	val d = abs(a - b) % 360f
	return if (d > 180f) 360f - d else d
}

/** Beyond this many degrees apart, two swatches are not the same colour to the eye. */
private const val HUE_FAMILY_DEGREES = 45f

/** Below this saturation a swatch has no meaningful hue, and belongs to the greyscale family. */
private const val NEUTRAL_SATURATION = 0.12f

/**
 * The leading swatch of the most populous colour FAMILY — what a person would say the artwork
 * "is" — rather than the single most populous swatch.
 *
 * `Palette.dominantSwatch` is the biggest *quantisation box*, which is not the same thing and on
 * real sleeves is often not even close. Median cut subdivides crowded regions, so a colour that
 * covers half the cover in a hundred shades is split across half a dozen boxes and each one is
 * small, while a flat minority area — a border, a patch of sky — survives whole and wins. Measured
 * on Yo La Tengo's `I Can Hear the Heart Beating as One`: red/magenta 43% of the sleeve, lilac
 * 43%, pale blue 13%, and `dominantSwatch` answered the blue. The page came out ice blue for a
 * cover nobody would describe as blue.
 *
 * So each swatch is scored by the population of its whole neighbourhood, weighted by how far each
 * neighbour's hue is from its own — a kernel density estimate over the hue wheel, with no bin
 * edges for a family to fall across. Swatches too desaturated to have a hue are scored as one
 * greyscale family instead, which is what keeps a black-and-white cover neutral rather than
 * handing it whichever faint tint happens to survive quantisation.
 */
private fun List<Swatch>.dominantByColorFamily(): Swatch? {
	if (isEmpty()) return null
	val neutral = filter { it.hsl[1] < NEUTRAL_SATURATION }
	val chromatic = filter { it.hsl[1] >= NEUTRAL_SATURATION }

	val neutralScore = neutral.sumOf { it.population.toDouble() }
	// Scored once, and the winner read off the same list: computing the score in two places is two
	// chances for the comparison and the pick to disagree.
	val (best, bestScore) = chromatic
		.map { swatch ->
			swatch to chromatic.sumOf { other ->
				val weight = 1f - hueDistance(swatch.hsl[0], other.hsl[0]) / HUE_FAMILY_DEGREES
				if (weight <= 0f) 0.0 else other.population * weight.toDouble()
			}
		}
		.maxByOrNull { it.second }
		?: (null to 0.0)

	return if (best == null || neutralScore > bestScore) {
		neutral.maxByOrNull { it.population } ?: best
	} else {
		// The family's own leading swatch: the colour to name it by, at the tone it actually is.
		chromatic
			.filter { hueDistance(it.hsl[0], best.hsl[0]) <= HUE_FAMILY_DEGREES }
			.maxByOrNull { it.population }
	}
}

/** A readable Material scheme derived from artwork plus the extracted seed color. */
data class CoverColors(
	val scheme: ColorScheme,
	val seed: Color,
	/**
	 * The artwork's MOST POPULOUS colour, raw. This is what the cover "is" to the eye at a
	 * glance — the sleeve's field colour, not the detail printed on it — so it is what any
	 * BACKGROUND derived from the cover should follow. Distinct from [seed] (pulled partway
	 * toward the vivid swatch) and from the accent behind `scheme.primary` (the vivid swatch
	 * itself): a navy sleeve with a green squiggle on it has a navy dominant and a green accent.
	 */
	val dominant: Color,
	/** Resolved page brightness — follows the ARTWORK's luminance, not the app theme. */
	val isDark: Boolean,
	/**
	 * True only once a palette is actually in hand. False means [dominant] and [scheme] are a
	 * stand-in (the app's scheme, or the colour we navigated from) and must not be treated as a
	 * statement about this artwork — see the note in [rememberCoverColorScheme].
	 */
	val resolved: Boolean,
	/**
	 * False when the user has turned cover theming off. Everything here is then the app's own
	 * scheme, and a caller that paints its OWN surface from [seed] (the detail screens' hero wash,
	 * the blurred backdrop) must skip it — those don't read [scheme], so the gate can't reach them.
	 */
	val themed: Boolean
)

/**
 * Builds a readable Material 3 [ColorScheme] from a cover-art / artist image's
 * dominant color (Apple-Music-style art theming). kmpalette extracts the seed,
 * materialKolor derives the full scheme — so on-colors stay legible over
 * unpredictable artwork. Generalizes the now-playing sheet's `colorSchemeForCurrentSong`.
 *
 * Extraction is async: [CoverColors.seed]/[scheme] start at a neutral default and
 * update once the (downsized) image loads. Caches by [coverArtId] via remember.
 */
@Composable
fun rememberCoverColorScheme(
	coverArtId: String?,
	isDark: Boolean = true,
	initialSeed: Color? = null,
	// When false, the scheme keeps the passed [isDark] (app) brightness instead of following
	// the artwork's luminance. Used by the library home, whose page brightness should stay put
	// (only the accent hues adapt to the now-playing song) rather than flipping per cover.
	followArtworkBrightness: Boolean = true
): CoverColors {
	val themingOn = coverThemingEnabled()
	val sessionManager = koinInject<SessionManager>()
	// Navidrome serves a flat grey person-glyph PNG for every artist with no picture, under a
	// perfectly valid cover id (see [CoverPlaceholder]). It is a served image, so quantising it
	// answers "this artist is grey" for all ~26 of them at once. `CoverArt` has always refused to
	// DRAW it; refusing to THEME off it is the same rule, and it has to be here rather than at the
	// call sites because the artist page, `ArtistSheet` and the now-playing chrome all feed ids in.
	val hasArt = themingOn && coverArtId != null && !CoverPlaceholder.isPlaceholder(coverArtId)
	// 128px, asked for as a PARAMETER: `getCoverArtUrl` already carries the user's cover-art
	// quality, so appending `&size=128` gave `…&size=4096&size=128` and Subsonic honoured the
	// FIRST — every palette extraction pulled and quantised a 4096px JPEG over a bare Ktor client
	// with a 60s timeout, which is what made the unresolved state below a routine sight.
	val coverUri = remember(coverArtId, hasArt) {
		coverArtId?.takeIf { hasArt }?.let { sessionManager.getCoverArtUrl(it, size = PALETTE_PX) }
	}
	val networkLoader = rememberNetworkLoader(paletteHttpClient)
	// ONE extraction per cover, and every colour below is read off that one palette — so a cover
	// resolves in a single step. (Running the wash and the accent as two separate
	// `DominantColorState`s made covers visibly change colour TWICE; kmpalette's `PaletteState`
	// wrapper never resolved at all. Loading the bitmap and building the palette directly is both
	// simpler and the thing we can actually reason about.)
	//
	// NOT keyed on the cover id: the previous palette stays put until the new one is ready, so
	// changing songs eases from the old colour instead of flashing through the neutral default.
	var palette by remember { mutableStateOf(coverArtId?.let { paletteCache[it] }) }

	LaunchedEffect(coverUri, coverArtId) {
		val id = coverArtId ?: return@LaunchedEffect
		val uri = coverUri ?: return@LaunchedEffect
		paletteCache[id]?.let {
			palette = it
			return@LaunchedEffect
		}
		// LaunchedEffect bodies run on the composition dispatcher (main). The Ktor fetch suspends
		// off it on its own, but the quantisation afterwards is plain CPU work, and it lands
		// exactly when the artwork changes — i.e. the moment the colour adaptation is visible.
		// Retried, and the failure LOGGED. This used to be `runCatching { … }.getOrNull() ?:
		// return@LaunchedEffect`: silent, and permanent for the life of the composable, because the
		// effect's key never changes again. A single flaky fetch therefore pinned one screen to the
		// unresolved state while a sheet opened seconds later — a fresh composable, a fresh
		// attempt — showed the right colours for the same cover. That asymmetry was the bug report.
		var result: Palette? = null
		for (attempt in 0 until PALETTE_ATTEMPTS) {
			if (attempt > 0) delay(PALETTE_RETRY_DELAY_MS * attempt)
			val outcome = runCatching {
				withContext(Dispatchers.Default) {
					networkLoader.load(Url(uri)).generatePalette()
				}
			}
			result = outcome.getOrNull()
			if (result != null) break
			Logger.w("CoverColorScheme", "palette fetch failed for $id (attempt ${attempt + 1})", outcome.exceptionOrNull())
		}
		if (result == null) return@LaunchedEffect
		paletteCache[id] = result
		palette = result
	}

	val appScheme = MaterialTheme.colorScheme
	val fallback = appScheme.surface
	/**
	 * Did extraction actually answer? Everything below used to run regardless, and an unresolved
	 * cover was not neutral — it was a FABRICATED scheme. With no palette the dominant falls back
	 * to `MaterialTheme.colorScheme.surface`, `vivid` is null, so the accent seed became that
	 * near-white surface run through [boostedAccent] (saturation x1.6) and then through
	 * `dynamicColorScheme` under `PaletteStyle.Content` — which was chosen on `coverUri != null`,
	 * not on having a palette. The surface's own faint M3 cast was therefore amplified into an
	 * obvious accent: measured on device, `#faf8fe` produced a dusty ROSE page while a navy/teal
	 * sleeve was playing. Worse, `fallback` reads the ENCLOSING theme, so inside a washed screen an
	 * unresolved cover seeded itself from the previous wash and drifted further each time.
	 *
	 * An unresolved cover is not a colour. It is an absence, and the app's own scheme is what an
	 * absence should look like.
	 */
	val resolved = themingOn && palette != null
	// `initialSeed` is a real colour — the screen we navigated FROM (see [AmbientColorHolder]) — so
	// a cover still being fetched eases from something meaningful rather than from nothing. But it
	// is a BRIDGE to a pending resolve, not evidence about THIS artwork (hence `resolved` stays
	// false), and it only earns its place while artwork is actually on its way: with nothing to
	// fetch — no cover id, or Navidrome's generic avatar — nothing will ever replace it, and an
	// artist with no picture sat permanently in the colour of whatever album you opened it from.
	val hasSeedSource = themingOn && (resolved || (initialSeed != null && coverUri != null))
	// The most POPULOUS colour, by family (see [dominantByColorFamily] — NOT `dominantSwatch`).
	// It decides page BRIGHTNESS — a mostly-black cover has to give a dark page — and, since the
	// scheme's neutrals are seeded from it below, every background. On its own it's still a poor
	// accent: on a black-and-yellow sleeve it's the black, not the yellow the cover reads as.
	val dominant = remember(palette, initialSeed, fallback, themingOn, coverUri) {
		if (!themingOn) fallback
		else palette?.swatches?.dominantByColorFamily()?.toColor()
			?: initialSeed?.takeIf { coverUri != null }
			?: fallback
	}
	// The most populous COLOURFUL swatch: washed-out and near-black/near-white swatches are
	// rejected, so what survives is the colour a human would name the cover by (the yellow).
	// A genuinely greyscale cover has no survivor and falls back to the dominant, staying neutral
	// rather than having a hue invented for it.
	val vivid = remember(palette) {
		palette?.swatches
			?.filter { it.hsl[1] >= 0.25f && it.hsl[2] in 0.25f..0.85f }
			// By family here too, and for the same reason: the accent should be the colour the
			// artwork is known by, not whichever shade of it quantisation happened to keep whole.
			?.dominantByColorFamily()
			?.toColor()
	}
	// The WASH is the dominant colour pulled a third of the way toward the vivid one: enough for
	// the page to actually read as the cover's colour, not so much that a saturated cover turns
	// the background into a glare. This is what "expressive" means here — a black-and-yellow cover
	// should give a dark page that is unmistakably YELLOW-dark, not a flat grey one.
	val seed = if (vivid != null) lerp(dominant, vivid, 0.35f) else dominant
	// The ACCENT (buttons, primary) is the vivid swatch, saturation-boosted so even muted artwork
	// yields an obvious cover colour rather than a washed near-grey `primary`.
	val accentSeed = (vivid ?: dominant).boostedAccent()
	// Page brightness FOLLOWS THE ARTWORK (Apple-Music style): a dark cover gives a dark
	// immersive page (light text), a light cover a light page (dark text) — independent of
	// the app theme mode (a dark album on a light page was the "doesn't match" mismatch).
	// Keyed on the DOMINANT colour, never the vivid one: a bright accent on a dark cover must not
	// flip the whole page to light.
	val coverIsDark = if (!followArtworkBrightness) isDark
		else if (palette != null) dominant.luminance() < 0.5f else isDark
	// Content keeps colourful covers faithful AND leaves a true-greyscale cover naturally
	// grey — it doesn't amplify chroma like Vibrant (which invented a teal from a B&W cover),
	// so no Monochrome special-case is needed; a dark scheme already yields a bright `primary`.
	// Gated on having an actual seed source, NOT on `coverUri != null`: a cover whose fetch is in
	// flight or has failed has no colour to be faithful to, and Content on a near-white fallback is
	// exactly what manufactured the rose accent above.
	val style = if (hasSeedSource) PaletteStyle.Content else PaletteStyle.Monochrome
	// Seed the SCHEME from the vivid swatch (saturation-boosted) so muted/dark artwork still
	// yields an OBVIOUS cover accent (Symfonium-like) rather than a washed near-grey `primary`.
	//
	// The non-composable `dynamicColorScheme` behind our own [schemeCache], rather than
	// `rememberDynamicColorScheme`: its remember dies with the composition, and the mini-player's
	// composition is destroyed on every navigation, so the HCT derivation was being repeated on
	// the UI thread mid-transition for a cover that hadn't changed. The trailing `.copy` used to
	// sit outside any remember too — and since ColorScheme has identity equality, that alone
	// re-published LocalColorScheme (and so invalidated the whole subtree) at every
	// `NavicTheme(coverColors.scheme)` call site, on every recomposition.
	//
	// materialKolor gives LIGHT schemes a near-neutral (almost black) onSurface, so light
	// pages read as "just black text". Tint the content colours toward the cover's `primary`
	// so text carries the artwork's hue on light AND dark pages (matching the queue's look).
	//
	// The SURFACES are seeded separately, from the dominant colour: materialKolor builds the
	// background/`onSurface` family out of `neutral`/`neutralVariant`, so handing it the dominant
	// swatch makes every background the cover's own field colour while the accent stays vivid. One
	// seed for both put the page in the hue of whatever detail happened to be the most colourful
	// thing on the sleeve — a navy record with a green squiggle on it gave a green page. Deriving
	// the neutrals rather than tinting them by hand is also what keeps `onSurface` legible over
	// them, which hand-blending a surface toward a cover colour does not.
	val scheme = if (!hasSeedSource) appScheme else remember(accentSeed, dominant, coverIsDark, style) {
		val key = SchemeKey(accentSeed, dominant, coverIsDark, style)
		schemeCache[key] ?: run {
			val raw = dynamicColorScheme(
				seedColor = accentSeed,
				isDark = coverIsDark,
				style = style,
				neutral = dominant,
				neutralVariant = dominant,
				specVersion = ColorSpec.SpecVersion.SPEC_2021
			)
			raw.copy(
				onSurface = lerp(raw.onSurface, raw.primary, 0.14f),
				onSurfaceVariant = lerp(raw.onSurfaceVariant, raw.primary, 0.18f)
			).also { schemeCache[key] = it }
		}
	}

	return CoverColors(
		scheme = scheme,
		seed = seed,
		dominant = dominant,
		isDark = coverIsDark,
		resolved = resolved,
		themed = themingOn
	)
}

/**
 * Whether cover-derived theming is on at all.
 *
 * Upstream shipped this preference in alpha42 and read it in exactly two places —
 * `ArtistDetailScreen` and `CollectionDetailScreen` — as
 * `if (dynamicTheming) rememberColorSchemeFromCoverArt(...) else null`, feeding `NavicTheme`'s
 * `colorScheme ?: chosenScheme`. That `null` WAS the off switch. The fork replaced both call sites
 * with its own unconditional engine during the alpha58 merge, and `coverColors.scheme` is never
 * null, so the toggle was left with no readers at all: flipping it in Settings did nothing.
 *
 * It is honoured here instead of at the call sites because the fork tints far more than upstream
 * ever did — the library home, every browsing tab via `App.kt`'s `Washed`, the mini-player, the
 * now-playing chrome and every sheet — and one gate in the engine covers all of them, including
 * anything added later. Default is ON: the fork has always behaved that way, and a pref defaulting
 * false would have silently un-themed everyone's app on update.
 */
@Composable
private fun coverThemingEnabled(): Boolean = koinInject<PreferenceManager>().dynamicTheming

/** App dark/light per the user's theme preference (matches the detail screens). */
@Composable
fun rememberAppIsDark(): Boolean {
	val preferenceManager = koinInject<PreferenceManager>()
	return when (preferenceManager.themeMode) {
		ThemeMode.System -> isSystemInDarkTheme()
		ThemeMode.Dark -> true
		ThemeMode.Light -> false
	}
}

/**
 * Cover-derived theming bundle for an ambient surface (sheet / queue): the
 * readable [scheme] to drive content colours via `NavicTheme`, the raw [seed],
 * and [top] — the muted gradient-top colour (same formula as
 * [paige.navic.ui.components.common.ArtAmbientBackground]) used as the sheet's
 * opaque container colour so its drag-handle area blends with the wash.
 */
data class CoverAmbient(
	val scheme: ColorScheme,
	val seed: Color,
	val top: Color,
	val bottom: Color,
	/** Content colour guaranteed to read over [top] — drive `NavicTheme(contentColor = …)`. */
	val onAmbient: Color
)

/**
 * A more vivid, "obvious" accent seed (Symfonium-style): raises saturation while PRESERVING
 * the hue, so washed/dark artwork yields a clear cover accent instead of a near-grey `primary`.
 * A true greyscale colour (no chroma) is returned unchanged, so B&W covers stay neutral.
 */
fun Color.boostedAccent(): Color {
	val r = red; val g = green; val b = blue
	val max = maxOf(r, g, b)
	val min = minOf(r, g, b)
	val delta = max - min
	if (max <= 0f || delta < 0.04f) return this  // black or greyscale → leave neutral
	var h = 60f * when (max) {
		r -> ((g - b) / delta) % 6f
		g -> (b - r) / delta + 2f
		else -> (r - g) / delta + 4f
	}
	if (h < 0f) h += 360f
	val newS = (delta / max * 1.6f).coerceAtMost(1f)   // punchier chroma
	val newV = max.coerceIn(0.5f, 1f)                  // avoid near-black accents
	val c = newV * newS
	val x = c * (1f - abs((h / 60f) % 2f - 1f))
	val m = newV - c
	val (rr, gg, bb) = when {
		h < 60f -> Triple(c, x, 0f)
		h < 120f -> Triple(x, c, 0f)
		h < 180f -> Triple(0f, c, x)
		h < 240f -> Triple(0f, x, c)
		h < 300f -> Triple(x, 0f, c)
		else -> Triple(c, 0f, x)
	}
	return Color(rr + m, gg + m, bb + m)
}

/**
 * Page ambient wash for a cover [seed], eased toward white (light) / black (dark) but
 * keeping enough of the cover colour that the page reads as a rich tint, not washed grey.
 * Shared by the sheets and the album/artist detail screens so every surface matches.
 */
fun coverAmbientGradient(seed: Color, isDark: Boolean): Pair<Color, Color> {
	val target = if (isDark) Color.Black else Color.White
	val top = lerp(seed, target, if (isDark) 0.22f else 0.34f)
	val bottom = lerp(seed, target, if (isDark) 0.45f else 0.20f)
	return top to bottom
}

/**
 * A content colour guaranteed to read over [background]: the scheme's `onSurface` or its
 * inverse, whichever has the greater luminance distance from the painted ambient. The
 * ambient wash is computed independently of the scheme's `surface`, so trusting `onSurface`
 * alone can give low-contrast text on rich/mid-tone covers — this keeps text legible.
 */
fun onAmbientColor(background: Color, scheme: ColorScheme): Color {
	val bg = background.luminance()
	val onSurface = scheme.onSurface
	val inverse = scheme.inverseOnSurface
	return if (abs(onSurface.luminance() - bg) >= abs(inverse.luminance() - bg)) onSurface else inverse
}

@Composable
fun rememberCoverAmbient(
	coverArtId: String?,
	isDark: Boolean = rememberAppIsDark(),
	initialSeed: Color? = null
): CoverAmbient {
	val cover = rememberCoverColorScheme(coverArtId, isDark = isDark, initialSeed = initialSeed)
	// Brightness follows the artwork (cover.isDark), not the app theme passed in.
	// Theming off: the sheet is the app's plain surface. `cover.seed` is that surface already, but
	// the gradient would still ease it toward white/black and tint the sheet for no reason.
	val (rawTop, rawBottom) = if (cover.themed) coverAmbientGradient(cover.seed, cover.isDark)
		else cover.scheme.surface to cover.scheme.surface
	// Ease the colours in as the seed resolves (kmpalette extraction is async) so
	// the sheet wash fades from the neutral default to the cover colour instead of
	// popping instantly. Matches the detail screens' `animateColorAsState`.
	val top by animateColorAsState(rawTop, tween(450))
	val bottom by animateColorAsState(rawBottom, tween(450))
	return CoverAmbient(
		scheme = cover.scheme,
		seed = cover.seed,
		top = top,
		bottom = bottom,
		onAmbient = onAmbientColor(top, cover.scheme)
	)
}

/**
 * The CURRENTLY-PLAYING song's cover id, and nothing else. Collects ONLY the cover id,
 * distinct — so callers recompose on SONG change, not on every ~4 Hz progress tick. Critical:
 * this is read at the App root, so a full `uiState` collect there would re-theme the whole app
 * every tick (unusable lag).
 */
@Composable
fun rememberNowPlayingCoverArtId(): String? {
	val player = koinInject<MediaPlayerViewModel>()
	val coverArtId by remember(player) {
		player.uiState
			.map { it.currentSong?.coverArtId }
			.distinctUntilChanged()
	}.collectAsStateWithLifecycle(player.uiState.value.currentSong?.coverArtId)
	return coverArtId
}

/**
 * [CoverAmbient] for the CURRENTLY-PLAYING song's cover — for surfaces not tied
 * to a specific item (queue, device picker, mood / sort sheets).
 */
@Composable
fun rememberNowPlayingCoverAmbient(): CoverAmbient =
	rememberCoverAmbient(rememberNowPlayingCoverArtId())

/**
 * Background brush for every browsing tab: the plain themed surface. (An earlier cover-tinted
 * "dynamic home background" was removed — it was too laggy on scroll; [Modifier.
 * libraryAmbientBackground] is the cheap version of that idea, a drawn gradient with no blur.)
 * Kept as a single helper so all the tabs share one background source. The home uses the wash
 * instead of this.
 */
@Composable
fun rememberLibraryTabBackground(): Brush = SolidColor(MaterialTheme.colorScheme.surface)

/**
 * The browsing pages' base scheme: the now-playing cover's, or the app's own when nothing is
 * playing. Accents, containers and content colours all come from here; [rememberLibraryWashedScheme]
 * then replaces its surfaces with the wash.
 *
 * One definition for the home and the tabs, because they sit next to each other: the home was
 * already on the cover's scheme while the tabs kept the app's, so a warm page carried lavender
 * chips and a lavender mini-player the moment you left the home.
 */
@Composable
fun rememberNowPlayingScheme(): ColorScheme {
	val coverArtId = rememberNowPlayingCoverArtId()
	val cover = rememberCoverColorScheme(
		coverArtId,
		isDark = rememberAppIsDark(),
		followArtworkBrightness = false
	)
	// `resolved`, NOT `coverArtId != null`. A cover id only says a song is playing; it says nothing
	// about whether its colours are known yet, and publishing the unresolved scheme is what put a
	// fabricated accent on the home page.
	return if (cover.resolved) cover.scheme else MaterialTheme.colorScheme
}

/**
 * How far a browsing page's `surface` is mixed from the app's own toward the now-playing cover's
 * dominant colour. The one knob for the whole effect.
 */
private const val LIBRARY_WASH = 0.32f

/**
 * The browsing pages' scheme: [base] with its `surface` and `background` replaced by the
 * now-playing sleeve's colour.
 *
 * The wash is the page's SURFACE, not a gradient painted behind it. That distinction is the whole
 * point. A drawn background leaves every component that paints `surface` itself — and Material's
 * `ListItem` does, opaquely, at 62 call sites here — sitting on the page as a white slab: the
 * Favorites list read as three bright bars with the wash resuming underneath them. Making the
 * wash the surface means the rows, the alphabet headers, the top bar's scrolled tint and the
 * bottom bar's scrim all match by construction, including anything added later.
 *
 * The price is that the background is flat. A gradient cannot be expressed as a scheme colour, so
 * the choice was one uniform colour everywhere or a gradient with a seam around every list row.
 *
 * `surface` and `background` only: the `surfaceContainer` roles are left alone so cards, chips and
 * sheets still lift off the page. They come from the same dominant-seeded neutral palette (see
 * [rememberCoverColorScheme]), so they lift in the page's own hue.
 *
 * Not animated, deliberately. Publishing a new [ColorScheme] invalidates every descendant that
 * reads it, so easing this over 450ms would recompose the whole screen on every frame of a song
 * change; the scheme's accents already change in one step for the same reason.
 */
@Composable
fun rememberLibraryWashedScheme(base: ColorScheme = rememberNowPlayingScheme()): ColorScheme {
	val cover = rememberCoverColorScheme(
		rememberNowPlayingCoverArtId(),
		isDark = rememberAppIsDark(),
		followArtworkBrightness = false
	)
	// Nothing playing, or a cover whose palette hasn't landed: no wash. (This used to lean on the
	// dominant falling back to the surface, making the lerp a no-op — true only while the fallback
	// and `base.surface` were the same colour, which stopped being so the moment `base` was itself
	// a cover scheme.)
	if (!cover.resolved) return base
	return remember(base, cover.dominant) {
		val wash = lerp(base.surface, cover.dominant, LIBRARY_WASH)
		base.copy(surface = wash, background = wash)
	}
}
