package paige.navic.ui.components.common

import paige.navic.ui.util.crossfadeMsFor
import paige.navic.ui.util.rememberReducedMotion
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.koin.compose.koinInject
import paige.navic.di.getStaticImageLoader
import paige.navic.domain.manager.SessionManager
import kotlin.math.roundToInt
import kotlin.time.TimeSource
import coil3.compose.LocalPlatformContext as LocalCoilPlatformContext

/** The cover is asked for at this size: an 80 dp blur over the whole screen shows nothing finer. */
internal const val BLEND_ART_SIZE_PX = 256

/** The artwork layers are drawn at this scale and stretched back, with the blur scaled to match. */
internal const val BLEND_RENDER_SCALE = 0.25f

private val BLEND_BLUR_RADIUS = 80.dp

/**
 * Blurred, saturation-boosted cover-art wash. Fills its parent by default; pass a sizing
 * [modifier] (and, for a capped hero, a `BlendMode.DstIn` fade mask) to confine it to a region —
 * [modifier] is applied outside the blur, so a mask there fades the already-blurred result.
 *
 * [scrim] is drawn over the artwork; the default flat black works for a full-screen wash, while a
 * vertical brush lets a capped hero resolve to the page's own surface colour at its bottom edge.
 */
@Composable
fun BlendBackground(
	coverArtId: String?,
	modifier: Modifier = Modifier,
	isPaused: Boolean = false,
	scrim: Brush? = null
) {
	val resolvedScrim = scrim ?: remember { SolidColor(Color.Black.copy(alpha = 0.4f)) }
	var frameRotation by remember { mutableStateOf(0f) }
	var topLeftRotation by remember { mutableStateOf(0f) }
	var botRightRotation by remember { mutableStateOf(0f) }

	val colorMatrix = remember {
		ColorMatrix().apply { setToSaturation(1.5f) }
	}

	val coilPlatformContext = LocalCoilPlatformContext.current

	// One loader for the process, so the wash's decode is shared by every wash on screen.
	val staticImageLoader = remember(coilPlatformContext) {
		getStaticImageLoader(coilPlatformContext)
	}

	val sessionManager = koinInject<SessionManager>()
	// Reduced motion: one still frame, no rotation and no cross-fade (audit L1-03).
	val reduced = rememberReducedMotion()
	val fadeMs = crossfadeMsFor(reduced, 400)
	// 256 px, with the size in the memory key (adapted from upstream 6947d2f9): the request had no
	// size, so the cover was decoded to fill the screen, up to ~2400 px square per bitmap. The disk
	// entry stays the bare id (B-038), shared with the tiles.
	val model = remember(coverArtId, fadeMs) {
		ImageRequest.Builder(coilPlatformContext)
			.data(coverArtId?.let { sessionManager.getCoverArtUrl(it) })
			.size(BLEND_ART_SIZE_PX, BLEND_ART_SIZE_PX)
			.memoryCacheKey(coverArtId?.let { "${it}_static_$BLEND_ART_SIZE_PX" })
			.diskCacheKey(coverArtId)
			.diskCachePolicy(CachePolicy.ENABLED)
			.memoryCachePolicy(CachePolicy.ENABLED)
			.crossfade(fadeMs)
			.build()
	}

	LaunchedEffect(isPaused, reduced) {
		if (!isPaused && !reduced) {
			val timeSource = TimeSource.Monotonic
			var lastFrameMark = timeSource.markNow()

			while (true) {
				withFrameNanos { _ ->
					val now = timeSource.markNow()
					val elapsed = now - lastFrameMark
					val elapsedMillis =
						elapsed.toDouble(kotlin.time.DurationUnit.MILLISECONDS).toFloat()
					lastFrameMark = now

					frameRotation -= (360f / 24000f) * elapsedMillis
					topLeftRotation += (360f / 12000f) * elapsedMillis
					botRightRotation += (360f / 20000f) * elapsedMillis
				}
			}
		}
	}

	// The background and the scrim are flat (or a smooth vertical brush), which a blur leaves as
	// they are, so only the artwork is blurred: drawn at a quarter of the size with a quarter of the
	// radius, then stretched back (adapted from upstream 6947d2f9). The colour filter is applied
	// under the blur, as before, so the wash is the same picture at a sixteenth of the pixels.
	Box(
		modifier = modifier
			.fillMaxSize()
			.background(MaterialTheme.colorScheme.background)
	) {
		Box(
			modifier = Modifier
				.fillMaxSize()
				.renderAtScale(BLEND_RENDER_SCALE)
				.blur(BLEND_BLUR_RADIUS * BLEND_RENDER_SCALE)
		) {
			AsyncImage(
				model = model,
				imageLoader = staticImageLoader,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				colorFilter = ColorFilter.colorMatrix(colorMatrix),
				modifier = Modifier.fillMaxSize()
			)
			Box(
				modifier = Modifier
					.fillMaxSize()
					.graphicsLayer { rotationZ = frameRotation }
			) {
				Box(
					modifier = Modifier
						.fillMaxWidth(0.5f)
						.fillMaxHeight(0.5f)
						.align(Alignment.TopStart)
				) {
					AsyncImage(
						model = model,
						imageLoader = staticImageLoader,
						contentDescription = null,
						contentScale = ContentScale.Crop,
						alignment = Alignment.TopStart,
						colorFilter = ColorFilter.colorMatrix(colorMatrix),
						modifier = Modifier
							.fillMaxSize()
							.graphicsLayer { rotationZ = topLeftRotation }
					)
				}
				Box(
					modifier = Modifier
						.fillMaxWidth(0.5f)
						.fillMaxHeight(0.5f)
						.align(Alignment.BottomEnd)
				) {
					AsyncImage(
						model = model,
						imageLoader = staticImageLoader,
						contentDescription = null,
						contentScale = ContentScale.Crop,
						alignment = Alignment.BottomEnd,
						colorFilter = ColorFilter.colorMatrix(colorMatrix),
						modifier = Modifier
							.fillMaxSize()
							.graphicsLayer { rotationZ = botRightRotation }
					)
				}
			}
		}
		Spacer(
			modifier = Modifier
				.fillMaxSize()
				.drawWithContent {
					drawContent()
					drawRect(brush = resolvedScrim)
				}
		)
	}
}

/**
 * Lays the content out at [scale] of the space it is given and draws it stretched back to fill it
 * (upstream `6947d2f9`'s), so whatever it draws, a blur included, costs scale² of the pixels.
 */
private fun Modifier.renderAtScale(scale: Float): Modifier = layout { measurable, constraints ->
	if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
		val placeable = measurable.measure(constraints)
		return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
	}
	val width = constraints.maxWidth
	val height = constraints.maxHeight
	val placeable = measurable.measure(
		Constraints.fixed(
			(width * scale).roundToInt().coerceAtLeast(1),
			(height * scale).roundToInt().coerceAtLeast(1)
		)
	)
	layout(width, height) {
		placeable.placeWithLayer(0, 0) {
			scaleX = 1f / scale
			scaleY = 1f / scale
			transformOrigin = TransformOrigin(0f, 0f)
		}
	}
}
