package paige.navic.ui.screens.external.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_download_all_files
import navic.composeapp.generated.resources.action_show_files
import navic.composeapp.generated.resources.info_source_artist_unverified
import navic.composeapp.generated.resources.info_source_complete
import navic.composeapp.generated.resources.info_source_matches_album
import navic.composeapp.generated.resources.info_source_more_files
import navic.composeapp.generated.resources.info_source_partial
import navic.composeapp.generated.resources.info_source_recommended
import navic.composeapp.generated.resources.info_source_wrong_album
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import paige.navic.domain.manager.LbGapSource
import paige.navic.domain.manager.sourceExtras
import paige.navic.ui.components.common.FillText
import paige.navic.ui.components.common.Pill
import paige.navic.ui.components.common.PillTone
import paige.navic.ui.components.common.SegmentedListItem
import paige.navic.ui.components.common.SegmentedListItemDefaults.segmentedShapes
import paige.navic.ui.components.common.StatePill
import paige.navic.ui.components.sheets.SourceFileList
import paige.navic.ui.components.sheets.SourceMetaLine

/**
 * The ranked candidates, as radio cards (P4; they were `MissingAlbumSheet`'s `AlbumSources`).
 *
 * Every card answers three questions in order of how badly getting them wrong hurts: is this the
 * right *album*, does it have all the *tracks*, and is it the right *quality*. The album verdict is
 * on every card because it is the only one whose failure is silent: a partial download is obvious
 * afterwards, the wrong record is not. A folder with no artist evidence says so in the error role:
 * lb-bot will not fetch it unattended, and a tap here is consent.
 *
 * Tapping a card selects it and opens its file listing. The selected card carries the second
 * action when its folder holds more than the album (B-084, [sourceExtras]); the first, "Request
 * this album", is the box's own button under the cards.
 */
@Composable
fun SourceCards(
	sources: List<LbGapSource>,
	selected: Int?,
	enabled: Boolean,
	onSelect: (Int) -> Unit,
	/** "Download all M files": every audio file in that card's folder. */
	onRequestAll: (LbGapSource) -> Unit
) {
	Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
		sources.forEachIndexed { index, source ->
			val isSelected = index == selected
			SegmentedListItem(
				selected = isSelected,
				onClick = { onSelect(index) },
				enabled = enabled,
				shapes = segmentedShapes(index = index, count = sources.size),
				leadingContent = {
					RadioButton(selected = isSelected, onClick = null, enabled = enabled)
				}
			) {
				SourceCardBody(source, isSelected, enabled, onRequestAll)
			}
		}
	}
}

@Composable
private fun SourceCardBody(
	source: LbGapSource,
	selected: Boolean,
	enabled: Boolean,
	onRequestAll: (LbGapSource) -> Unit
) {
	Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
		val recommended = stringResource(Res.string.info_source_recommended)
		val pills = listOfNotNull(
			Pill(FillText.Raw(recommended), PillTone.OK).takeIf { source.recommended },
			source.format.takeIf { it.isNotBlank() }?.let { Pill(FillText.Raw(it), PillTone.NEUTRAL) }
		)
		if (pills.isNotEmpty()) {
			FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
				pills.forEach { StatePill(it) }
			}
		}
		Text(
			source.folder.substringAfterLast('\\').substringAfterLast('/').ifBlank { source.peer },
			style = MaterialTheme.typography.bodyMedium,
			fontWeight = FontWeight.Medium,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis
		)
		Text(
			coverageText(source),
			style = MaterialTheme.typography.bodySmall,
			color = if (source.coverageFull) MaterialTheme.colorScheme.onSurface
			else MaterialTheme.colorScheme.onSurfaceVariant
		)
		// The verdict that would have caught the self-titled album.
		Text(
			if (source.albumMatchOk) stringResource(Res.string.info_source_matches_album)
			else stringResource(Res.string.info_source_wrong_album),
			style = MaterialTheme.typography.labelMedium,
			fontWeight = FontWeight.Medium,
			color = if (source.albumMatchOk) MaterialTheme.colorScheme.primary
			else MaterialTheme.colorScheme.error
		)
		// No artist evidence in the folder (lb-bot's artistVerified). lb-bot will not
		// fetch it unattended, and a tap here is consent — so the card must say so.
		if (!source.artistVerified) {
			Text(
				stringResource(Res.string.info_source_artist_unverified),
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.error
			)
		}
		SourceMetaLine(listOf(source.peer.takeIf { it.isNotBlank() }?.let { "@$it" }, source.bitrate, source.size))
		if (source.flags.isNotEmpty()) {
			Text(
				source.flags.joinToString(" • "),
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.error
			)
		}
		// The listing rides along with this route on purpose — it is a one-shot read, and
		// these rows are the evidence.
		if (selected) {
			SourceFileList(source.files, source.filesTruncated)
		} else if (source.files.isNotEmpty()) {
			Text(
				stringResource(Res.string.action_show_files, source.files.size),
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.primary
			)
		}
		val extras = sourceExtras(source)
		if (selected && extras != null) {
			// lb-bot's count, never one worked out from the listing above.
			Text(
				pluralStringResource(Res.plurals.info_source_more_files, extras.more, extras.more),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
			TextButton(
				onClick = { onRequestAll(source) },
				enabled = enabled,
				// The quieter of the card's two actions, in the muted colour.
				colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
			) {
				Text(pluralStringResource(Res.plurals.action_download_all_files, extras.all, extras.all))
			}
		}
	}
}

/**
 * "All 12 tracks" / "9 of 12 tracks".
 *
 * lb-bot's own `coverage` string is used when it has one — the album picker sends
 * a pre-formatted label — and the counts are the fallback. Both come from pairing
 * the folder against the canonical tracklist, never from counting files.
 */
@Composable
private fun coverageText(source: LbGapSource): String {
	val have = source.coverageDetail.haveTracks
	val total = source.coverageDetail.totalTracks
	return when {
		total > 0 && have >= total -> stringResource(Res.string.info_source_complete, total)
		total > 0 -> stringResource(Res.string.info_source_partial, have, total)
		else -> source.coverage
	}
}
