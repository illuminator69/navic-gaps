package paige.navic.ui.components.sheets

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_album_match_apply
import navic.composeapp.generated.resources.action_album_match_apply_anyway
import navic.composeapp.generated.resources.action_album_match_open_album
import navic.composeapp.generated.resources.action_album_match_preview
import navic.composeapp.generated.resources.action_album_match_set_cover
import navic.composeapp.generated.resources.action_album_match_show_unchanged
import navic.composeapp.generated.resources.action_album_match_to_artist
import navic.composeapp.generated.resources.action_cancel
import navic.composeapp.generated.resources.action_close
import navic.composeapp.generated.resources.action_fix_album_match
import navic.composeapp.generated.resources.action_retry
import navic.composeapp.generated.resources.action_undo
import navic.composeapp.generated.resources.count_album_match_discs
import navic.composeapp.generated.resources.count_tracks
import navic.composeapp.generated.resources.info_album_match_applying
import navic.composeapp.generated.resources.info_album_match_applying_count
import navic.composeapp.generated.resources.info_album_match_bad_id
import navic.composeapp.generated.resources.info_album_match_changed
import navic.composeapp.generated.resources.info_album_match_changing
import navic.composeapp.generated.resources.info_album_match_commit
import navic.composeapp.generated.resources.info_album_match_commit_cover
import navic.composeapp.generated.resources.info_album_match_commit_with_cover
import navic.composeapp.generated.resources.info_album_match_cover_saved
import navic.composeapp.generated.resources.info_album_match_editions_failed
import navic.composeapp.generated.resources.info_album_match_gone
import navic.composeapp.generated.resources.info_album_match_looking_up
import navic.composeapp.generated.resources.info_album_match_lookup_failed
import navic.composeapp.generated.resources.info_album_match_missing
import navic.composeapp.generated.resources.info_album_match_no_covers
import navic.composeapp.generated.resources.info_album_match_no_editions
import navic.composeapp.generated.resources.info_album_match_no_group
import navic.composeapp.generated.resources.info_album_match_no_place
import navic.composeapp.generated.resources.info_album_match_no_slot
import navic.composeapp.generated.resources.info_album_match_no_slot_note
import navic.composeapp.generated.resources.info_album_match_not_finished
import navic.composeapp.generated.resources.info_album_match_not_indexed
import navic.composeapp.generated.resources.info_album_match_not_removed
import navic.composeapp.generated.resources.info_album_match_nothing
import navic.composeapp.generated.resources.info_album_match_older
import navic.composeapp.generated.resources.info_album_match_poor
import navic.composeapp.generated.resources.info_album_match_previewing
import navic.composeapp.generated.resources.info_album_match_removed
import navic.composeapp.generated.resources.info_album_match_removing
import navic.composeapp.generated.resources.info_album_match_row_failures
import navic.composeapp.generated.resources.info_album_match_still_working
import navic.composeapp.generated.resources.info_album_match_tagged
import navic.composeapp.generated.resources.info_album_match_untagged
import navic.composeapp.generated.resources.info_album_match_wrote
import navic.composeapp.generated.resources.info_album_match_wrote_partly
import navic.composeapp.generated.resources.label_mbid_match_current
import navic.composeapp.generated.resources.option_album_match_keep_cover
import navic.composeapp.generated.resources.option_album_match_paste
import navic.composeapp.generated.resources.title_album_match_cover
import navic.composeapp.generated.resources.title_album_match_editions
import navic.composeapp.generated.resources.title_album_match_missing
import navic.composeapp.generated.resources.title_album_match_preview
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.domain.manager.ALBUM_CODE_IN_PROGRESS
import paige.navic.domain.manager.ALBUM_CODE_NO_OVERRIDE
import paige.navic.domain.manager.ALBUM_POLL_INTERVAL_MS
import paige.navic.domain.manager.ALBUM_STATE_FAILED
import paige.navic.domain.manager.AlbumAfter
import paige.navic.domain.manager.AlbumCommit
import paige.navic.domain.manager.AlbumEditionRow
import paige.navic.domain.manager.AlbumJob
import paige.navic.domain.manager.AlbumOutcome
import paige.navic.domain.manager.AlbumPoll
import paige.navic.domain.manager.AlbumPollPhase
import paige.navic.domain.manager.AlbumPoorMatchQuestion
import paige.navic.domain.manager.AlbumPreviewSummary
import paige.navic.domain.manager.AlbumTaggedAs
import paige.navic.domain.manager.LbAlbumApply
import paige.navic.domain.manager.LbAlbumCover
import paige.navic.domain.manager.LbAlbumFailure
import paige.navic.domain.manager.LbAlbumOverrideRow
import paige.navic.domain.manager.LbAlbumPreview
import paige.navic.domain.manager.LbAlbumState
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbCachePolicy
import paige.navic.domain.manager.LbPreviewFile
import paige.navic.domain.manager.LbPreviewRelease
import paige.navic.domain.manager.LbReleaseDetail
import paige.navic.domain.manager.albumAfterPoll
import paige.navic.domain.manager.albumCommit
import paige.navic.domain.manager.albumEditionRows
import paige.navic.domain.manager.albumJobInFlight
import paige.navic.domain.manager.albumLookupFreeText
import paige.navic.domain.manager.albumLookupQuery
import paige.navic.domain.manager.albumOutcome
import paige.navic.domain.manager.albumPollRead
import paige.navic.domain.manager.albumPollStart
import paige.navic.domain.manager.albumPollStep
import paige.navic.domain.manager.albumTaggedAs
import paige.navic.domain.manager.forceAllowed
import paige.navic.domain.manager.parseReleaseMbid
import paige.navic.domain.manager.previewMultiDisc
import paige.navic.domain.manager.previewSummary
import paige.navic.domain.manager.releaseGroupFor
import paige.navic.domain.manager.slotLabel
import paige.navic.domain.repositories.DbRepository
import paige.navic.ui.components.common.CoverArt
import paige.navic.ui.components.common.RemoteCoverArt
import paige.navic.ui.util.rememberCoverAmbient
import kotlin.time.Clock

/** What the sheet knows of the album, from `GET /lb/album/override`. */
private sealed interface AlbumLoad {
	data object Loading : AlbumLoad
	data class Failed(val error: LbBotManager.LbError) : AlbumLoad
	data class Loaded(val state: LbAlbumState) : AlbumLoad
}

/** Which of the sheet's writes is out, so its spinner and its refusal sit beside its own button. */
private enum class AlbumAction { COMMIT, UNDO }

private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

/** Cover candidates across the sheet: "keep" plus two, then threes. */
private const val COVERS_PER_ROW = 3

/**
 * "Fix album match" (I-022): choose which MusicBrainz release an owned album is.
 *
 * lb-bot retags the album's files to the release picked here — release id, disc and track
 * numbers, titles, date; tags only, never a rename or a move — and keeps the previous tags, so
 * "Undo" puts them back. It can also save a Cover Art Archive image as the album's cover. The
 * steps, in the order the sheet shows them: what the album is tagged as now (and that lb-bot
 * changed it, when it did), the editions of its release group, a preview of what a pick would
 * rewrite, the cover, apply.
 *
 * **Every sentence here states what lb-bot answered, and nothing else.** The counts are fields
 * of its preview, the progress is its row's `done` / `total`, a refusal is its own text. In
 * particular nothing says whether a favourite, a rating or a play count survives a retag: that
 * is not measured.
 *
 * The rules — what a refusal means, what the three counts are, what ends the poll, where to go
 * afterwards — are the pure functions in `AlbumOverride.kt`; this file only asks and draws.
 * The reads and the poll run in the sheet's own scope and stop when it is dismissed. A write
 * the user confirmed is finished by [LbBotManager] whether or not the sheet is still there, and
 * a job found running when the sheet opens (started here before it was closed, or on another
 * client) is picked up again.
 *
 * **A retag changes the album's Navidrome id**, so [albumId] is dead once a change has landed.
 * [onFinished] is then called on the way out with where the user belongs ([AlbumAfter]): the
 * album under its new id once Room holds it, else the artist's page. The page under the sheet
 * is not replaced while the sheet is open — the sheet lives in that page, and what lb-bot wrote
 * would vanish unread.
 *
 * @param albumId the Navidrome album id the sheet was opened on.
 * @param albumTitle and [artistName]: the library's own names, shown until lb-bot has answered.
 * @param onFinished `asked` is true when the user pressed the sheet's own button, false when
 *   the sheet was swiped away. A host sitting on the dead album's page leaves it either way; a
 *   host that is not (the artist's page) only goes anywhere when asked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumMatchSheet(
	albumId: String,
	albumTitle: String,
	artistName: String,
	coverArtId: String?,
	onFinished: (after: AlbumAfter, asked: Boolean) -> Unit,
	onDismissRequest: () -> Unit
) {
	val lbBot = koinInject<LbBotManager>()
	val dbRepository = koinInject<DbRepository>()
	val scope = rememberCoroutineScope()
	val title = stringResource(Res.string.action_fix_album_match)

	// What lb-bot says the album is. A re-read keeps what was read, so it never blanks the sheet.
	var load by remember(albumId) { mutableStateOf<AlbumLoad>(AlbumLoad.Loading) }
	var reads by remember(albumId) { mutableStateOf(0) }
	// lb-bot answered "no such route" for one of the album routes: it predates them.
	var older by remember(albumId) { mutableStateOf(false) }

	// The release group whose editions are listed: null until settled, "" when nothing names one.
	var rgid by remember(albumId) { mutableStateOf<String?>(null) }
	var lookupFailed by remember(albumId) { mutableStateOf(false) }
	var detail by remember(albumId) { mutableStateOf<LbReleaseDetail?>(null) }
	var editionsLoading by remember(albumId) { mutableStateOf(false) }
	var editionsFailed by remember(albumId) { mutableStateOf(false) }
	var editionReads by remember(albumId) { mutableStateOf(0) }

	// The release picked — an edition's row, or a pasted id — and lb-bot's preview of it.
	var picked by remember(albumId) { mutableStateOf("") }
	var preview by remember(albumId) { mutableStateOf<LbAlbumPreview?>(null) }
	var previewLoading by remember(albumId) { mutableStateOf(false) }
	var previewError by remember(albumId) { mutableStateOf<LbBotManager.LbError?>(null) }
	var previewReads by remember(albumId) { mutableStateOf(0) }

	// Null covers: not read yet. A null choice is "keep the current cover", the default.
	var covers by remember(albumId) { mutableStateOf<List<LbAlbumCover>?>(null) }
	var coversLoading by remember(albumId) { mutableStateOf(false) }
	var coversError by remember(albumId) { mutableStateOf<LbBotManager.LbError?>(null) }
	var coverReads by remember(albumId) { mutableStateOf(0) }
	var cover by remember(albumId) { mutableStateOf<String?>(null) }

	// One write at a time. Each refusal is kept for the button it answers: the Undo sits at the
	// top of a long sheet and the Apply at the bottom, and one shared line would land off-screen.
	var busy by remember(albumId) { mutableStateOf<AlbumAction?>(null) }
	val working = busy != null
	var commitError by remember(albumId) { mutableStateOf<LbBotManager.LbError?>(null) }
	var undoError by remember(albumId) { mutableStateOf<LbBotManager.LbError?>(null) }
	// lb-bot's `poor_match`, kept with the release it was answered for.
	var poorMatch by remember(albumId) { mutableStateOf<AlbumPoorMatchQuestion?>(null) }
	var coverSaved by remember(albumId) { mutableStateOf(false) }

	// The apply or remove being followed, by the override row's id.
	var poll by remember(albumId) { mutableStateOf<AlbumPoll?>(null) }
	var pollId by remember(albumId) { mutableStateOf("") }
	var pollRuns by remember(albumId) { mutableStateOf(0) }
	// Where the user belongs once a change has landed; null while nothing has.
	var after by remember(albumId) { mutableStateOf<AlbumAfter?>(null) }

	val state = (load as? AlbumLoad.Loaded)?.state

	fun follow(job: AlbumJob, id: String, seen: LbAlbumState? = null) {
		pollId = id
		poll = albumPollStart(job, nowMs(), seen)
		after = null
		commitError = null
		undoError = null
		pollRuns++
	}

	// What the album is now.
	LaunchedEffect(albumId, reads) {
		if (load !is AlbumLoad.Loaded) load = AlbumLoad.Loading
		when (val result = lbBot.albumOverrideState(albumId)) {
			is LbBotManager.LbResult.Ok -> {
				load = AlbumLoad.Loaded(result.value)
				// A job already running is followed, not shown as a row frozen mid-way.
				val running = albumJobInFlight(result.value)
				val rowId = result.value.override?.id.orEmpty()
				if (running != null && rowId.isNotBlank() && poll?.phase != AlbumPollPhase.POLLING) {
					follow(running, rowId, seen = result.value)
				}
			}
			is LbBotManager.LbResult.Failed -> when {
				result.error == LbBotManager.LbError.RouteUnknown -> older = true
				// A re-read that failed leaves what the first one read.
				load !is AlbumLoad.Loaded -> load = AlbumLoad.Failed(result.error)
			}
		}
	}

	// Its release group, then that group's editions.
	LaunchedEffect(albumId, state?.rgid, state?.album, state?.artist, editionReads) {
		val loaded = state ?: return@LaunchedEffect
		editionsLoading = true
		editionsFailed = false
		lookupFailed = false
		var group = loaded.rgid
		if (group.isBlank()) {
			rgid = null
			// lb-bot cannot tell the group (an untagged album of an artist it has not indexed).
			// One MusicBrainz search, as the Discover screen resolves a browse row: fielded,
			// and the answer validated against the artist and the title. Declining is fine.
			val artist = loaded.artist.ifBlank { artistName }
			val album = loaded.album.ifBlank { albumTitle }
			var found = lbBot.albumLookupResult(albumLookupQuery(artist, album))
			currentCoroutineContext().ensureActive()
			val freeText = albumLookupFreeText(artist, album)
			if (found is LbBotManager.LbResult.Ok && found.value.isEmpty() && freeText.isNotBlank()) {
				found = lbBot.albumLookupResult(freeText)
				currentCoroutineContext().ensureActive()
			}
			when (found) {
				is LbBotManager.LbResult.Ok -> group = releaseGroupFor(found.value, albumId, artist, album).orEmpty()
				// "lb-bot does not know" is not said of a lookup that was never answered.
				is LbBotManager.LbResult.Failed -> lookupFailed = true
			}
		}
		rgid = group
		if (group.isBlank()) {
			detail = null
			editionsLoading = false
			return@LaunchedEffect
		}
		// The cached editions first, as everywhere else; "Retry" asks whatever their age.
		val policy = if (editionReads > 0) LbCachePolicy.REFRESH else LbCachePolicy.NORMAL
		lbBot.albumReleases(group, policy).collect { answer ->
			detail = answer
			editionsFailed = answer == null
			editionsLoading = false
		}
	}

	// What picking a release would rewrite. A new pick starts over: preview, covers, choice.
	LaunchedEffect(albumId, picked, previewReads) {
		preview = null
		previewError = null
		covers = null
		coversError = null
		cover = null
		poorMatch = null
		commitError = null
		if (picked.isBlank()) return@LaunchedEffect
		previewLoading = true
		when (val result = lbBot.previewAlbumOverride(albumId, picked)) {
			is LbBotManager.LbResult.Ok -> preview = result.value
			is LbBotManager.LbResult.Failed ->
				if (result.error == LbBotManager.LbError.RouteUnknown) older = true else previewError = result.error
		}
		previewLoading = false
	}

	// The covers on offer for the previewed release, after the preview so the two slow
	// requests do not take two of the hub's four slots at once.
	LaunchedEffect(albumId, preview?.releaseMbid, coverReads) {
		val shown = preview ?: return@LaunchedEffect
		coversLoading = true
		coversError = null
		when (val result = lbBot.albumCovers(rgid.orEmpty(), shown.releaseMbid)) {
			is LbBotManager.LbResult.Ok -> covers = result.value
			is LbBotManager.LbResult.Failed ->
				if (result.error == LbBotManager.LbError.RouteUnknown) older = true else coversError = result.error
		}
		coversLoading = false
	}

	// The poll. It belongs to the sheet: dismissing the sheet ends it, and each tick is one
	// short request. lb-bot goes on working either way, and a reopened sheet picks the job up.
	LaunchedEffect(albumId, pollRuns) {
		val id = pollId
		var current = poll ?: return@LaunchedEffect
		if (id.isBlank()) return@LaunchedEffect
		while (current.phase == AlbumPollPhase.POLLING) {
			delay(ALBUM_POLL_INTERVAL_MS)
			current = albumPollStep(current, albumPollRead(lbBot.albumOverrideById(id)), nowMs())
			poll = current
		}
		// Over, or given up on. The device's copy of lb-bot's override lists is asked for again;
		// the album itself arrives through the `library` frame lb-bot sends once Navidrome has
		// indexed it, which the library sync already follows.
		lbBot.requestArtistOverridesRefresh()
		val target = albumAfterPoll(current, albumId)
		if (target is AlbumAfter.Album) {
			// The album's page loads from Room and refuses an id Room does not hold, and that
			// frame may not have arrived. So this one album is pulled first, and until it is
			// in — or if it cannot be read yet — the artist's page is where to go.
			after = AlbumAfter.Artist
			if (dbRepository.syncAlbumsById(listOf(target.albumId)).isSuccess) {
				// As the library sync says after a pull of its own: Room holds it, re-read.
				lbBot.onLocalLibrarySynced(listOf(target.albumId))
				after = target
			}
		} else {
			after = target
		}
	}

	fun refused(action: AlbumAction, error: LbBotManager.LbError) {
		if (error == LbBotManager.LbError.RouteUnknown) {
			older = true
			return
		}
		when (action) {
			AlbumAction.COMMIT -> commitError = error
			AlbumAction.UNDO -> undoError = error
		}
		// lb-bot is already working on this album, or no longer holds the row: what the sheet
		// shows is behind. Read it again; a job found running is then followed.
		val code = (error as? LbBotManager.LbError.Rejected)?.code
		if (code == ALBUM_CODE_IN_PROGRESS || code == ALBUM_CODE_NO_OVERRIDE) reads++
	}

	fun apply(releaseMbid: String, confirmed: Boolean) {
		if (working) return
		// `force` only for the release whose `poor_match` the user has just confirmed.
		val force = confirmed && forceAllowed(poorMatch, releaseMbid)
		scope.launch {
			busy = AlbumAction.COMMIT
			commitError = null
			poorMatch = null
			when (val result = lbBot.applyAlbumOverride(albumId, releaseMbid, cover, force)) {
				is LbBotManager.LbResult.Ok -> when (val answer = result.value) {
					is LbAlbumApply.Started -> follow(AlbumJob.APPLY, answer.id)
					is LbAlbumApply.PoorMatch ->
						poorMatch = AlbumPoorMatchQuestion(releaseMbid, answer.matched, answer.total)
				}
				is LbBotManager.LbResult.Failed -> refused(AlbumAction.COMMIT, result.error)
			}
			busy = null
		}
	}

	fun saveCover(coverUrl: String) {
		if (working) return
		scope.launch {
			busy = AlbumAction.COMMIT
			commitError = null
			when (val result = lbBot.setAlbumCover(albumId, coverUrl)) {
				// The release and the tags stay, so the album stays where it is.
				is LbBotManager.LbResult.Ok -> {
					coverSaved = true
					after = AlbumAfter.Stay
				}
				is LbBotManager.LbResult.Failed -> refused(AlbumAction.COMMIT, result.error)
			}
			busy = null
		}
	}

	fun undo(row: LbAlbumOverrideRow) {
		if (working || row.id.isBlank()) return
		scope.launch {
			busy = AlbumAction.UNDO
			undoError = null
			when (val result = lbBot.removeAlbumOverride(row.id)) {
				is LbBotManager.LbResult.Ok -> follow(AlbumJob.REMOVE, row.id)
				is LbBotManager.LbResult.Failed -> refused(AlbumAction.UNDO, result.error)
			}
			busy = null
		}
	}

	// Once a change has landed the album may live under another id: the host is told where to
	// go instead of being left on the page of an id that is dead.
	fun close(asked: Boolean) {
		val target = after
		onDismissRequest()
		if (target != null) onFinished(target, asked)
	}

	ModalBottomSheet(
		onDismissRequest = { close(asked = false) },
		sheetTitle = title,
		ambient = rememberCoverAmbient(coverArtId)
	) {
		// ONE scrolling column, as GapFillSheet learned (B-043): the edition list, the table and
		// the cover grid are tens of rows at most, and an inner list capped inside a column that
		// does not scroll leaves everything under it out of reach on a short screen.
		Column(
			modifier = Modifier
				.verticalScroll(rememberScrollState())
				.padding(horizontal = 20.dp)
				.padding(bottom = 32.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp)
		) {
			Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
			Text(
				listOf(
					state?.album.orEmpty().ifBlank { albumTitle },
					state?.artist.orEmpty().ifBlank { artistName }
				).filter { it.isNotBlank() }.joinToString(" • "),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis
			)

			val current = load
			val running = poll
			when {
				// One muted sentence, never an error: this lb-bot simply has no such routes yet.
				older -> SheetCaption(stringResource(Res.string.info_album_match_older))

				current is AlbumLoad.Loading -> Spinner()

				current is AlbumLoad.Failed -> {
					LbErrorLine(current.error)
					TextButton(onClick = { reads++ }) { Text(stringResource(Res.string.action_retry)) }
				}

				current is AlbumLoad.Loaded && running != null -> AlbumJobBlock(
					poll = running,
					after = after,
					enabled = !working,
					undoing = busy == AlbumAction.UNDO,
					undoError = undoError,
					onUndo = { row -> undo(row) },
					onClose = { close(asked = true) }
				)

				current is AlbumLoad.Loaded && coverSaved -> {
					Text(stringResource(Res.string.info_album_match_cover_saved), style = MaterialTheme.typography.bodyMedium)
					Button(onClick = { close(asked = true) }) { Text(stringResource(Res.string.action_close)) }
				}

				current is AlbumLoad.Loaded -> {
					val loaded = current.state
					val rows = remember(detail, loaded.releaseMbid) {
						albumEditionRows(detail?.releases.orEmpty(), loaded.releaseMbid)
					}
					TaggedAsBlock(albumTaggedAs(loaded, rows))

					loaded.override?.let { row ->
						StandingRowBlock(
							row = row,
							enabled = !working,
							undoing = busy == AlbumAction.UNDO,
							error = undoError,
							onUndo = { undo(row) }
						)
					}

					SheetLabel(stringResource(Res.string.title_album_match_editions))
					when {
						// Not settled yet is "loading", never "none": the group is unknown until
						// the effect above has run, and an empty list then says nothing.
						(editionsLoading || rgid == null) && rows.isEmpty() -> {
							if (loaded.rgid.isBlank() && rgid == null) {
								SheetCaption(stringResource(Res.string.info_album_match_looking_up))
							}
							Spinner()
						}
						lookupFailed -> {
							SheetCaption(stringResource(Res.string.info_album_match_lookup_failed))
							TextButton(onClick = { editionReads++ }) { Text(stringResource(Res.string.action_retry)) }
						}
						// Nothing names a release group: the pasted id is the only way in.
						rgid == "" -> SheetCaption(stringResource(Res.string.info_album_match_no_group))
						editionsFailed -> {
							SheetCaption(stringResource(Res.string.info_album_match_editions_failed))
							TextButton(onClick = { editionReads++ }) { Text(stringResource(Res.string.action_retry)) }
						}
						rows.isEmpty() -> SheetCaption(stringResource(Res.string.info_album_match_no_editions))
					}
					rows.forEach { row ->
						EditionRow(
							row = row,
							selected = row.releaseMbid == picked,
							enabled = !working,
							onPick = { picked = row.releaseMbid }
						)
					}

					// For the pressing the list does not carry (lb-bot lists one release per
					// medium of each variant): the id itself, or the address of its page.
					val pasteState = rememberTextFieldState()
					val pasted = pasteState.text.toString()
					val parsed = parseReleaseMbid(pasted)
					TextField(
						state = pasteState,
						label = { Text(stringResource(Res.string.option_album_match_paste)) },
						lineLimits = TextFieldLineLimits.SingleLine,
						modifier = Modifier.fillMaxWidth()
					)
					if (pasted.isNotBlank() && parsed == null) {
						Text(
							stringResource(Res.string.info_album_match_bad_id),
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.error
						)
					}
					TextButton(
						enabled = !working && parsed != null && parsed != picked,
						onClick = { parsed?.let { picked = it } }
					) { Text(stringResource(Res.string.action_album_match_preview)) }

					if (picked.isNotBlank()) {
						SheetLabel(stringResource(Res.string.title_album_match_preview))
						if (previewLoading) {
							Row(
								horizontalArrangement = Arrangement.spacedBy(12.dp),
								verticalAlignment = Alignment.CenterVertically
							) {
								CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
								Text(
									stringResource(Res.string.info_album_match_previewing),
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant
								)
							}
						}
						previewError?.let { error ->
							// lb-bot's own words: no such release, MusicBrainz not answering.
							LbErrorLine(error)
							TextButton(onClick = { previewReads++ }) { Text(stringResource(Res.string.action_retry)) }
						}
					}

					preview?.let { shown ->
						val summary = remember(shown) { previewSummary(shown) }
						PreviewBlock(shown, summary)

						SheetLabel(stringResource(Res.string.title_album_match_cover))
						val offered = covers
						when {
							coversLoading -> Spinner()
							coversError != null -> {
								LbErrorLine(coversError)
								TextButton(onClick = { coverReads++ }) { Text(stringResource(Res.string.action_retry)) }
							}
							// No candidates: the step says so and is skipped.
							offered != null && offered.isEmpty() ->
								SheetCaption(stringResource(Res.string.info_album_match_no_covers))
							offered != null -> CoverGrid(
								covers = offered,
								currentCoverArtId = coverArtId,
								chosen = cover,
								enabled = !working,
								onChoose = { cover = it }
							)
						}

						val chosenCover = cover
						val commit = albumCommit(loaded.releaseMbid, shown.releaseMbid, summary.changing, chosenCover != null)
						// Shown, and confirmable, only over the release it was answered for.
						val question = poorMatch?.takeIf { forceAllowed(it, shown.releaseMbid) }
						when (commit) {
							AlbumCommit.NOTHING -> SheetCaption(stringResource(Res.string.info_album_match_nothing))

							AlbumCommit.COVER_ONLY -> {
								Text(
									stringResource(Res.string.info_album_match_commit_cover),
									style = MaterialTheme.typography.bodyMedium
								)
								ActionRow(busy == AlbumAction.COMMIT) {
									Button(
										enabled = !working && chosenCover != null,
										onClick = { chosenCover?.let { saveCover(it) } }
									) { Text(stringResource(Res.string.action_album_match_set_cover)) }
								}
							}

							AlbumCommit.APPLY -> {
								// The one fixed sentence before an apply, with lb-bot's own count.
								Text(
									pluralStringResource(
										Res.plurals.info_album_match_commit,
										summary.changing,
										summary.changing
									),
									style = MaterialTheme.typography.bodyMedium
								)
								if (chosenCover != null) {
									SheetCaption(stringResource(Res.string.info_album_match_commit_with_cover))
								}
								if (question != null) {
									// lb-bot wrote nothing and asks first; both numbers are its own.
									Text(
										stringResource(Res.string.info_album_match_poor, question.matched, question.total),
										style = MaterialTheme.typography.bodyMedium,
										color = MaterialTheme.colorScheme.error
									)
									ActionRow(busy == AlbumAction.COMMIT) {
										Button(
											enabled = !working,
											onClick = { apply(shown.releaseMbid, confirmed = true) }
										) { Text(stringResource(Res.string.action_album_match_apply_anyway)) }
										TextButton(enabled = !working, onClick = { poorMatch = null }) {
											Text(stringResource(Res.string.action_cancel))
										}
									}
								} else {
									ActionRow(busy == AlbumAction.COMMIT) {
										Button(
											enabled = !working,
											onClick = { apply(shown.releaseMbid, confirmed = false) }
										) { Text(stringResource(Res.string.action_album_match_apply)) }
									}
								}
							}
						}
					}

					// A refusal, in lb-bot's own words: a fill in flight for the album, an apply
					// already running, MusicBrainz or Navidrome not answering.
					LbErrorLine(commitError)
				}
			}
		}
	}
}

@Composable
private fun Spinner() {
	Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
		CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
	}
}

/** A button row with the "something was sent and has not answered" spinner beside it. */
@Composable
private fun ActionRow(working: Boolean, buttons: @Composable () -> Unit) {
	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(12.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		buttons()
		if (working) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
	}
}

/** "N tracks", or null where the count is not known. */
@Composable
private fun tracksText(count: Int): String? =
	if (count > 0) pluralStringResource(Res.plurals.count_tracks, count, count) else null

/** A release in one line: what tells it apart, then where, on what and how long it is. */
@Composable
private fun releaseLine(
	title: String,
	disambiguation: String,
	year: String,
	country: String,
	format: String,
	trackCount: Int,
	discCount: Int = 0
): String = listOfNotNull(
	listOf(title, disambiguation.takeIf { it.isNotBlank() }?.let { "($it)" }.orEmpty())
		.filter { it.isNotBlank() }.joinToString(" ").takeIf { it.isNotBlank() },
	year.takeIf { it.isNotBlank() },
	country.takeIf { it.isNotBlank() },
	format.takeIf { it.isNotBlank() },
	tracksText(trackCount),
	if (discCount > 1) pluralStringResource(Res.plurals.count_album_match_discs, discCount, discCount) else null
).joinToString(" • ")

/**
 * What the album's files are tagged as now. The id is lb-bot's statement; the line above it is
 * that release as the edition list describes it, and is simply absent where the list does not
 * carry the release.
 */
@Composable
private fun TaggedAsBlock(tagged: AlbumTaggedAs?) {
	if (tagged == null) {
		SheetCaption(stringResource(Res.string.info_album_match_untagged))
		return
	}
	SheetCaption(stringResource(Res.string.info_album_match_tagged))
	val line = releaseLine(tagged.title, tagged.disambiguation, tagged.year, tagged.country, tagged.format, tagged.trackCount)
	if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodyMedium)
	Text(
		tagged.releaseMbid,
		style = MaterialTheme.typography.labelSmall,
		color = MaterialTheme.colorScheme.onSurfaceVariant
	)
}

/** The override row lb-bot holds for the album, when no job is running: that it exists, and Undo. */
@Composable
private fun StandingRowBlock(
	row: LbAlbumOverrideRow,
	enabled: Boolean,
	undoing: Boolean,
	error: LbBotManager.LbError?,
	onUndo: () -> Unit
) {
	SheetCaption(stringResource(Res.string.info_album_match_changed))
	// `failed`: an apply that was interrupted. Undo restores what it did write.
	if (row.state == ALBUM_STATE_FAILED) {
		Text(
			pluralStringResource(Res.plurals.info_album_match_not_finished, row.total, row.done, row.total),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.error
		)
	}
	FailureList(row.failures)
	ActionRow(undoing) {
		TextButton(enabled = enabled, onClick = onUndo) { Text(stringResource(Res.string.action_undo)) }
	}
	LbErrorLine(error)
}

/** The files lb-bot could not write, each with its own reason. */
@Composable
private fun FailureList(failures: List<LbAlbumFailure>) {
	if (failures.isEmpty()) return
	Text(
		pluralStringResource(Res.plurals.info_album_match_row_failures, failures.size, failures.size),
		style = MaterialTheme.typography.bodySmall,
		color = MaterialTheme.colorScheme.error
	)
	failures.forEach { failure ->
		Text(
			listOf(failure.title, failure.reason).filter { it.isNotBlank() }.joinToString(" — "),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant
		)
	}
}

@Composable
private fun EditionRow(row: AlbumEditionRow, selected: Boolean, enabled: Boolean, onPick: () -> Unit) {
	// What tells two editions apart, in the order a person checks it: the medium, where it
	// was pressed, when, and how many tracks it has.
	val detail = listOfNotNull(
		row.label.ifBlank { row.format }.takeIf { it.isNotBlank() },
		row.country.takeIf { it.isNotBlank() },
		row.year.takeIf { it.isNotBlank() },
		tracksText(row.trackCount)
	).joinToString(" • ")
	ListItem(
		colors = ListItemDefaults.colors(containerColor = Color.Transparent),
		enabled = enabled,
		onClick = onPick,
		leadingContent = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
		content = {
			Text(
				listOf(row.title, row.disambiguation.takeIf { it.isNotBlank() }?.let { "($it)" }.orEmpty())
					.filter { it.isNotBlank() }.joinToString(" "),
				maxLines = 2,
				overflow = TextOverflow.Ellipsis
			)
		},
		supportingContent = if (detail.isBlank()) null else {
			{ Text(detail) }
		},
		trailingContent = if (!row.current) null else {
			{
				Text(
					stringResource(Res.string.label_mbid_match_current),
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.primary
				)
			}
		}
	)
}

/**
 * lb-bot's preview: the release, its three counts, and the from → to table. Files that stay as
 * they are fold away behind one line, so the rows on screen are the ones an apply touches.
 */
@Composable
private fun PreviewBlock(preview: LbAlbumPreview, summary: AlbumPreviewSummary) {
	val release: LbPreviewRelease = preview.release
	val line = releaseLine(
		release.title,
		release.disambiguation,
		release.year,
		release.country,
		release.format,
		release.trackCount,
		release.discCount
	)
	if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
	Text(
		preview.releaseMbid,
		style = MaterialTheme.typography.labelSmall,
		color = MaterialTheme.colorScheme.onSurfaceVariant
	)

	Text(
		pluralStringResource(Res.plurals.info_album_match_changing, summary.changing, summary.changing),
		style = MaterialTheme.typography.bodyMedium
	)
	Text(
		pluralStringResource(Res.plurals.info_album_match_no_slot, summary.noSlot, summary.noSlot),
		style = MaterialTheme.typography.bodyMedium
	)
	Text(
		pluralStringResource(Res.plurals.info_album_match_missing, summary.missing, summary.missing),
		style = MaterialTheme.typography.bodyMedium
	)
	if (summary.noSlot > 0) {
		// Said before the user applies: what a file with no slot turns into.
		Text(
			stringResource(Res.string.info_album_match_no_slot_note),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.error
		)
	}

	val multiDisc = remember(preview) { previewMultiDisc(preview) }
	var showUnchanged by remember(preview) { mutableStateOf(false) }
	preview.files.forEach { file ->
		val unchanged = file.to != null && !file.changed
		if (!unchanged || showUnchanged) PreviewFileRow(file, multiDisc, dimmed = unchanged)
	}
	if (summary.unchanged > 0 && !showUnchanged) {
		TextButton(onClick = { showUnchanged = true }) {
			Text(
				pluralStringResource(
					Res.plurals.action_album_match_show_unchanged,
					summary.unchanged,
					summary.unchanged
				)
			)
		}
	}

	if (preview.missing.isNotEmpty()) {
		SheetLabel(stringResource(Res.string.title_album_match_missing))
		preview.missing.forEach { slot ->
			SlotLine(slotLabel(slot, multiDisc), slot.title, MaterialTheme.colorScheme.onSurfaceVariant)
		}
	}
}

/** One file: what it is tagged as, and under it the slot it would take — or that it has none. */
@Composable
private fun PreviewFileRow(file: LbPreviewFile, multiDisc: Boolean, dimmed: Boolean) {
	val to = file.to
	Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
		when {
			// Already right: one dimmed line, since from and to are the same.
			dimmed && to != null ->
				SlotLine(slotLabel(to, multiDisc), to.title, MaterialTheme.colorScheme.onSurfaceVariant)

			to == null -> {
				SlotLine(slotLabel(file.from, multiDisc), file.from.title, MaterialTheme.colorScheme.onSurface)
				SlotLine("", stringResource(Res.string.info_album_match_no_place), MaterialTheme.colorScheme.error)
			}

			else -> {
				SlotLine(slotLabel(file.from, multiDisc), file.from.title, MaterialTheme.colorScheme.onSurfaceVariant)
				SlotLine("→ " + slotLabel(to, multiDisc), to.title, MaterialTheme.colorScheme.onSurface)
			}
		}
	}
}

@Composable
private fun SlotLine(number: String, title: String, color: Color) {
	Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Text(
			number,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			textAlign = TextAlign.End,
			maxLines = 1,
			modifier = Modifier.width(64.dp)
		)
		Spacer(Modifier.width(10.dp))
		Text(
			title,
			style = MaterialTheme.typography.bodyMedium,
			color = color,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f)
		)
	}
}

/**
 * The cover step. "Keep the current cover" comes first and is the default; the candidates are
 * public Cover Art Archive images, loaded straight from the Archive — through [RemoteCoverArt],
 * which sends none of the headers the user's own server gets.
 */
@Composable
private fun CoverGrid(
	covers: List<LbAlbumCover>,
	currentCoverArtId: String?,
	chosen: String?,
	enabled: Boolean,
	onChoose: (String?) -> Unit
) {
	// null is "keep"; the rest are the candidates, in lb-bot's order.
	val tiles: List<LbAlbumCover?> = listOf<LbAlbumCover?>(null) + covers
	tiles.chunked(COVERS_PER_ROW).forEach { rowTiles ->
		Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			rowTiles.forEach { tile ->
				val selected = tile?.full == chosen
				val shape = RoundedCornerShape(10.dp)
				Column(
					modifier = Modifier
						.weight(1f)
						.clip(shape)
						.border(
							width = if (selected) 2.dp else 0.dp,
							color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
							shape = shape
						)
						.clickable(enabled = enabled) { onChoose(tile?.full) }
						.padding(4.dp),
					horizontalAlignment = Alignment.CenterHorizontally
				) {
					if (tile == null) {
						CoverArt(coverArtId = currentCoverArtId, modifier = Modifier.fillMaxWidth())
						Text(
							stringResource(Res.string.option_album_match_keep_cover),
							style = MaterialTheme.typography.labelSmall,
							textAlign = TextAlign.Center,
							maxLines = 2,
							overflow = TextOverflow.Ellipsis
						)
					} else {
						RemoteCoverArt(url = tile.thumb, modifier = Modifier.fillMaxWidth())
					}
				}
			}
			// Keep the last row's tiles the size of the others.
			repeat(COVERS_PER_ROW - rowTiles.size) { Spacer(Modifier.weight(1f)) }
		}
	}
}

/**
 * An apply or a remove being followed, then what it came to. While it runs there is nothing to
 * pick: the album is being rewritten. Once it is over the only way on is out, because the album
 * id this sheet was opened with may be dead.
 */
@Composable
private fun AlbumJobBlock(
	poll: AlbumPoll,
	after: AlbumAfter?,
	enabled: Boolean,
	undoing: Boolean,
	undoError: LbBotManager.LbError?,
	onUndo: (LbAlbumOverrideRow) -> Unit,
	onClose: () -> Unit
) {
	val row = poll.row
	val outcome = albumOutcome(poll)
	if (outcome == null) {
		when (poll.job) {
			AlbumJob.APPLY -> {
				// Progress is lb-bot's own `done` / `total`, and is not invented before it sent any.
				val total = row?.total ?: 0
				val done = row?.done ?: 0
				Text(
					if (total > 0) stringResource(Res.string.info_album_match_applying_count, done, total)
					else stringResource(Res.string.info_album_match_applying),
					style = MaterialTheme.typography.bodyMedium
				)
				if (total > 0) {
					LinearProgressIndicator(
						progress = { (done.toFloat() / total).coerceIn(0f, 1f) },
						modifier = Modifier.fillMaxWidth()
					)
				} else {
					LinearProgressIndicator(Modifier.fillMaxWidth())
				}
			}
			AlbumJob.REMOVE -> {
				Text(stringResource(Res.string.info_album_match_removing), style = MaterialTheme.typography.bodyMedium)
				LinearProgressIndicator(Modifier.fillMaxWidth())
			}
		}
		return
	}

	Text(
		when (outcome) {
			is AlbumOutcome.Wrote ->
				pluralStringResource(Res.plurals.info_album_match_wrote, outcome.files, outcome.files)
			is AlbumOutcome.WrotePartly -> pluralStringResource(
				Res.plurals.info_album_match_wrote_partly,
				outcome.total,
				outcome.done,
				outcome.total,
				outcome.failed
			)
			is AlbumOutcome.DidNotFinish -> pluralStringResource(
				Res.plurals.info_album_match_not_finished,
				outcome.total,
				outcome.done,
				outcome.total
			)
			AlbumOutcome.Removed -> stringResource(Res.string.info_album_match_removed)
			AlbumOutcome.NotRemoved -> stringResource(Res.string.info_album_match_not_removed)
			AlbumOutcome.Gone -> stringResource(Res.string.info_album_match_gone)
			AlbumOutcome.StillWorking -> stringResource(Res.string.info_album_match_still_working)
		},
		style = MaterialTheme.typography.bodyMedium
	)
	// A partial result names the files that failed, each with lb-bot's reason.
	if (outcome is AlbumOutcome.WrotePartly || outcome is AlbumOutcome.DidNotFinish) {
		FailureList(row?.failures.orEmpty())
	}
	// Said only of a change that landed with no id for the album: lb-bot's "" is Navidrome
	// not having indexed it again yet.
	val landed = poll.phase == AlbumPollPhase.DONE && poll.job == AlbumJob.APPLY
	if (landed && row?.albumId.isNullOrBlank()) {
		SheetCaption(stringResource(Res.string.info_album_match_not_indexed))
	}
	LbErrorLine(undoError)

	ActionRow(undoing) {
		Button(enabled = enabled, onClick = onClose) {
			Text(
				stringResource(
					when (after) {
						is AlbumAfter.Album -> Res.string.action_album_match_open_album
						AlbumAfter.Artist -> Res.string.action_album_match_to_artist
						AlbumAfter.Stay, null -> Res.string.action_close
					}
				)
			)
		}
		// A row left standing — an apply that failed part-way, a remove that did not finish —
		// can still be undone: lb-bot restores whatever it did write.
		if (poll.phase == AlbumPollPhase.FAILED && row != null) {
			TextButton(enabled = enabled, onClick = { onUndo(row) }) {
				Text(stringResource(Res.string.action_undo))
			}
		}
	}
}
