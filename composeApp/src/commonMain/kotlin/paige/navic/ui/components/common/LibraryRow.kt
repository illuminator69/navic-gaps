package paige.navic.ui.components.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The Library's one row (P5 round 2, the owner's note: "everything should match the default
 * library view"): Recents' look, for every list the Library shows and the pages it opens. A
 * rounded segment in a 16 dp gutter, 3 dp from the next, on the song rows' translucent tone, with
 * 50 dp art, a title and one line. The lists differ in what they carry (a menu button, a song's
 * status), not in how they look.
 */
object LibraryRowDefaults {
	val ArtSize = 50.dp
	val Gutter = 16.dp
}

@Composable
fun LibraryRow(
	title: String,
	line: String?,
	onClick: () -> Unit,
	shapes: ListItemShapes,
	art: @Composable () -> Unit,
	modifier: Modifier = Modifier,
	onLongClick: (() -> Unit)? = null,
	enabled: Boolean = true,
	/** A spinner in the art's place: a tap that is being worked on. */
	busy: Boolean = false,
	/** A custom radio's name is its artists, so it may take two lines. */
	titleLines: Int = 1,
	/** Applied to the row only, so a caller that lays out its own gutter passes false. */
	gutter: Boolean = true,
	trailing: (@Composable () -> Unit)? = null
) {
	SegmentedListItem(
		onClick = onClick,
		onLongClick = onLongClick,
		enabled = enabled && !busy,
		shapes = shapes,
		modifier = if (gutter) modifier.padding(horizontal = LibraryRowDefaults.Gutter) else modifier,
		colors = ListItemDefaults.colors(
			containerColor = SongRowDefaults.containerColor(false),
			supportingContentColor = SongRowDefaults.supportingContentColor(false),
			disabledContainerColor = SongRowDefaults.containerColor(false)
		),
		verticalAlignment = Alignment.CenterVertically,
		contentPadding = SongRowDefaults.ContentPadding,
		leadingContent = {
			Box(Modifier.size(LibraryRowDefaults.ArtSize), contentAlignment = Alignment.Center) {
				if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
				else art()
			}
		},
		content = { Text(title, maxLines = titleLines, overflow = TextOverflow.Ellipsis) },
		supportingContent = line?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
		trailingContent = trailing
	)
}

/** A row's cover: the record's sleeve, or an artist's picture in a circle. */
@Composable
fun LibraryRowCover(
	coverArtId: String?,
	name: String?,
	artist: Boolean = false,
	fallbackAlbumId: String? = null
) {
	CoverArt(
		modifier = Modifier.size(LibraryRowDefaults.ArtSize),
		coverArtId = coverArtId,
		contentDescription = null,
		shape = if (artist) CircleShape else null,
		isArtist = artist,
		fallbackAlbumId = fallbackAlbumId,
		placeholderName = name
	)
}

/**
 * A letter over an alphabetical list's group (the owner's note: the letters sat against the left
 * edge). In the rows' gutter, lined up with the cards' edge, on a small pill, so the one a grid
 * keeps stuck over the tiles scrolling under it stays readable. [grid] drops the gutter: a grid has
 * its own. Over rows it is not stuck: it covered the first row's picture.
 */
@Composable
fun LetterHeader(letter: String, modifier: Modifier = Modifier, grid: Boolean = false) {
	Box(
		modifier.padding(
			start = if (grid) 0.dp else LibraryRowDefaults.Gutter,
			top = 10.dp,
			bottom = 6.dp
		)
	) {
		Surface(
			shape = RoundedCornerShape(50),
			color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f)
		) {
			Text(
				letter,
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
			)
		}
	}
}
