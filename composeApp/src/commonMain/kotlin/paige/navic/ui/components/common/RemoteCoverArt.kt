package paige.navic.ui.components.common

import paige.navic.ui.util.COVER_FADE_MS
import paige.navic.ui.util.crossfadeMsFor
import paige.navic.ui.util.rememberReducedMotion
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import coil3.compose.LocalPlatformContext as LocalCoilPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.koin.compose.koinInject
import paige.navic.domain.manager.PreferenceManager
import paige.navic.ui.util.placeholderBrush

/**
 * Cover art from a third-party host, for a release the library does not have.
 *
 * Separate from [CoverArt] on purpose, and it must stay separate. [CoverArt]
 * attaches `preferenceManager.customHeadersMap()` to every request, because every
 * image it loads comes from the user's own Navidrome. Those headers are where a
 * reverse-proxy auth header or a Cloudflare Access token lives, so pointing that
 * composable at coverartarchive.org would hand the user's credentials to someone
 * else's server. This one sends no headers at all.
 *
 * A miss is the normal case — plenty of obscure release-groups have no art in the
 * Archive — so both the empty and the failed states render the same plain
 * placeholder box the null branch of [CoverArt] does. A broken-image icon here
 * would be noise, not information.
 */
@Composable
fun RemoteCoverArt(
	modifier: Modifier = Modifier,
	url: String?,
	contentDescription: String? = null,
	onClick: (() -> Unit)? = null,
	onLongClick: (() -> Unit)? = null,
	square: Boolean = true,
	crossfadeMs: Int = COVER_FADE_MS,
	shape: Shape? = null,
	/** The card's own, so a press anywhere on the card ripples the cover (as `CoverArt`). */
	interactionSource: MutableInteractionSource? = null,
	/** navi-connect P1: the box behind the image is this name's tinted gradient (as `CoverArt`'s). */
	placeholderName: String? = null
) {
	val preferenceManager = koinInject<PreferenceManager>()
	val resolvedShape = shape ?: preferenceManager.coverArtShape.shape
	val coilPlatformContext = LocalCoilPlatformContext.current

	val fadeMs = crossfadeMsFor(rememberReducedMotion(), crossfadeMs)
	val model = remember(url, fadeMs) {
		ImageRequest.Builder(coilPlatformContext)
			.data(url)
			.memoryCacheKey(url)
			.diskCacheKey(url)
			.diskCachePolicy(CachePolicy.ENABLED)
			.memoryCachePolicy(CachePolicy.ENABLED)
			.crossfade(fadeMs)
			.build()
	}

	val tint = remember(placeholderName) { placeholderName?.let { placeholderBrush(it) } }
	val boxColor = MaterialTheme.colorScheme.surfaceContainer

	val commonModifier = modifier
		.then(if (square) Modifier.aspectRatio(1f) else Modifier)
		.clip(resolvedShape)
		.then(if (tint != null) Modifier.background(tint) else Modifier.background(boxColor))
		.then(
			if (onClick != null)
				Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
			else Modifier
		)
		.then(
			if (interactionSource != null) Modifier.indication(interactionSource, ripple())
			else Modifier
		)

	if (url.isNullOrBlank()) return Box(commonModifier)
	SubcomposeAsyncImage(
		model = model,
		contentDescription = contentDescription,
		modifier = commonModifier,
		contentScale = ContentScale.Crop,
		error = { Box(Modifier) }
	)
}
