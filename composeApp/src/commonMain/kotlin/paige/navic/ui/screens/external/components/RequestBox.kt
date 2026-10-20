package paige.navic.ui.screens.external.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_add_to_wishlist
import navic.composeapp.generated.resources.action_allow_mp3_retry
import navic.composeapp.generated.resources.action_dismiss
import navic.composeapp.generated.resources.action_find_sources
import navic.composeapp.generated.resources.action_open_album
import navic.composeapp.generated.resources.action_retry
import navic.composeapp.generated.resources.action_search_again
import navic.composeapp.generated.resources.action_try_another_source
import navic.composeapp.generated.resources.info_already_downloading
import navic.composeapp.generated.resources.info_asked_all_files
import navic.composeapp.generated.resources.info_hub_needs_restart
import navic.composeapp.generated.resources.info_no_sources
import navic.composeapp.generated.resources.info_pending_sync
import navic.composeapp.generated.resources.info_pending_sync_detail
import navic.composeapp.generated.resources.info_searching_sources
import navic.composeapp.generated.resources.info_source_failover
import navic.composeapp.generated.resources.info_tracks_only
import navic.composeapp.generated.resources.label_edition
import navic.composeapp.generated.resources.label_quality_preference
import navic.composeapp.generated.resources.label_version
import navic.composeapp.generated.resources.lbbot_fill_cancelled
import navic.composeapp.generated.resources.lbbot_fill_verified
import navic.composeapp.generated.resources.request_cancel
import navic.composeapp.generated.resources.request_leave_any_time
import navic.composeapp.generated.resources.request_refused
import navic.composeapp.generated.resources.request_retry_from
import navic.composeapp.generated.resources.request_search_failed
import navic.composeapp.generated.resources.request_starting
import navic.composeapp.generated.resources.request_this_album
import navic.composeapp.generated.resources.title_sources
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import paige.navic.domain.manager.AllFilesNote
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbFillEntry
import paige.navic.domain.manager.LbGapSource
import paige.navic.domain.manager.LbRelease
import paige.navic.domain.manager.LbReleaseDetail
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Check
import paige.navic.icons.outlined.Download
import paige.navic.ui.components.common.FillButton
import paige.navic.ui.components.common.FillPresentation
import paige.navic.ui.components.common.FillProgressKind
import paige.navic.ui.components.common.FillText
import paige.navic.ui.components.common.Pill
import paige.navic.ui.components.common.PillTone
import paige.navic.ui.components.common.SegmentedListItem
import paige.navic.ui.components.common.SegmentedListItemDefaults
import paige.navic.ui.components.common.SegmentedListItemDefaults.segmentedShapes
import paige.navic.ui.components.common.StatePill
import paige.navic.ui.components.common.resolve
import paige.navic.ui.components.sheets.ChipRow
import paige.navic.ui.components.sheets.LbErrorLine
import paige.navic.ui.components.sheets.SheetCaption
import paige.navic.ui.components.sheets.SheetLabel
import paige.navic.ui.screens.external.RequestInput
import paige.navic.ui.screens.external.RequestStage
import paige.navic.ui.screens.external.RequestStep
import paige.navic.ui.screens.external.SourceSearch
import paige.navic.ui.screens.external.StepStatus
import paige.navic.ui.screens.external.Support
import paige.navic.ui.screens.external.requestActions
import paige.navic.ui.screens.external.requestStage
import paige.navic.ui.screens.external.viewmodels.RequestUi
import paige.navic.ui.util.rememberFoldMotion
import kotlin.time.Clock

/*
 * navi-connect P4: the album page's request box, which replaced `MissingAlbumSheet`. "The user never
 * leaves the row they acted on": finding sources, choosing, requesting, watching and recovering all
 * happen here, and the box follows its content's height with the fold motion.
 *
 * What the sheet did, and where each thing went (written before the sheet was deleted):
 *
 * | The sheet                                                     | The box                                       |
 * |---------------------------------------------------------------|-----------------------------------------------|
 * | two-step review (search, then pick)                           | `Idle`, then `Choosing`                       |
 * | coverage against the canonical tracklist, the album verdict,  | `SourceCards`                                 |
 * |   the artist-unverified line                                  |                                               |
 * | version and edition pickers                                   | the `Idle` and `Choosing` header, only when   |
 * |                                                               |   there is a choice                           |
 * | the sticky quality preference                                 | one quiet line under the cards                |
 * | "Download all M files" and its note                           | the selected card's second action; a caption  |
 * | the hub-needs-restart line                                    | `NeedsHubRestart`                             |
 * | `readOnly` for a just-landed release                          | `InLibrary`                                   |
 * | `FillProgress` and its Cancel                                 | `Active`                                      |
 * | `mp3WouldHelp` + Allow MP3                                    | `Failed` / `Attention` actions                |
 * | `alreadyRunning`                                              | a caption in `Active`                         |
 * | the canonical tracklist                                       | the page's own rows, following the edition    |
 *
 * The state is `ExternalAlbumViewModel`'s (`RequestUi`); which stage shows is `requestStage`'s.
 */

@Composable
fun RequestBox(
	support: Support,
	/** This device's ledger row for the release-group, if any. */
	fill: LbFillEntry?,
	indexed: LbRelease?,
	request: RequestUi,
	detail: LbReleaseDetail?,
	wishlistAvailable: Boolean,
	/** The library's album for this release, once Room has it ("Open album" when done). */
	ownedAlbumId: String?,
	onFindSources: () -> Unit,
	onSelectEdition: (variantIndex: Int, editionMbid: String) -> Unit,
	onSelectSource: (Int) -> Unit,
	onRequest: (source: LbGapSource, allFiles: Boolean) -> Unit,
	onQuality: (String) -> Unit,
	onAction: (FillButton) -> Unit,
	onOpenAlbum: (String) -> Unit,
	modifier: Modifier = Modifier,
	/** The box moved to another kind of stage (a different height): the page keeps it in view. */
	onStageKindChanged: () -> Unit = {}
) {
	// A one-second clock for the countdowns and "last checked Ns ago", as the Downloads rows have,
	// and only while there is a ledger row to count for. Read only here, so a tick recomposes the
	// box and nothing around it.
	val now = remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
	if (fill != null) {
		LaunchedEffect(fill.key) {
			while (true) {
				delay(1_000)
				now.longValue = Clock.System.now().toEpochMilliseconds()
			}
		}
	}
	val stage = requestStage(
		RequestInput(
			support = support,
			fill = fill,
			indexed = indexed,
			search = request.search,
			editionMbid = request.editionMbid,
			starting = request.starting,
			wishlistAvailable = wishlistAvailable,
			now = now.longValue
		)
	)
	if (stage == RequestStage.Absent) return
	// A request on its way keeps the cards it was made from on screen, disabled: swapping them
	// for one line and back moved the page out from under the finger that tapped (seen on the
	// AVD: the list jumped to the top on "Request this album").
	val search = request.search
	val shown = if (stage == RequestStage.Starting && search is SourceSearch.Found &&
		search.editionMbid == request.editionMbid && search.sources.isNotEmpty()
	) RequestStage.Choosing(search.sources, null) else stage
	val kind = shown::class
	var lastKind by remember { mutableStateOf(kind) }
	LaunchedEffect(kind) {
		if (kind != lastKind) {
			lastKind = kind
			onStageKindChanged()
		}
	}

	val motion = rememberFoldMotion(tall = true)
	Column(
		modifier = modifier
			.padding(horizontal = 16.dp, vertical = 8.dp)
			.fillMaxWidth()
			.clip(RoundedCornerShape(24.dp))
			.background(MaterialTheme.colorScheme.surfaceContainer)
			// The height follows the content, one stage to the next and within a stage (a card's
			// files opening, a subline arriving), on the fold's motion.
			.animateContentSize(motion.spatial<IntSize>())
			.padding(16.dp)
	) {
		// Keyed by the stage's KIND: a progress tick is the same stage and must not cross-fade.
		AnimatedContent(
			targetState = shown,
			contentKey = { it::class },
			transitionSpec = {
				fadeIn(motion.effects()) togetherWith fadeOut(motion.effects()) using
					// The size snaps here and the column above eases it, so it is eased once.
					SizeTransform(clip = false) { _, _ -> snap() }
			},
			label = "requestStage"
		) { shown ->
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				StageContent(
					stage = shown,
					fill = fill,
					request = request,
					detail = detail,
					ownedAlbumId = ownedAlbumId,
					onFindSources = onFindSources,
					onSelectEdition = onSelectEdition,
					onSelectSource = onSelectSource,
					onRequest = onRequest,
					onQuality = onQuality,
					onAction = onAction,
					onOpenAlbum = onOpenAlbum
				)
			}
		}
	}
}

@Composable
private fun StageContent(
	stage: RequestStage,
	fill: LbFillEntry?,
	request: RequestUi,
	detail: LbReleaseDetail?,
	ownedAlbumId: String?,
	onFindSources: () -> Unit,
	onSelectEdition: (Int, String) -> Unit,
	onSelectSource: (Int) -> Unit,
	onRequest: (LbGapSource, Boolean) -> Unit,
	onQuality: (String) -> Unit,
	onAction: (FillButton) -> Unit,
	onOpenAlbum: (String) -> Unit
) {
	when (stage) {
		RequestStage.Absent -> Unit

		// This hub does not proxy the source picker. A client ships independently of the hub and
		// the hub is a long-running process, so "my client is newer than the hub" is a permanent
		// condition; an unknown route is an ordinary 404 that surfaced as a button doing nothing.
		RequestStage.NeedsHubRestart -> Text(
			stringResource(Res.string.info_hub_needs_restart),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.error
		)

		is RequestStage.Idle -> {
			if (stage.cancelledBefore) {
				StatePill(Pill(FillText.Res(Res.string.lbbot_fill_cancelled), PillTone.MUTE))
			}
			EditionHeader(request, detail, onSelectEdition)
			Button(onClick = onFindSources, modifier = Modifier.fillMaxWidth()) {
				Icon(Icons.Outlined.Download, null, modifier = Modifier.size(20.dp).padding(end = 4.dp))
				Text(stringResource(Res.string.action_find_sources))
			}
		}

		RequestStage.Searching -> {
			EditionHeader(request, detail, onSelectEdition)
			BusyLine(stringResource(Res.string.info_searching_sources))
		}

		// Three different answers, three different words: a search that failed is not "nothing
		// found", and neither is "no releases".
		is RequestStage.SearchFailed -> {
			Text(
				stringResource(Res.string.request_search_failed),
				style = MaterialTheme.typography.titleSmall,
				color = MaterialTheme.colorScheme.error
			)
			LbErrorLine(stage.error)
			// Another pressing may be worth searching instead; the choice stays where it was.
			EditionHeader(request, detail, onSelectEdition)
			OutlinedButton(onClick = onFindSources, modifier = Modifier.fillMaxWidth()) {
				Text(stringResource(Res.string.action_search_again))
			}
		}

		RequestStage.NoSources -> {
			Text(
				stringResource(Res.string.info_no_sources),
				style = MaterialTheme.typography.titleSmall
			)
			EditionHeader(request, detail, onSelectEdition)
			OutlinedButton(onClick = onFindSources, modifier = Modifier.fillMaxWidth()) {
				Text(stringResource(Res.string.action_search_again))
			}
		}

		is RequestStage.Choosing -> {
			EditionHeader(request, detail, onSelectEdition)
			SheetLabel(stringResource(Res.string.title_sources))
			SheetCaption(stringResource(Res.string.info_source_failover))
			SourceCards(
				sources = stage.sources,
				selected = request.selected,
				enabled = !request.starting,
				onSelect = onSelectSource,
				onRequestAll = { onRequest(it, true) }
			)
			QualityLine(request.quality, onQuality)
			LbErrorLine(request.error)
			val chosen = request.selected?.let { stage.sources.getOrNull(it) }
			// Disabled until a card is chosen: an unverified folder is never one tap away, because
			// a tap is consent and a default is not.
			if (request.starting) {
				BusyLine(stringResource(Res.string.request_starting))
			} else {
				Button(
					onClick = { chosen?.let { onRequest(it, false) } },
					enabled = chosen != null,
					modifier = Modifier.fillMaxWidth()
				) {
					Text(stringResource(Res.string.request_this_album))
				}
			}
		}

		RequestStage.Starting -> BusyLine(stringResource(Res.string.request_starting))

		is RequestStage.Active -> {
			ActiveContent(stage.view, stage.steps)
			// Also how a request started from another client surfaces.
			if (request.alreadyRunning) Caption(stringResource(Res.string.info_already_downloading))
			AllFilesCaption(request.allFilesNote)
			if (FillButton.CANCEL in stage.view.buttons) {
				TextButton(onClick = { onAction(FillButton.CANCEL) }, enabled = !request.acting) {
					Text(stringResource(Res.string.request_cancel))
				}
			}
			ActionOutcome(request)
			Caption(stringResource(Res.string.request_leave_any_time))
		}

		is RequestStage.Failed -> RecoveryContent(stage.view, PillTone.BAD, fill, request, onAction)

		is RequestStage.Attention -> RecoveryContent(stage.view, PillTone.WARN, fill, request, onAction)

		is RequestStage.Done -> {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
				Text(
					stage.view.headline.resolve(),
					style = MaterialTheme.typography.titleMedium,
					modifier = Modifier.padding(start = 8.dp)
				)
			}
			stage.view.sublines.forEach { Caption(it.resolve()) }
			AllFilesCaption(request.allFilesNote)
			if (ownedAlbumId != null) {
				Button(onClick = { onOpenAlbum(ownedAlbumId) }, modifier = Modifier.fillMaxWidth()) {
					Text(stringResource(Res.string.action_open_album))
				}
			}
		}

		// lb-bot's index says the library holds it; Navidrome has not indexed the files yet. The
		// page moves to the album by itself once Room has it.
		RequestStage.InLibrary -> {
			Text(stringResource(Res.string.lbbot_fill_verified), style = MaterialTheme.typography.titleMedium)
			StatePill(Pill(FillText.Res(Res.string.info_pending_sync), PillTone.RUN))
			Caption(stringResource(Res.string.info_pending_sync_detail))
		}
	}
}

/** The version and edition choosers, only when there is a choice to make. */
@Composable
private fun EditionHeader(
	request: RequestUi,
	detail: LbReleaseDetail?,
	onSelectEdition: (Int, String) -> Unit
) {
	val variants = detail?.releases.orEmpty()
	// A variant changes the tracklist (Original / Remaster / Deluxe); an edition is the same
	// tracklist on different media.
	if (variants.size > 1) {
		SheetLabel(stringResource(Res.string.label_version))
		ChipRow(
			labels = variants.map { variant ->
				listOfNotNull(
					variant.disambiguation.takeIf { it.isNotBlank() }
						?: variant.title.takeIf { it.isNotBlank() },
					variant.year.takeIf { it.isNotBlank() }
				).joinToString(" • ").ifBlank { "—" }
			},
			selectedIndex = request.variantIndex,
			onSelect = { index ->
				onSelectEdition(
					index,
					variants[index].editions.firstOrNull()?.releaseMbid ?: variants[index].releaseMbid
				)
			}
		)
	}
	val editions = variants.getOrNull(request.variantIndex)?.editions.orEmpty()
	if (editions.size > 1) {
		SheetLabel(stringResource(Res.string.label_edition))
		ChipRow(
			labels = editions.map { edition ->
				listOfNotNull(
					edition.label.takeIf { it.isNotBlank() },
					edition.format.takeIf { it.isNotBlank() },
					edition.year.takeIf { it.isNotBlank() }
				).joinToString(" • ").ifBlank { "—" }
			},
			selectedIndex = editions.indexOfFirst { it.releaseMbid == request.editionMbid },
			onSelect = { index -> onSelectEdition(request.variantIndex, editions[index].releaseMbid) }
		)
	}
}

/**
 * "Prefer: <quality>". A *ranking* term upstream, not a filter — hence "prefer", never "only"; the
 * card's own format is what answers "what am I about to get".
 */
@Composable
private fun QualityLine(quality: String, onQuality: (String) -> Unit) {
	var open by remember { mutableStateOf(false) }
	Row(
		Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.SpaceBetween,
		verticalAlignment = Alignment.CenterVertically
	) {
		Text(
			stringResource(Res.string.label_quality_preference),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant
		)
		Box {
			TextButton(onClick = { open = true }) {
				// A stored value lb-bot has since dropped from its enum falls back to the default.
				Text(
					LbBotManager.QUALITY_OPTIONS.firstOrNull { it.first == quality }?.second
						?: LbBotManager.QUALITY_OPTIONS.first().second,
					style = MaterialTheme.typography.labelLarge
				)
			}
			DropdownMenu(open, onDismissRequest = { open = false }) {
				LbBotManager.QUALITY_OPTIONS.forEach { (value, label) ->
					DropdownMenuItem(
						text = { Text(label) },
						onClick = {
							onQuality(value)
							open = false
						}
					)
				}
			}
		}
	}
}

@Composable
private fun ActiveContent(view: FillPresentation, steps: List<RequestStep>) {
	Text(view.headline.resolve(), style = MaterialTheme.typography.titleMedium)
	view.sublines.forEach { Caption(it.resolve()) }
	val motion = rememberFoldMotion()
	when (view.progress) {
		FillProgressKind.DETERMINATE -> {
			val progress by animateFloatAsState(view.percent / 100f, motion.spatial(), label = "requestProgress")
			LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
		}
		FillProgressKind.INDETERMINATE -> LinearProgressIndicator(Modifier.fillMaxWidth())
		FillProgressKind.NONE -> Unit
	}
	if (steps.isNotEmpty()) {
		Spacer(Modifier.height(4.dp))
		Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
			steps.forEach { StepRow(it) }
		}
	}
}

@Composable
private fun StepRow(step: RequestStep) {
	Row(verticalAlignment = Alignment.CenterVertically) {
		Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
			when (step.status) {
				StepStatus.DONE -> Box(
					Modifier.size(18.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
					contentAlignment = Alignment.Center
				) {
					Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(12.dp))
				}
				StepStatus.NOW -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
				StepStatus.PENDING -> Box(
					Modifier.size(14.dp).border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
				)
			}
		}
		Text(
			step.label.resolve(),
			style = MaterialTheme.typography.bodyMedium,
			fontWeight = if (step.status == StepStatus.NOW) FontWeight.Medium else FontWeight.Normal,
			color = if (step.status == StepStatus.PENDING) MaterialTheme.colorScheme.onSurfaceVariant
			else MaterialTheme.colorScheme.onSurface,
			modifier = Modifier.padding(start = 10.dp).weight(1f)
		)
		step.detail?.let {
			Text(
				it.resolve(),
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
		}
	}
}

/**
 * A settled request that failed or waits on the user: the same box, saying why (the headline, then
 * lb-bot's own sentence verbatim among the sublines) and one row per action the vocabulary offers.
 */
@Composable
private fun RecoveryContent(
	view: FillPresentation,
	tone: PillTone,
	fill: LbFillEntry?,
	request: RequestUi,
	onAction: (FillButton) -> Unit
) {
	StatePill(Pill(view.headline, tone))
	view.sublines.forEach { Caption(it.resolve()) }
	view.explain?.let { Caption(it.resolve()) }
	val actions = requestActions(view)
	if (actions.isNotEmpty()) {
		Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
			actions.forEachIndexed { index, button ->
				val peer = fill?.retryPeer.orEmpty()
				SegmentedListItem(
					onClick = { onAction(button) },
					enabled = !request.acting,
					shapes = segmentedShapes(index = index, count = actions.size),
					// A step lighter than the box, so the rows read as rows inside it.
					colors = SegmentedListItemDefaults.segmentedColors(
						containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
						disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
					),
					supportingContent = if (button == FillButton.RETRY && peer.isNotBlank()) {
						{ Text(stringResource(Res.string.request_retry_from, peer)) }
					} else null
				) {
					Text(stringResource(button.boxLabel))
				}
			}
		}
	}
	ActionOutcome(request)
}

/** What the last action or request answered, when it was not a plain yes. */
@Composable
private fun ActionOutcome(request: RequestUi) {
	LbErrorLine(request.error)
	if (request.refused) {
		Text(
			stringResource(Res.string.request_refused),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.error
		)
	}
}

/** What lb-bot understood of "Download all" (B-084), from its echo. */
@Composable
private fun AllFilesCaption(note: AllFilesNote) {
	when (note) {
		is AllFilesNote.Asked -> Caption(pluralStringResource(Res.plurals.info_asked_all_files, note.files, note.peer, note.files))
		AllFilesNote.TracksOnly -> Caption(stringResource(Res.string.info_tracks_only))
		AllFilesNote.None -> Unit
	}
}

@Composable
private fun BusyLine(text: String) {
	Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
		CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
		Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
	}
}

@Composable
private fun Caption(text: String) {
	Text(
		text,
		style = MaterialTheme.typography.bodySmall,
		color = MaterialTheme.colorScheme.onSurfaceVariant
	)
}

private val FillButton.boxLabel
	get() = when (this) {
		FillButton.RETRY -> Res.string.action_retry
		FillButton.TRY_ANOTHER -> Res.string.action_try_another_source
		FillButton.ALLOW_MP3 -> Res.string.action_allow_mp3_retry
		FillButton.WISHLIST -> Res.string.action_add_to_wishlist
		FillButton.CANCEL -> Res.string.request_cancel
		FillButton.DISMISS -> Res.string.action_dismiss
		FillButton.OPEN_ALBUM -> Res.string.action_open_album
	}
