package paige.navic.ui.components.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
	onClick: (() -> Unit)? = null
) {
	when (art) {
		is LbArt.Library -> CoverArt(
			modifier = modifier,
			coverArtId = art.coverArtId,
			contentDescription = contentDescription,
			onClick = onClick,
			isArtist = art.isArtist
		)
		is LbArt.Remote -> RemoteCoverArt(
			modifier = modifier,
			url = art.url,
			contentDescription = contentDescription,
			onClick = onClick
		)
		is LbArt.Placeholder -> CoverArt(
			modifier = modifier,
			coverArtId = null,
			contentDescription = contentDescription,
			onClick = onClick,
			isArtist = art.isArtist
		)
	}
}
