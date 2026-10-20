package paige.navic.ui.screens.collection.components

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousCapsule
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_auto_pick
import navic.composeapp.generated.resources.action_close
import navic.composeapp.generated.resources.action_dismiss
import navic.composeapp.generated.resources.action_find_sources
import navic.composeapp.generated.resources.action_open_in_lbbot
import navic.composeapp.generated.resources.action_recheck_album
import navic.composeapp.generated.resources.action_search_again
import navic.composeapp.generated.resources.action_wrong_edition
import navic.composeapp.generated.resources.gap_added
import navic.composeapp.generated.resources.gap_allow_mp3_request
import navic.composeapp.generated.resources.gap_back
import navic.composeapp.generated.resources.gap_banner_action
import navic.composeapp.generated.resources.gap_for_missing_only
import navic.composeapp.generated.resources.gap_from
import navic.composeapp.generated.resources.gap_getting
import navic.composeapp.generated.resources.gap_in_library
import navic.composeapp.generated.resources.gap_measured_against
import navic.composeapp.generated.resources.gap_request_tracks
import navic.composeapp.generated.resources.gap_searching
import navic.composeapp.generated.resources.gap_sources_best_first
import navic.composeapp.generated.resources.gap_tap_to_fold
import navic.composeapp.generated.resources.gap_tap_to_see
import navic.composeapp.generated.resources.gap_tracks_missing_banner
import navic.composeapp.generated.resources.info_gap_failed
import navic.composeapp.generated.resources.info_no_sources
import navic.composeapp.generated.resources.lbbot_fill_failed
import navic.composeapp.generated.resources.lbbot_fill_needs_match
import navic.composeapp.generated.resources.lbbot_fill_verified
import navic.composeapp.generated.resources.request_cancel
import navic.composeapp.generated.resources.request_leave_any_time
import navic.composeapp.generated.resources.request_step_count
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import paige.navic.domain.manager.LbGap
import paige.navic.domain.manager.LbGapSource
import paige.navic.domain.manager.LbGapTrack
import paige.navic.icons.Icons
import paige.navic.icons.outlined.ArrowBack
import paige.navic.icons.outlined.Check
import paige.navic.icons.outlined.Close
import paige.navic.icons.outlined.Info
import paige.navic.icons.outlined.KeyboardArrowDown
import paige.navic.icons.outlined.Search
import paige.navic.ui.components.common.FillText
import paige.navic.ui.components.common.Pill
import paige.navic.ui.components.common.PillTone
import paige.navic.ui.components.common.SegmentedListItem
import paige.navic.ui.components.common.SegmentedListItemDefaults
import paige.navic.ui.components.common.SegmentedListItemDefaults.segmentedShapes
import paige.navic.ui.components.common.StatePill
import paige.navic.ui.components.common.gapPositionLabel
import paige.navic.ui.components.common.gapShowsDisc
import paige.navic.ui.components.common.gapTrackStateLabel
import paige.navic.ui.components.common.gapTrackTone
import paige.navic.ui.components.sheets.LbErrorLine
import paige.navic.ui.screens.collection.GapStage
import paige.navic.ui.screens.collection.viewmodels.GapBoxUi
import paige.navic.ui.screens.external.components.BusyLine
import paige.navic.ui.screens.external.components.RequestSteps
import paige.navic.ui.screens.external.components.SourceCards
import paige.navic.ui.util.Fold
import paige.navic.ui.util.rememberFoldMotion

/*
 * navi-connect P4, round 2: the gaps box on the album page, which retired `GapFillSheet` (the owner's
 * storyboard D). What the sheet did and where each thing went:
 *
 * | The sheet                                         | The box                                          |
 * |---------------------------------------------------|--------------------------------------------------|
 * | the album, "9 of 12 in your library" and a bar    | the page itself; the box's line counts what is   |
 * |                                                   |   missing                                        |
 * | the tracklist with a pill on each absent track    | `Tracks`: only the missing ones, each with its   |
 * |                                                   |   pill (the page lists the rest)                 |
 * | "Measured against…", Wrong edition?               | `Tracks`' note, with Wrong edition? in it        |
 * | Find sources / Search again                       | Fill gaps (the line's button); Search again      |
 * |                                                   |   under the sources                              |
 * | the source rows, Show files, Download from here   | `Choosing`: the album box's radio cards, the     |
 * |                                                   |   listing on a tap, Request under the chosen one |
 * | Pick the best source for me                       | a quiet button under the sources                 |
 * | the status line and the per-track progress        | `Active`: the request box's steps and a bar      |
 * | Cancel                                            | `Active`'s Cancel request                        |
 * | Allow MP3                                         | "Allow MP3 for this request" where lb-bot says   |
 * |                                                   |   it would help                                  |
 * | Re-check, Open in lb-bot                          | `Tracks`' quiet buttons; `Attention`'s actions   |
 * | the hub-needs-restart line                        | no box (as the banner before it)                 |
 *
 * Which stage shows is `gapStage`'s; the box follows its content's height on the fold motion and
 * cross-fades between stage kinds, so nothing swaps at once.
 */

@Composable
fun GapBox(
	stage: GapStage,
	ui: GapBoxUi,
	gap: LbGap?,
	/** Wrong edition? where the hub can fix an album's match; null hides it. */
	onWrongEdition: (() -> Unit)?,
	/** lb-bot's own page for this album; null when the hub does not advertise lb-bot's address. */
	onOpenInLbBot: (() -> Unit)?,
	onToggle: () -> Unit,
	onFillGaps: () -> Unit,
	onBack: () -> Unit,
	onClose: () -> Unit,
	onSelect: (Int) -> Unit,
	onRequest: (LbGapSource) -> Unit,
	onAuto: () -> Unit,
	onSearchAgain: () -> Unit,
	onAllowMp3: () -> Unit,
	onCancel: () -> Unit,
	onRecheck: () -> Unit,
	onDismiss: () -> Unit,
	modifier: Modifier = Modifier
) {
	val motion = rememberFoldMotion(tall = true)
	// The missing songs stay drawn while the list folds away.
	var lastTracks by remember { mutableStateOf<GapStage.Tracks?>(null) }
	if (stage is GapStage.Tracks) lastTracks = stage
	val shape = RoundedCornerShape(20.dp)
	Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
		Column(
			Modifier
				.fillMaxWidth()
				.clip(shape)
				.background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f))
				.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), shape)
				// The height follows the content, one stage to the next and within one (the list
				// unfolding, a card's files opening), on the fold's motion.
				.animateContentSize(motion.spatial<IntSize>())
		) {
			AnimatedContent(
				targetState = stage,
				// The line and its list are one kind (the header stays while the list unfolds); the
				// sources' three states share their header too. A progress tick never cross-fades.
				contentKey = { it.kind() },
				transitionSpec = {
					fadeIn(motion.effects()) togetherWith fadeOut(motion.effects()) using
						SizeTransform(clip = false) { _, _ -> snap() }
				},
				label = "gapStage"
			) { shown ->
				Column {
					when (shown) {
						GapStage.Absent -> Unit
						is GapStage.Folded, is GapStage.Tracks -> FoldContent(
							missing = (shown as? GapStage.Folded)?.missing ?: (shown as GapStage.Tracks).missing.size,
							open = shown is GapStage.Tracks,
							tracks = lastTracks,
							album = gap?.album.orEmpty(),
							onToggle = onToggle,
							onFillGaps = onFillGaps,
							onWrongEdition = onWrongEdition,
							onRecheck = onRecheck,
							onOpenInLbBot = onOpenInLbBot,
							working = ui.working
						)
						GapStage.Loading -> Box(Modifier.padding(16.dp)) { BusyLine(stringResource(Res.string.gap_searching)) }
						GapStage.Searching, is GapStage.Choosing, is GapStage.NoSources -> SourcesContent(
							stage = shown,
							ui = ui,
							gap = gap,
							onBack = onBack,
							onClose = onClose,
							onSelect = onSelect,
							onRequest = onRequest,
							onAuto = onAuto,
							onSearchAgain = onSearchAgain,
							onAllowMp3 = onAllowMp3
						)
						is GapStage.Active -> ActiveContent(shown, ui, onCancel)
						is GapStage.Done -> DoneContent(shown)
						is GapStage.Failed -> RecoveryContent(
							pill = Pill(FillText.Res(Res.string.lbbot_fill_failed), PillTone.BAD),
							reason = shown.reason,
							actions = listOfNotNull(
								Res.string.action_search_again to onSearchAgain,
								(Res.string.gap_allow_mp3_request to onAllowMp3).takeIf { shown.mp3WouldHelp },
								Res.string.action_dismiss to onDismiss
							),
							working = ui.working
						)
						is GapStage.Attention -> RecoveryContent(
							pill = Pill(FillText.Res(Res.string.lbbot_fill_needs_match), PillTone.WARN),
							reason = shown.reason,
							actions = listOfNotNull(
								onOpenInLbBot?.let { Res.string.action_open_in_lbbot to it },
								Res.string.action_recheck_album to onRecheck,
								Res.string.action_dismiss to onDismiss
							),
							working = ui.working
						)
					}
					if (ui.error != null) {
						Box(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) { LbErrorLine(ui.error) }
					}
				}
			}
		}
		// Under the box, as the request box says it: the fill goes on without the page.
		Fold(visible = stage is GapStage.Active) {
			Text(
				stringResource(Res.string.request_leave_any_time),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(start = 4.dp, top = 8.dp)
			)
		}
	}
}

private fun GapStage.kind(): String = when (this) {
	is GapStage.Folded, is GapStage.Tracks -> "fold"
	GapStage.Searching, is GapStage.Choosing, is GapStage.NoSources -> "sources"
	else -> this::class.simpleName.orEmpty()
}

/** "3 tracks missing / Tap to see which", Fill gaps, the chevron; unfolded, the missing songs. */
@Composable
private fun FoldContent(
	missing: Int,
	open: Boolean,
	tracks: GapStage.Tracks?,
	album: String,
	onToggle: () -> Unit,
	onFillGaps: () -> Unit,
	onWrongEdition: (() -> Unit)?,
	onRecheck: () -> Unit,
	onOpenInLbBot: (() -> Unit)?,
	working: Boolean
) {
	val motion = rememberFoldMotion()
	val turn by animateFloatAsState(if (open) 180f else 0f, motion.spatial(), label = "gapChevron")
	Row(
		Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		Icon(Icons.Outlined.Search, null, modifier = Modifier.size(20.dp))
		Column(Modifier.weight(1f).padding(start = 12.dp)) {
			Text(
				pluralStringResource(Res.plurals.gap_tracks_missing_banner, missing, missing),
				style = MaterialTheme.typography.titleSmall,
				fontWeight = FontWeight.SemiBold
			)
			Text(
				stringResource(if (open) Res.string.gap_tap_to_fold else Res.string.gap_tap_to_see),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
		}
		// Fill gaps stays beside the title, open or folded: always one tap.
		Button(
			onClick = onFillGaps,
			shape = ContinuousCapsule,
			contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
			modifier = Modifier.height(36.dp)
		) {
			Text(stringResource(Res.string.gap_banner_action), style = MaterialTheme.typography.labelLarge)
		}
		Icon(
			Icons.Outlined.KeyboardArrowDown,
			contentDescription = null,
			modifier = Modifier.padding(start = 6.dp).size(24.dp).rotate(turn)
		)
	}
	Fold(visible = open && tracks != null) {
		if (tracks != null) {
			Column {
				HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
				MissingTracks(tracks.missing)
				MeasuredNote(album, tracks.total, onWrongEdition)
				FlowRow(Modifier.padding(horizontal = 8.dp).padding(bottom = 6.dp)) {
					TextButton(onClick = onRecheck, enabled = !working) { Text(stringResource(Res.string.action_recheck_album)) }
					if (onOpenInLbBot != null) {
						TextButton(onClick = onOpenInLbBot) { Text(stringResource(Res.string.action_open_in_lbbot)) }
					}
				}
			}
		}
	}
}

@Composable
private fun MissingTracks(tracks: List<LbGapTrack>) {
	// B-071: a multi-disc gap numbers its rows "2-1", because the positions alone repeat.
	val showDisc = gapShowsDisc(tracks)
	val numberWidth = if (showDisc) 40.dp else 28.dp
	Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
		tracks.forEach { track ->
			Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
				Text(
					gapPositionLabel(track, showDisc),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.width(numberWidth)
				)
				Text(
					track.title,
					style = MaterialTheme.typography.bodyMedium,
					fontWeight = FontWeight.Medium,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.weight(1f)
				)
				StatePill(
					Pill(
						gapTrackStateLabel(track.state)?.let { FillText.Res(it) }
							?: FillText.Raw(track.state.replace('_', ' ')),
						gapTrackTone(track.state)
					),
					modifier = Modifier.padding(start = 8.dp)
				)
			}
		}
	}
}

/**
 * Which release the count comes from: lb-bot measures the gap against the album's own MusicBrainz
 * tag, so a library tagged as a 17-track deluxe reports 17 slots when every pressing on offer has 12.
 * Wrong edition? is the remedy when that release is the wrong one; no download fixes it.
 */
@Composable
private fun MeasuredNote(album: String, total: Int, onWrongEdition: (() -> Unit)?) {
	if (total <= 0) return
	Row(Modifier.padding(horizontal = 16.dp).padding(top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
		Icon(Icons.Outlined.Info, null, modifier = Modifier.padding(top = 1.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
		val note = stringResource(Res.string.gap_measured_against, album, total)
		val wrong = stringResource(Res.string.action_wrong_edition)
		Text(
			buildAnnotatedString {
				append(note)
				if (onWrongEdition != null) {
					append(" ")
					withStyle(SpanStyle(textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium)) { append(wrong) }
				}
			},
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier
				.padding(start = 8.dp)
				.then(if (onWrongEdition != null) Modifier.clickable(onClickLabel = wrong, onClick = onWrongEdition) else Modifier)
		)
	}
}

/** Find sources for the missing tracks: searching, the ranked cards, or nothing found. */
@Composable
private fun SourcesContent(
	stage: GapStage,
	ui: GapBoxUi,
	gap: LbGap?,
	onBack: () -> Unit,
	onClose: () -> Unit,
	onSelect: (Int) -> Unit,
	onRequest: (LbGapSource) -> Unit,
	onAuto: () -> Unit,
	onSearchAgain: () -> Unit,
	onAllowMp3: () -> Unit
) {
	val missing = gap?.let { g -> g.tracks.count { it.state != "present" && it.state != "done" } } ?: 0
	Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
		IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, stringResource(Res.string.gap_back), Modifier.size(20.dp)) }
		Column(Modifier.weight(1f)) {
			Text(stringResource(Res.string.action_find_sources), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
			if (missing > 0) {
				Text(
					pluralStringResource(Res.plurals.gap_for_missing_only, missing, missing),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}
		}
		IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, stringResource(Res.string.action_close), Modifier.size(20.dp)) }
	}
	HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
	val motion = rememberFoldMotion()
	AnimatedContent(
		targetState = stage,
		contentKey = { it::class },
		transitionSpec = {
			fadeIn(motion.effects()) togetherWith fadeOut(motion.effects()) using SizeTransform(clip = false) { _, _ -> snap() }
		},
		label = "gapSources"
	) { shown ->
		Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			when (shown) {
				is GapStage.Choosing -> {
					Text(
						pluralStringResource(Res.plurals.gap_sources_best_first, shown.sources.size, shown.sources.size).uppercase(),
						style = MaterialTheme.typography.labelMedium,
						fontWeight = FontWeight.Bold,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.padding(start = 4.dp)
					)
					val chosen = ui.selected?.let { shown.sources.getOrNull(it) }
					SourceCards(
						sources = shown.sources,
						selected = ui.selected,
						enabled = !ui.working,
						onSelect = onSelect,
						// A gap's folder holds the album; there is no "every file" to ask for.
						onRequestAll = onRequest,
						listingFor = { ui.listings[it.id] },
						// Request sits right under the chosen card, as the storyboard has it; with no
						// safe preselection there is none until a card is tapped (a tap is consent).
						belowSelected = {
							Button(
								onClick = { chosen?.let(onRequest) },
								enabled = chosen != null && !ui.working,
								shape = ContinuousCapsule,
								modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp)
							) {
								Text(
									pluralStringResource(Res.plurals.gap_request_tracks, missing.coerceAtLeast(1), missing.coerceAtLeast(1)),
									fontWeight = FontWeight.SemiBold
								)
							}
						}
					)
					if (gap?.mp3WouldHelp == true && gap.allowMp3.not()) {
						OutlinedButton(onClick = onAllowMp3, enabled = !ui.working, shape = ContinuousCapsule, modifier = Modifier.fillMaxWidth()) {
							Text(stringResource(Res.string.gap_allow_mp3_request))
						}
					}
					FlowRow {
						TextButton(onClick = onSearchAgain, enabled = !ui.working) { Text(stringResource(Res.string.action_search_again)) }
						TextButton(onClick = onAuto, enabled = !ui.working) { Text(stringResource(Res.string.action_auto_pick)) }
					}
				}
				is GapStage.NoSources -> {
					Text(
						shown.reason.ifBlank { stringResource(Res.string.info_no_sources) },
						style = MaterialTheme.typography.bodyMedium
					)
					if (shown.mp3WouldHelp) {
						Button(onClick = onAllowMp3, enabled = !ui.working, shape = ContinuousCapsule, modifier = Modifier.fillMaxWidth()) {
							Text(stringResource(Res.string.gap_allow_mp3_request))
						}
					}
					OutlinedButton(onClick = onSearchAgain, enabled = !ui.working, shape = ContinuousCapsule, modifier = Modifier.fillMaxWidth()) {
						Text(stringResource(Res.string.action_search_again))
					}
				}
				else -> BusyLine(
					gap?.sourceTask?.current?.ifBlank { null } ?: stringResource(Res.string.gap_searching)
				)
			}
		}
	}
}

/** "Getting 3 tracks / FLAC · from <folder>", the bar, the steps, Cancel request. */
@Composable
private fun ActiveContent(stage: GapStage.Active, ui: GapBoxUi, onCancel: () -> Unit) {
	Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
		Box(
			Modifier.clip(ContinuousCapsule).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 10.dp, vertical = 6.dp)
		) {
			CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSecondaryContainer)
		}
		Column(Modifier.weight(1f).padding(start = 12.dp)) {
			Text(
				pluralStringResource(Res.plurals.gap_getting, stage.wanted, stage.wanted),
				style = MaterialTheme.typography.titleSmall,
				fontWeight = FontWeight.SemiBold
			)
			val source = ui.fetched
			if (source != null) {
				val folder = source.folder.substringAfterLast('\\').substringAfterLast('/').ifBlank { source.peer }
				Text(
					listOfNotNull(source.format.ifBlank { null }, stringResource(Res.string.gap_from, folder)).joinToString(" · "),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
			}
		}
		if (stage.wanted > 0) {
			Text(
				stringResource(Res.string.request_step_count, stage.done, stage.wanted),
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
		}
	}
	HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
	Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
		val motion = rememberFoldMotion()
		// The bar eases to each value, never snaps.
		val progress by animateFloatAsState(
			if (stage.wanted > 0) stage.done.toFloat() / stage.wanted else 0f,
			motion.spatial(),
			label = "gapProgress"
		)
		LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
		RequestSteps(stage.steps)
		if (stage.failed > 0) {
			Text(
				stringResource(Res.string.info_gap_failed, stage.failed),
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.error
			)
		}
		OutlinedButton(onClick = onCancel, enabled = !ui.working, shape = ContinuousCapsule) {
			Text(stringResource(Res.string.request_cancel))
		}
	}
}

/** The result row the finish folds down to, before the box folds away. */
@Composable
private fun DoneContent(stage: GapStage.Done) {
	Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
		StatePill(Pill(FillText.Res(Res.string.lbbot_fill_verified), PillTone.GOOD))
		Column(Modifier.weight(1f).padding(start = 12.dp)) {
			Text(
				pluralStringResource(Res.plurals.gap_added, stage.added, stage.added),
				style = MaterialTheme.typography.titleSmall,
				fontWeight = FontWeight.SemiBold
			)
			if (stage.total > 0) {
				Text(
					stringResource(Res.string.gap_in_library, stage.present, stage.total),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}
		}
		Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
	}
}

/** A fill that failed or waits on lb-bot: why, in lb-bot's words, and a row per way on. */
@Composable
private fun RecoveryContent(
	pill: Pill,
	reason: String,
	actions: List<Pair<org.jetbrains.compose.resources.StringResource, () -> Unit>>,
	working: Boolean
) {
	Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
		StatePill(pill)
		if (reason.isNotBlank()) {
			Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
		Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
			actions.forEachIndexed { index, (label, onClick) ->
				SegmentedListItem(
					onClick = onClick,
					enabled = !working,
					shapes = segmentedShapes(index = index, count = actions.size),
					colors = SegmentedListItemDefaults.segmentedColors(
						containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
						disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
					)
				) {
					Text(stringResource(label))
				}
			}
		}
	}
}
