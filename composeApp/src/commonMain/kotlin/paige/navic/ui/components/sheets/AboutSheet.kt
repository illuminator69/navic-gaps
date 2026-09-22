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
import paige.navic.domain.manager.LbMetaRelation
import paige.navic.ui.util.rememberCoverAmbient
import paige.navic.ui.util.stripHtml

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
	meta: LbMeta?,
	onDismissRequest: () -> Unit,
	/**
	 * The host page's own description — Navidrome's artist biography, or an
	 * album's `getAlbumInfo2.notes`. It is the sheet's body whenever lb-bot has
	 * no prose, which is what makes the teaser and the sheet agree: tapping three
	 * truncated lines has to open the rest of *those* lines, not a different text
	 * about the same artist. HTML, so it goes through [stripHtml].
	 */
	fallbackBio: String? = null,
	/**
	 * Themes the sheet from the page's artwork. Every other sheet in the app
	 * passes an ambient; this one did not, so it opened in the flat M3 container
	 * colour on top of a page fully washed in the cover's palette.
	 */
	coverArtId: String? = null
) {
	ModalBottomSheet(
		onDismissRequest = onDismissRequest,
		sheetTitle = title,
		ambient = rememberCoverAmbient(coverArtId)
	) {
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
			// all, which is exactly when it earns its place. It lives HERE rather
			// than in the header: in the header it replaced a 220-character
			// teaser with one sentence the moment lb-bot answered, collapsing the
			// block and jerking every button below it upwards.
			if (meta != null && meta.paragraphs.isEmpty() && meta.summary.isBlank() &&
				meta.wikidataDescription.isNotBlank()
			) {
				Text(
					meta.wikidataDescription,
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}
			val metaParagraphs = meta?.paragraphs?.ifEmpty {
				listOfNotNull(meta.summary.ifBlank { null })
			}.orEmpty()
			// lb-bot's prose when there is any, the page's own text otherwise. The
			// fallback is what the teaser showed, so the sheet continues it rather
			// than replacing it with something else.
			val paragraphs = metaParagraphs.ifEmpty {
				listOfNotNull(fallbackBio?.let { stripHtml(it) }?.ifBlank { null })
			}
			paragraphs.forEach { paragraph ->
				Text(paragraph, style = MaterialTheme.typography.bodyMedium)
			}
			// Attribution belongs to lb-bot's text only — never to Navidrome's.
			meta?.source?.takeIf { it.url.isNotBlank() && metaParagraphs.isNotEmpty() }?.let { source ->
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
			MetaRelationBlock("Members", meta?.relations?.members.orEmpty())
			MetaRelationBlock("Related", meta?.relations?.related.orEmpty())
			if (meta != null && meta.credits.isNotEmpty()) {
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
			if (meta != null && meta.links.isNotEmpty()) {
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
private fun MetaRelationBlock(label: String, rows: List<LbMetaRelation>) {
	if (rows.isEmpty()) return
	SheetLabel(label)
	Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
		rows.forEach { row ->
			Text(
				buildString {
					append(row.name)
					// `attributes` is the instruments and roles MusicBrainz states,
					// already merged per person by lb-bot across its
					// one-row-per-instrument-per-stint shape. The client used to
					// drop the field entirely, which left this a row of bare names.
					val detail = relationDetail(row)
					if (detail.isNotBlank()) append(" — ").append(detail)
				},
				style = MaterialTheme.typography.bodySmall
			)
		}
	}
}

/** "guitar, lead vocals (1985–1991)"; an open stint gets no end year. */
private fun relationDetail(row: LbMetaRelation): String {
	val roles = row.attributes.joinToString(", ")
	val years = when {
		row.begin.isNotBlank() -> row.begin + "\u2013" + if (row.ended) row.end else ""
		row.ended && row.end.isNotBlank() -> "until " + row.end
		else -> ""
	}
	return when {
		roles.isNotBlank() && years.isNotBlank() -> "$roles ($years)"
		roles.isNotBlank() -> roles
		years.isNotBlank() -> "($years)"
		else -> ""
	}
}
