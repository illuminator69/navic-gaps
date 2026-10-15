package paige.navic.ui.components.common

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import paige.navic.domain.manager.LbArt

/**
 * The picture an lb-bot row or card shows, through the app's own art components: `CoverArt` for
 * what the library holds (an artist in the artist shape) and for the placeholder, `RemoteCoverArt`
 * for a public URL. What to draw is decided elsewhere, from ids the row already carries
 * ([LbArt]); this only draws it, so a page of a hundred rows makes no request to learn a picture.
 */
@Composable
fun LbArtImage(
	art: LbArt,
	modifier: Modifier = Modifier,
	contentDescription: String? = null,
	onClick: (() -> Unit)? = null,
	onLongClick: (() -> Unit)? = null,
	/** A row's thumbnail passes the decreased cover shape, as upstream's list rows do. */
	shape: Shape? = null,
	/** A card's own, so the cover ripples for a press anywhere on the card. */
	interactionSource: MutableInteractionSource? = null
) {
	when (art) {
		is LbArt.Library -> CoverArt(
			modifier = modifier,
			coverArtId = art.coverArtId,
			contentDescription = contentDescription,
			onClick = onClick,
			onLongClick = onLongClick,
			isArtist = art.isArtist,
			shape = shape,
			interactionSource = interactionSource
		)
		is LbArt.Remote -> RemoteCoverArt(
			modifier = modifier,
			url = art.url,
			contentDescription = contentDescription,
			onClick = onClick,
			onLongClick = onLongClick,
			shape = shape,
			interactionSource = interactionSource
		)
		is LbArt.Placeholder -> CoverArt(
			modifier = modifier,
			coverArtId = null,
			contentDescription = contentDescription,
			onClick = onClick,
			onLongClick = onLongClick,
			isArtist = art.isArtist,
			shape = shape,
			interactionSource = interactionSource
		)
	}
}
