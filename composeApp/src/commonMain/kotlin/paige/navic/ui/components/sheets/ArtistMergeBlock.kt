package paige.navic.ui.components.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_mbid_merge
import navic.composeapp.generated.resources.action_mbid_merge_done
import navic.composeapp.generated.resources.action_retry
import navic.composeapp.generated.resources.action_undo
import navic.composeapp.generated.resources.count_albums
import navic.composeapp.generated.resources.count_mbid_merge_files
import navic.composeapp.generated.resources.info_album_match_row_failures
import navic.composeapp.generated.resources.info_album_match_still_working
import navic.composeapp.generated.resources.info_mbid_merge_blurb
import navic.composeapp.generated.resources.info_mbid_merge_counting
import navic.composeapp.generated.resources.info_mbid_merge_gone
import navic.composeapp.generated.resources.info_mbid_merge_names
import navic.composeapp.generated.resources.info_mbid_merge_not_finished
import navic.composeapp.generated.resources.info_mbid_merge_not_undone
import navic.composeapp.generated.resources.info_mbid_merge_older
import navic.composeapp.generated.resources.info_mbid_merge_preview
import navic.composeapp.generated.resources.info_mbid_merge_retagged
import navic.composeapp.generated.resources.info_mbid_merge_retagged_partly
import navic.composeapp.generated.resources.info_mbid_merge_retagging
import navic.composeapp.generated.resources.info_mbid_merge_retagging_count
import navic.composeapp.generated.resources.info_mbid_merge_undo_note
import navic.composeapp.generated.resources.info_mbid_merge_undoing
import navic.composeapp.generated.resources.info_mbid_merge_undone
import navic.composeapp.generated.resources.label_mbid_merge_keep
import navic.composeapp.generated.resources.label_mbid_merge_with
import navic.composeapp.generated.resources.option_mbid_merge_spelling
import navic.composeapp.generated.resources.title_mbid_merge
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbJobPhase
import paige.navic.domain.manager.LbMergePreview
import paige.navic.domain.manager.LbSibling
import paige.navic.domain.manager.MergeBlockView
import paige.navic.domain.manager.MergeJob
import paige.navic.domain.manager.MergeJobState
import paige.navic.domain.manager.MergeOutcome
import paige.navic.domain.manager.mergeBlockView
import paige.navic.domain.manager.mergeDefaultKeep
import paige.navic.domain.manager.mergeJobFor
import paige.navic.domain.manager.mergeJobInFlight
import paige.navic.domain.manager.mergeOthers
import paige.navic.domain.manager.mergeOutcome
import paige.navic.domain.manager.mergeToPickUp
import paige.navic.domain.manager.planMerge
import paige.navic.domain.manager.siblingGroupFor

/** How many of the files lb-bot could not write are listed; the count above them is the whole. */
private const val FAILURES_SHOWN = 10

/** What an ended merge or undo is said to have come to. Every number is lb-bot's own. */
internal fun mergeOutcomeSentence(outcome: MergeOutcome): LbSentence = when (outcome) {
	is MergeOutcome.Retagged ->
		LbSentence(plural = Res.plurals.info_mbid_merge_retagged, quantity = outcome.files, args = listOf(outcome.files))
	is MergeOutcome.RetaggedPartly -> LbSentence(
		plural = Res.plurals.info_mbid_merge_retagged_partly,
		quantity = outcome.total,
		args = listOf(outcome.done, outcome.total, outcome.failed)
	)
	is MergeOutcome.DidNotFinish -> LbSentence(
		plural = Res.plurals.info_mbid_merge_not_finished,
		quantity = outcome.total,
		args = listOf(outcome.done, outcome.total)
	)
	MergeOutcome.Undone -> LbSentence(string = Res.string.info_mbid_merge_undone)
	MergeOutcome.NotUndone -> LbSentence(string = Res.string.info_mbid_merge_not_undone)
	MergeOutcome.Gone -> LbSentence(string = Res.string.info_mbid_merge_gone)
	MergeOutcome.StillWorking -> LbSentence(string = Res.string.info_album_match_still_working)
}

/**
 * "Merge into one name" (I-023), a block of the MusicBrainz match sheet.
 *
 * Navidrome keys artists by name, so two spellings of one artist are two artists even with one
 * MusicBrainz id. lb-bot lists such groups (`siblings`), and this block joins two of them: pick
 * the other spelling when there are several, pick which of the two names to keep (the one with
 * the most albums, unless told otherwise), read lb-bot's own count of what would be retagged,
 * merge. lb-bot keeps the previous tags, and Undo — here once it is done, and in the "lb-bot
 * data" panel — puts them back.
 *
 * **What the block shows is `mergeBlockView`.** While a merge or an undo concerns this artist it
 * shows that job — running, or over and not yet read — with the names from the job, because a
 * finished merge removes the very sibling group that offered it. The job is the manager's
 * ([LbBotManager.mergeJobs]): closing the sheet stops nothing, and "Done" is what puts the form
 * back.
 *
 * Every sentence is a field of an answer. The block only shows while the hub offers the merge
 * route; an lb-bot that turns out to predate it says so in one muted line.
 */
@Composable
internal fun ArtistMergeBlock(ndArtistId: String) {
	if (ndArtistId.isBlank()) return
	val lbBot = koinInject<LbBotManager>()
	val scope = rememberCoroutineScope()
	val supported by lbBot.artistMergesSupported.collectAsState()
	val siblings by lbBot.artistSiblings.collectAsState()
	val jobs by lbBot.mergeJobs.collectAsState()

	val group = remember(siblings, ndArtistId) { siblingGroupFor(siblings, ndArtistId) }
	val others = remember(group, ndArtistId) { mergeOthers(group, ndArtistId) }
	val job = mergeJobFor(jobs.values, ndArtistId)
	val controls = supported && lbBot.isConfigured

	// A merge lb-bot is already working on with this artist's name as the kept one — started on
	// another client, or before a restart: followed from where it is, like any other.
	val merges by lbBot.artistMerges.collectAsState()
	val pickUp = mergeToPickUp(controls, merges, jobs, ndArtistId)
	LaunchedEffect(pickUp?.id) {
		val row = pickUp ?: return@LaunchedEffect
		val running = mergeJobInFlight(row) ?: return@LaunchedEffect
		lbBot.followMergeJob(
			job = running,
			rowId = row.id,
			intoId = row.into.ndArtistId,
			fromId = row.from.ndArtistId,
			intoName = row.into.name,
			fromName = row.from.name,
			seen = row
		)
	}

	// lb-bot answered "no such route" to this block's own request: said once, here.
	var older by remember(ndArtistId) { mutableStateOf(false) }

	val view = mergeBlockView(
		controls = controls,
		hasOthers = others.isNotEmpty(),
		job = job
	)
	if (view == MergeBlockView.Hidden && !older) return

	// On screen: a job's ending is not also said in a snackbar while this is showing it.
	DisposableEffect(ndArtistId) {
		lbBot.mergeBlockShown(ndArtistId)
		onDispose { lbBot.mergeBlockHidden(ndArtistId) }
	}

	// Ruled off above and below: the search under it is for the artist's MusicBrainz match, and
	// must not read as part of the merge.
	HorizontalDivider()
	SheetLabel(stringResource(Res.string.title_mbid_merge))

	if (view is MergeBlockView.Job && job != null) {
		MergeJobView(
			view = view,
			job = job,
			onUndo = { row ->
				lbBot.undoArtistMerge(row, job.intoId, job.fromId, view.into, view.from)
			},
			onDone = { lbBot.dismissMergeJob(job.rowId) }
		)
		HorizontalDivider()
		return
	}
	if (older || view != MergeBlockView.Form) {
		SheetCaption(stringResource(Res.string.info_mbid_merge_older))
		HorizontalDivider()
		return
	}

	// ----- the form ---------------------------------------------------------------------------
	val self = group?.artists?.firstOrNull { it.ndArtistId == ndArtistId }
	var otherPick by remember(ndArtistId) { mutableStateOf("") }
	var keepPick by remember(ndArtistId) { mutableStateOf("") }
	// A pick that is no longer on offer falls back to the default rather than naming nobody.
	val other = others.firstOrNull { it.ndArtistId == otherPick } ?: others.firstOrNull()
	val pair = listOfNotNull(self, other)
	val keepId = pair.firstOrNull { it.ndArtistId == keepPick }?.ndArtistId ?: mergeDefaultKeep(pair)
	// `nd_id` is the entry whose name is KEPT; keeping the other spelling's name swaps the pair.
	val plan = planMerge(ndArtistId, other?.ndArtistId.orEmpty(), keepId)

	var preview by remember(ndArtistId) { mutableStateOf<LbMergePreview?>(null) }
	var previewing by remember(ndArtistId) { mutableStateOf(false) }
	var previewError by remember(ndArtistId) { mutableStateOf<LbBotManager.LbError?>(null) }
	var previewReads by remember(ndArtistId) { mutableStateOf(0) }
	var busy by remember(ndArtistId) { mutableStateOf(false) }
	var error by remember(ndArtistId) { mutableStateOf<LbBotManager.LbError?>(null) }

	// lb-bot's own count for the pair as it stands. A new pair, or the same pair the other way
	// round, is another question.
	LaunchedEffect(plan, previewReads) {
		preview = null
		previewError = null
		error = null
		val asked = plan ?: return@LaunchedEffect
		previewing = true
		when (val result = lbBot.mergePreview(asked.ndId, asked.fromNdId)) {
			is LbBotManager.LbResult.Ok -> preview = result.value
			is LbBotManager.LbResult.Failed ->
				if (result.error == LbBotManager.LbError.RouteUnknown) older = true else previewError = result.error
		}
		previewing = false
	}

	SheetCaption(stringResource(Res.string.info_mbid_merge_blurb))
	if (others.size > 1) {
		// Three or more spellings: which of the others is merged with this artist.
		Text(stringResource(Res.string.label_mbid_merge_with), style = MaterialTheme.typography.bodyMedium)
		ChipRow(
			labels = others.map { it.name.ifBlank { it.ndArtistId } },
			selectedIndex = others.indexOfFirst { it.ndArtistId == other?.ndArtistId },
			onSelect = { index ->
				otherPick = others[index].ndArtistId
				keepPick = ""
			}
		)
	}
	Text(stringResource(Res.string.label_mbid_merge_keep), style = MaterialTheme.typography.bodyMedium)
	ChipRow(
		labels = pair.map { spellingLabel(it) },
		selectedIndex = pair.indexOfFirst { it.ndArtistId == keepId },
		onSelect = { index -> keepPick = pair[index].ndArtistId }
	)

	if (previewing) SheetCaption(stringResource(Res.string.info_mbid_merge_counting))
	preview?.let { counts ->
		Text(
			stringResource(
				Res.string.info_mbid_merge_preview,
				pluralStringResource(Res.plurals.count_mbid_merge_files, counts.files, counts.files),
				pluralStringResource(Res.plurals.count_albums, counts.albums, counts.albums),
				// The name lb-bot says it keeps: the proof that the pair was not swapped.
				counts.into.name.ifBlank { pair.firstOrNull { it.ndArtistId == keepId }?.name.orEmpty() }
			),
			style = MaterialTheme.typography.bodyMedium
		)
	}
	previewError?.let { failed ->
		// lb-bot's own words: two different artists, a download in flight, Navidrome not answering.
		LbErrorLine(failed)
		TextButton(onClick = { previewReads++ }) { Text(stringResource(Res.string.action_retry)) }
	}

	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(12.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		Button(
			// Only what lb-bot has counted is merged: the count is the confirmation.
			enabled = !busy && plan != null && preview != null,
			onClick = {
				if (plan != null) {
					scope.launch {
						busy = true
						error = null
						val names = pair.associate { it.ndArtistId to it.name }
						val result = lbBot.startArtistMerge(
							ndId = plan.ndId,
							fromNdId = plan.fromNdId,
							intoName = names[plan.ndId].orEmpty(),
							fromName = names[plan.fromNdId].orEmpty()
						)
						// A started merge is followed by the manager; the block then shows that job.
						if (result is LbBotManager.LbResult.Failed) {
							if (result.error == LbBotManager.LbError.RouteUnknown) older = true else error = result.error
						}
						busy = false
					}
				}
			}
		) { Text(stringResource(Res.string.action_mbid_merge)) }
		if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
	}
	LbErrorLine(error)
	HorizontalDivider()
}

@Composable
private fun spellingLabel(spelling: LbSibling): String =
	pluralStringResource(
		Res.plurals.option_mbid_merge_spelling,
		spelling.albums,
		spelling.name.ifBlank { spelling.ndArtistId },
		spelling.albums
	)

/**
 * A merge or an undo being followed, then what it came to: the two names, lb-bot's own progress,
 * the outcome, the files it could not write, and — for a merge at rest — Undo.
 */
@Composable
private fun MergeJobView(
	view: MergeBlockView.Job,
	job: MergeJobState,
	onUndo: suspend (rowId: String) -> LbBotManager.LbResult<*>,
	onDone: () -> Unit
) {
	val scope = rememberCoroutineScope()
	val poll = job.poll
	val row = poll.row
	var busy by remember(job.rowId) { mutableStateOf(false) }
	var error by remember(job.rowId) { mutableStateOf<LbBotManager.LbError?>(null) }

	// The direction the names are being rewritten in.
	if (view.from.isNotBlank() && view.into.isNotBlank()) {
		SheetCaption(
			if (view.undo) stringResource(Res.string.info_mbid_merge_names, view.into, view.from)
			else stringResource(Res.string.info_mbid_merge_names, view.from, view.into)
		)
	}

	val outcome = mergeOutcome(poll)
	if (outcome == null || job.unfinished) {
		// Running — or over, with the library still being brought to agree.
		val total = row?.total ?: 0
		val done = row?.done ?: 0
		Text(
			when {
				view.undo -> stringResource(Res.string.info_mbid_merge_undoing)
				total > 0 -> stringResource(Res.string.info_mbid_merge_retagging_count, done, total)
				else -> stringResource(Res.string.info_mbid_merge_retagging)
			},
			style = MaterialTheme.typography.bodyMedium
		)
		if (!view.undo && total > 0 && done < total) {
			LinearProgressIndicator(
				progress = { (done.toFloat() / total).coerceIn(0f, 1f) },
				modifier = Modifier.fillMaxWidth()
			)
		} else {
			LinearProgressIndicator(Modifier.fillMaxWidth())
		}
		return
	}

	Text(mergeOutcomeSentence(outcome).text(), style = MaterialTheme.typography.bodyMedium)
	val failures = row?.failures.orEmpty()
	if (failures.isNotEmpty() && !view.undo) {
		Text(
			pluralStringResource(Res.plurals.info_album_match_row_failures, failures.size, failures.size),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.error
		)
		failures.take(FAILURES_SHOWN).forEach { failure ->
			Text(
				listOf(failure.album, failure.title, failure.reason).filter { it.isNotBlank() }.joinToString(" — "),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
		}
	}
	LbErrorLine(error)

	// A merge at rest — landed, or failed part-way — can be undone: lb-bot restores what it wrote.
	val undoable = poll.job == MergeJob.MERGE && row != null &&
		(poll.phase == LbJobPhase.DONE || poll.phase == LbJobPhase.FAILED)
	if (undoable) SheetCaption(stringResource(Res.string.info_mbid_merge_undo_note))
	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(12.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		Button(enabled = !busy, onClick = onDone) { Text(stringResource(Res.string.action_mbid_merge_done)) }
		if (undoable && row != null) {
			TextButton(
				enabled = !busy,
				onClick = {
					scope.launch {
						busy = true
						error = null
						(onUndo(row.id) as? LbBotManager.LbResult.Failed)?.let { error = it.error }
						busy = false
					}
				}
			) { Text(stringResource(Res.string.action_undo)) }
		}
		if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
	}
}
