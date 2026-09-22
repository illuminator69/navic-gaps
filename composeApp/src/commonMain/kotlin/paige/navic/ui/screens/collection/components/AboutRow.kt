package paige.navic.ui.screens.collection.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import paige.navic.domain.manager.LbMeta
import paige.navic.ui.util.teaserText

/** Three lines of prose under the album buttons; the rest lives in the sheet. */
private const val TEASER_LIMIT = 260

/**
 * A short "About" teaser on the album page, opening the full sheet.
 *
 * The album page has never shown a description. What it shows today is the ID3
 * `comment` tag, which is not one and is usually absent; `getAlbumInfo2.notes`
 * has been loaded on every album page since forever and read by nothing.
 *
 * So two sources, in order: lb-bot's Wikipedia text, which comes with an
 * attribution and a real article behind it, and `notes` as the fallback. The
 * fallback is put through [teaserText] rather than shown raw — Last.fm's notes
 * are HTML and would otherwise render their markup on screen, which is exactly
 * the bug the artist header had.
 *
 * Nothing renders when there is neither (§7).
 */
fun LazyListScope.collectionDetailScreenAboutRow(
	meta: LbMeta?,
	fallbackNotes: String?,
	onOpen: () -> Unit
) {
	val fromMeta = meta?.summary?.ifBlank { null }
		?: meta?.wikidataDescription?.ifBlank { null }
	val text = fromMeta ?: fallbackNotes?.ifBlank { null } ?: return
	// Only lb-bot's answer has a sheet worth opening: the `notes` fallback is one
	// paragraph with nowhere further to go.
	val expandable = fromMeta != null && meta != null
	item {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 8.dp)
				.then(if (expandable) Modifier.clickable(onClick = onOpen) else Modifier)
		) {
			Text(
				"About",
				style = MaterialTheme.typography.titleMediumEmphasized,
				fontWeight = FontWeight(600),
				color = MaterialTheme.colorScheme.primary,
				modifier = Modifier.padding(bottom = 4.dp)
			)
			Text(
				teaserText(text, TEASER_LIMIT),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 3,
				overflow = TextOverflow.Ellipsis
			)
		}
	}
}
