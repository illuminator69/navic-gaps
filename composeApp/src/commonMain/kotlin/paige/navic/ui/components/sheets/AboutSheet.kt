package paige.navic.ui.components.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import paige.navic.domain.manager.LbMeta

/**
 * The full "About" text for an artist or an album.
 *
 * This is the section Navic has never had. The artist page showed 200 raw
 * characters of Navidrome's Last.fm bio in the photo header and nothing else;
 * the album page showed no description at all, even though `getAlbumInfo2.notes`
 * was already being loaded and read by nothing.
 *
 * Everything here is plain text by construction — lb-bot asks Wikipedia for
 * `explaintext` extracts — so there is no markup to strip and nothing that can
 * render as a literal `<a href=...>` the way the Last.fm bio does.
 *
 * **The attribution is not optional.** Wikipedia text is CC BY-SA; showing it
 * without crediting the article is a licence violation, and its link is also
 * simply a better "read more" than the Last.fm one it replaces.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(
	title: String,
	meta: LbMeta,
	onDismissRequest: () -> Unit
) {
	ModalBottomSheet(onDismissRequest = onDismissRequest, sheetTitle = title) {
		Column(
			modifier = Modifier
				.verticalScroll(rememberScrollState())
				.padding(horizontal = 20.dp)
				.padding(bottom = 32.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp)
		) {
			Text(
				title,
				style = MaterialTheme.typography.titleLarge,
				fontWeight = FontWeight.Bold
			)
			// Wikidata's one-liner is often present when there is no article at
			// all, which is exactly when it earns its place. Suppressed when
			// there is prose, where it only restates the first sentence.
			if (meta.paragraphs.isEmpty() && meta.summary.isBlank() &&
				meta.wikidataDescription.isNotBlank()
			) {
				Text(
					meta.wikidataDescription,
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}
			val paragraphs = meta.paragraphs.ifEmpty { listOfNotNull(meta.summary.ifBlank { null }) }
			paragraphs.forEach { paragraph ->
				Text(paragraph, style = MaterialTheme.typography.bodyMedium)
			}
			meta.source?.takeIf { it.url.isNotBlank() && paragraphs.isNotEmpty() }?.let { source ->
				Text(
					text = buildAnnotatedString {
						append("From ${source.name} (${source.license}) — ")
						withLink(LinkAnnotation.Url(source.url)) { append("read more") }
					},
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}
			// Band members / side projects, and the credits on an album. A flat
			// list rather than a table: MusicBrainz's role vocabulary is long
			// and uneven, and grouping by role yields a dozen one-line sections.
			MetaRelationBlock("Members", meta.relations.members.map { it.name })
			MetaRelationBlock("Related", meta.relations.related.map { it.name })
			if (meta.credits.isNotEmpty()) {
				SheetLabel("Credits")
				Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
					meta.credits.forEach { credit ->
						Text(
							"${credit.roles.joinToString(", ")} — ${credit.name}",
							style = MaterialTheme.typography.bodySmall
						)
					}
				}
			}
			if (meta.links.isNotEmpty()) {
				SheetLabel("Links")
				FlowRow(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.spacedBy(16.dp)
				) {
					meta.links.forEach { link ->
						Text(
							text = buildAnnotatedString {
								withLink(LinkAnnotation.Url(link.url)) { append(link.label) }
							},
							style = MaterialTheme.typography.bodySmall
						)
					}
				}
			}
		}
	}
}

@Composable
private fun MetaRelationBlock(label: String, names: List<String>) {
	if (names.isEmpty()) return
	SheetLabel(label)
	Text(names.joinToString(", "), style = MaterialTheme.typography.bodySmall)
}
