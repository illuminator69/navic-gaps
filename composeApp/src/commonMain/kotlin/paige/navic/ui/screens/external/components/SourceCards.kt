package paige.navic.ui.screens.external.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_download_all_files
import navic.composeapp.generated.resources.action_show_files
import navic.composeapp.generated.resources.info_source_artist_unverified
import navic.composeapp.generated.resources.info_source_complete
import navic.composeapp.generated.resources.info_source_files_unexpanded
import navic.composeapp.generated.resources.info_source_matches_album
import navic.composeapp.generated.resources.info_source_more_files
import navic.composeapp.generated.resources.info_source_partial
import navic.composeapp.generated.resources.info_source_recommended
import navic.composeapp.generated.resources.info_source_wrong_album
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import paige.navic.domain.manager.LbGapSource
import paige.navic.domain.manager.LbSourceFiles
import paige.navic.domain.manager.sourceExtras
import paige.navic.ui.components.common.FillText
import paige.navic.ui.components.common.Pill
import paige.navic.ui.components.common.PillTone
import paige.navic.ui.components.common.SegmentedListItem
import paige.navic.ui.components.common.SegmentedListItemDefaults
import paige.navic.ui.components.common.SegmentedListItemDefaults.segmentedShapes
import paige.navic.ui.components.common.StatePill
import paige.navic.ui.components.sheets.SourceFileList
import paige.navic.ui.components.sheets.SourceMetaLine
import paige.navic.ui.util.Fold
import paige.navic.ui.util.rememberFoldMotion

/**
 * The ranked candidates, as radio cards (P4; they were `MissingAlbumSheet`'s `AlbumSources`).
 *
 * Every card answers three questions in order of how badly getting them wrong hurts: is this the
 * right *album*, does it have all the *tracks*, and is it the right *quality*. The album verdict is
 * on every card because it is the only one whose failure is silent: a partial download is obvious
 * afterwards, the wrong record is not. A folder with no artist evidence says so in the error role:
 * lb-bot will not fetch it unattended, and a tap here is consent.
 *
 * Tapping a card selects it and opens its file listing; the highlight eases from the old card to the
 * new one (the owner's note on the first build: it switched instantly). The selected card carries the second
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
	onRequestAll: (LbGapSource) -> Unit,
	/** The folder listing fetched for a card, where the route does not carry it (a gap's). */
	listingFor: (LbGapSource) -> LbSourceFiles? = { null },
	/** Drawn right under the chosen card: the gaps box puts its Request button there. */
	belowSelected: (@Composable () -> Unit)? = null
) {
	val motion = rememberFoldMotion()
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		sources.forEachIndexed { index, source ->
			val isSelected = index == selected
			// One eased fraction for everything the selection changes (the card's tone, its outline),
			// so choosing another card moves the highlight across rather than switching it.
			val chosen by animateFloatAsState(
				if (isSelected) 1f else 0f,
				motion.effects(),
				label = "sourceCardSelected"
			)
			val high = MaterialTheme.colorScheme.surfaceContainerHigh
			val container = lerp(high, MaterialTheme.colorScheme.surfaceContainerHighest, chosen)
			val outline = MaterialTheme.colorScheme.primary.copy(alpha = chosen)
			// Each card stands alone, fully rounded, so the outline follows the card's own edge.
			val shapes = segmentedShapes(index = 0, count = 1)
			SegmentedListItem(
				selected = isSelected,
				onClick = { onSelect(index) },
				enabled = enabled,
				shapes = shapes,
				// Every card stays on a surface role, where the lines' own colours (the verdict in
				// primary, a warning in error) were made to sit: a selection colour under them failed
				// contrast on a red cover (seen on the AVD). The chosen card is a step lighter and
				// outlined in primary, so it is told by more than its radio.
				colors = SegmentedListItemDefaults.segmentedColors(
					containerColor = container,
					disabledContainerColor = container,
					disabledContentColor = MaterialTheme.colorScheme.onSurface,
					selectedContainerColor = container
				),
				modifier = Modifier.border(2.dp, outline, shapes.shape),
				leadingContent = {
					RadioButton(selected = isSelected, onClick = null, enabled = enabled)
				}
			) {
				SourceCardBody(source, listingFor(source), isSelected, enabled, onRequestAll)
			}
			if (belowSelected != null) {
				Fold(visible = isSelected) { belowSelected() }
			}
		}
	}
}

@Composable
private fun SourceCardBody(
	source: LbGapSource,
	listing: LbSourceFiles?,
	selected: Boolean,
	enabled: Boolean,
	onRequestAll: (LbGapSource) -> Unit
) {
	// The card grows by itself when it is chosen (its file listing opens), so the cards under it
	// move down with the height rather than jumping.
	val motion = rememberFoldMotion(tall = true)
	Column(
		Modifier.animateContentSize(motion.spatial<IntSize>()).padding(vertical = 4.dp),
		verticalArrangement = Arrangement.spacedBy(2.dp)
	) {
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
		// A gap's listing is fetched when its card is chosen; the album picker's rides along.
		val files = listing?.files ?: source.files
		if (selected && files.isNotEmpty()) {
			if (listing != null && !listing.expanded) {
				Text(
					stringResource(Res.string.info_source_files_unexpanded),
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.error
				)
			}
			SourceFileList(files, listing?.filesTruncated ?: source.filesTruncated)
		} else if (!selected && (files.isNotEmpty() || source.fileCount > 0)) {
			Text(
				stringResource(Res.string.action_show_files, files.size.takeIf { it > 0 } ?: source.fileCount),
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
