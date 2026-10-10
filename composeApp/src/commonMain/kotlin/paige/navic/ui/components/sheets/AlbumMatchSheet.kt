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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_album_match_apply
import navic.composeapp.generated.resources.action_album_match_apply_anyway
import navic.composeapp.generated.resources.action_album_match_open_album
import navic.composeapp.generated.resources.action_album_match_preview
import navic.composeapp.generated.resources.action_album_match_set_cover
import navic.composeapp.generated.resources.action_album_match_show_unchanged
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
import navic.composeapp.generated.resources.info_album_match_indexing
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
import navic.composeapp.generated.resources.info_album_match_restored_indexing
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
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalSnackBarState
import paige.navic.domain.manager.ALBUM_CODE_IN_PROGRESS
import paige.navic.domain.manager.ALBUM_CODE_NO_OVERRIDE
import paige.navic.domain.manager.ALBUM_STATE_FAILED
import paige.navic.domain.manager.AlbumAfter
import paige.navic.domain.manager.AlbumCommit
import paige.navic.domain.manager.AlbumEditionRow
import paige.navic.domain.manager.AlbumJob
import paige.navic.domain.manager.AlbumJobState
import paige.navic.domain.manager.AlbumMatchTarget
import paige.navic.domain.manager.AlbumOutcome
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
import paige.navic.domain.manager.albumCommit
import paige.navic.domain.manager.albumEditionRows
import paige.navic.domain.manager.albumJobInFlight
import paige.navic.domain.manager.AlbumCoversQuery
import paige.navic.domain.manager.albumCoversQuery
import paige.navic.domain.manager.albumCoversReadNeeded
import paige.navic.domain.manager.albumJobFailures
import paige.navic.domain.manager.albumJobInWay
import paige.navic.domain.manager.albumWaitingForIndex
import paige.navic.domain.manager.mergeJobFailures
import paige.navic.domain.manager.albumLookupFreeText
import paige.navic.domain.manager.albumLookupQuery
import paige.navic.domain.manager.albumOutcome
import paige.navic.domain.manager.albumTaggedAs
import paige.navic.domain.manager.forceAllowed
import paige.navic.domain.manager.mergeAnnounced
import paige.navic.domain.manager.mergeOutcome
import paige.navic.domain.manager.parseReleaseMbid
import paige.navic.domain.manager.previewMultiDisc
import paige.navic.domain.manager.previewSummary
import paige.navic.domain.manager.releaseGroupFor
import paige.navic.domain.manager.slotLabel
import paige.navic.ui.components.common.CoverArt
import paige.navic.ui.components.common.RemoteCoverArt
import paige.navic.ui.navigation.Screen
import paige.navic.ui.util.rememberCoverAmbient

/** What the sheet knows of the album, from `GET /lb/album/override`. */
private sealed interface AlbumLoad {
	data object Loading : AlbumLoad
	data class Failed(val error: LbBotManager.LbError) : AlbumLoad
	data class Loaded(val state: LbAlbumState) : AlbumLoad
}

/** Which of the sheet's writes is out, so its spinner and its refusal sit beside its own button. */
private enum class AlbumAction { COMMIT, UNDO }

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
 *
 * **The job is not the sheet's.** An apply or a remove is sent, followed and settled by
 * [LbBotManager] in its own scope; the sheet only observes `albumJobs`. Swiping it away stops
 * nothing: lb-bot's answer is still acted on — the album's page leaves its dead id, the local
 * library forgets it, and [AlbumMatchHost] says what was written — and a sheet opened again
 * finds the job where it is. Only the reads (the album's state, the editions, a preview, the
 * covers) run in the sheet's own scope and end with it.
 *
 * **A retag changes the album's Navidrome id**, so the id in [target] is dead once a change
 * has landed. The sheet is composed above the pages ([AlbumMatchHost]) precisely so that it can
 * go on saying what lb-bot wrote while the page beneath it is replaced.
 *
 * @param target the album the sheet was opened on: its Navidrome id, and the library's own
 *   names for it, shown until lb-bot has answered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumMatchSheet(
	target: AlbumMatchTarget,
	onDismissRequest: () -> Unit
) {
	val albumId = target.albumId
	val albumTitle = target.title
	val artistName = target.artistName
	val coverArtId = target.coverArtId
	val backStack = LocalNavStack.current
	val lbBot = koinInject<LbBotManager>()
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
	// The question the covers on screen answer, and the Retry count they were read at.
	var coversAnswered by remember(albumId) { mutableStateOf<AlbumCoversQuery?>(null) }
	var coversAnsweredReads by remember(albumId) { mutableStateOf(0) }
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

	// The job this sheet shows, by the override row's id: one the manager is already following
	// for this album as the sheet opens — which is how a sheet opened again on an id that has
	// since died still finds its job — and after that the one it starts or finds running. Held
	// by id, so the job goes on being shown once it has ended.
	val jobs by lbBot.albumJobs.collectAsState()
	var followed by remember(albumId) {
		mutableStateOf(albumJobInWay(lbBot.albumJobs.value.values, albumId)?.rowId)
	}
	val job = followed?.let { jobs[it] }

	val state = (load as? AlbumLoad.Loaded)?.state

	fun follow(rowId: String) {
		followed = rowId
		commitError = null
		undoError = null
	}

	// What the album is now.
	LaunchedEffect(albumId, reads) {
		if (load !is AlbumLoad.Loaded) load = AlbumLoad.Loading
		when (val result = lbBot.albumOverrideState(albumId)) {
			is LbBotManager.LbResult.Ok -> {
				load = AlbumLoad.Loaded(result.value)
				// A job already running — started on another client, or before a restart — is
				// followed, not shown as a row frozen mid-way. One this device already follows
				// is simply joined.
				val running = albumJobInFlight(result.value)
				val rowId = result.value.override?.id.orEmpty()
				if (running != null && rowId.isNotBlank()) {
					lbBot.followAlbumJob(running, rowId, albumId, target.artistId, seen = result.value)
					follow(rowId)
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
		// What was read is thrown away with it: the next question is asked, even the same one.
		coversAnswered = null
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

	// The covers on offer, keyed on WHAT IS ASKED (`albumCoversQuery`), so they are read exactly
	// when the question changes: for the release the album is on as soon as the sheet knows it
	// — the read that was missing while this was gated on a preview, which only a pick makes —
	// and for a picked release once its preview has landed, so the two slow requests do not
	// take two of the hub's four slots at once. A release that is not one of the listed
	// editions — a pasted id — is asked for by itself (`albumCoversRgid`).
	val listedEditions = remember(detail) {
		albumEditionRows(detail?.releases.orEmpty(), "").map { it.releaseMbid }
	}
	val coversQuery = albumCoversQuery(
		picked = picked,
		previewedReleaseMbid = preview?.releaseMbid,
		currentReleaseMbid = state?.releaseMbid.orEmpty(),
		// Before the album is read nothing is settled, whatever a previous read left behind.
		rgid = if (state == null) null else rgid,
		listed = listedEditions,
		// A picked release's question includes whether it is a listed edition, so it waits
		// for the list: a pasted id is then asked for once, not once by itself and again
		// with the group when the editions land.
		editionsSettled = !editionsLoading && rgid != null
	)
	LaunchedEffect(albumId, coversQuery, coverReads) {
		val asked = coversQuery
		// One question, one read (`albumCoversReadNeeded`): coming back to the question already
		// answered — an editions Retry takes the group away and brings the same one back —
		// keeps its answer; only this step's own Retry asks it again.
		if (asked == null || !albumCoversReadNeeded(asked, coversAnswered, retry = coverReads != coversAnsweredReads)) {
			// Nothing to ask (yet): a read this replaced was cancelled before it could say so.
			coversLoading = false
			return@LaunchedEffect
		}
		// Another question: what was read for the last one is not its answer.
		covers = null
		coversAnswered = null
		coversLoading = true
		coversError = null
		when (val result = lbBot.albumCovers(asked.rgid, asked.releaseMbid)) {
			is LbBotManager.LbResult.Ok -> {
				covers = result.value
				coversAnswered = asked
			}
			is LbBotManager.LbResult.Failed ->
				if (result.error == LbBotManager.LbError.RouteUnknown) older = true else coversError = result.error
		}
		coversAnsweredReads = coverReads
		coversLoading = false
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
		// shows is behind. A job this device follows for the album is shown at once; otherwise
		// the album is read again, and a job found running then is followed.
		val code = (error as? LbBotManager.LbError.Rejected)?.code
		val running = albumJobInWay(lbBot.albumJobs.value.values, albumId)
		when {
			code == ALBUM_CODE_IN_PROGRESS && running != null -> follow(running.rowId)
			code == ALBUM_CODE_IN_PROGRESS || code == ALBUM_CODE_NO_OVERRIDE -> reads++
		}
	}

	fun apply(releaseMbid: String, confirmed: Boolean) {
		if (working) return
		// `force` only for the release whose `poor_match` the user has just confirmed.
		val force = confirmed && forceAllowed(poorMatch, releaseMbid)
		scope.launch {
			busy = AlbumAction.COMMIT
			commitError = null
			poorMatch = null
			when (val result = lbBot.applyAlbumOverride(albumId, releaseMbid, cover, force, target.artistId)) {
				is LbBotManager.LbResult.Ok -> when (val answer = result.value) {
					// The manager is following it already; the sheet only shows it.
					is LbAlbumApply.Started -> follow(answer.id)
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
				is LbBotManager.LbResult.Ok -> coverSaved = true
				is LbBotManager.LbResult.Failed -> refused(AlbumAction.COMMIT, result.error)
			}
			busy = null
		}
	}

	// [deadId] is the album id the undo kills: the album's CURRENT one. For the row read with
	// the album that is the id this sheet is open on; for a row the job block shows it is the
	// row's own `albumId` — the sheet may still be open on the id the album had before the
	// apply, which is exactly the id an undo brings it back to, and must not be forgotten.
	fun undo(row: LbAlbumOverrideRow, deadId: String) {
		if (working || row.id.isBlank()) return
		scope.launch {
			busy = AlbumAction.UNDO
			undoError = null
			when (val result = lbBot.removeAlbumOverride(row.id, deadId, target.artistId)) {
				is LbBotManager.LbResult.Ok -> follow(row.id)
				is LbBotManager.LbResult.Failed -> refused(AlbumAction.UNDO, result.error)
			}
			busy = null
		}
	}

	// "Open the album": only where lb-bot named the album's new id, and only when the page on
	// top is not that album already — the album's own page will have gone there by itself.
	fun openAlbum(newAlbumId: String) {
		onDismissRequest()
		val onTop = (backStack.lastOrNull() as? Screen.CollectionDetail)?.collectionId
		if (onTop != newAlbumId) backStack.add(Screen.CollectionDetail(newAlbumId, "library"))
	}

	ModalBottomSheet(
		onDismissRequest = onDismissRequest,
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
			val shown = job
			// The page on top, by the id it shows: once the album's own page has gone to the new
			// id there is nothing left for "Open the album" to do.
			val albumOnTop = (backStack.lastOrNull() as? Screen.CollectionDetail)?.collectionId
			when {
				// One muted sentence, never an error: this lb-bot simply has no such routes yet.
				older -> SheetCaption(stringResource(Res.string.info_album_match_older))

				// Before anything read about the album: a job is what is happening to it, and
				// the id this sheet was opened on may be one lb-bot no longer knows.
				shown != null -> AlbumJobBlock(
					job = shown,
					albumOnTop = albumOnTop,
					enabled = !working,
					undoing = busy == AlbumAction.UNDO,
					undoError = undoError,
					// "" on a `failed` row, and then nothing is forgotten.
					onUndo = { row -> undo(row, row.albumId) },
					onOpenAlbum = { openAlbum(it) },
					onClose = onDismissRequest
				)

				current is AlbumLoad.Loading -> Spinner()

				current is AlbumLoad.Failed -> {
					LbErrorLine(current.error)
					TextButton(onClick = { reads++ }) { Text(stringResource(Res.string.action_retry)) }
				}

				current is AlbumLoad.Loaded && coverSaved -> {
					Text(stringResource(Res.string.info_album_match_cover_saved), style = MaterialTheme.typography.bodyMedium)
					Button(onClick = onDismissRequest) { Text(stringResource(Res.string.action_close)) }
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
							onUndo = { undo(row, albumId) }
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

					// The cover step, drawn in one of two places: under the preview of a picked
					// release, or — with nothing picked — on its own, for the release the album
					// is on. "Keep the current cover" is the grid's first tile and the default.
					val coverStep: @Composable () -> Unit = {
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
					}

					// As the sheet opens: the covers are there before any edition is touched, and
					// the cover can be changed on its own — the release and the tags stay.
					if (picked.isBlank() && coversQuery != null) {
						coverStep()
						cover?.let { chosen ->
							Text(
								stringResource(Res.string.info_album_match_commit_cover),
								style = MaterialTheme.typography.bodyMedium
							)
							ActionRow(busy == AlbumAction.COMMIT) {
								Button(enabled = !working, onClick = { saveCover(chosen) }) {
									Text(stringResource(Res.string.action_album_match_set_cover))
								}
							}
						}
					}

					preview?.let { previewed ->
						val summary = remember(previewed) { previewSummary(previewed) }
						PreviewBlock(previewed, summary)

						coverStep()

						val chosenCover = cover
						val commit = albumCommit(loaded.releaseMbid, previewed.releaseMbid, summary.changing, chosenCover != null)
						// Shown, and confirmable, only over the release it was answered for.
						val question = poorMatch?.takeIf { forceAllowed(it, previewed.releaseMbid) }
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
											onClick = { apply(previewed.releaseMbid, confirmed = true) }
										) { Text(stringResource(Res.string.action_album_match_apply_anyway)) }
										TextButton(enabled = !working, onClick = { poorMatch = null }) {
											Text(stringResource(Res.string.action_cancel))
										}
									}
								} else {
									ActionRow(busy == AlbumAction.COMMIT) {
										Button(
											enabled = !working,
											onClick = { apply(previewed.releaseMbid, confirmed = false) }
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

/** What lb-bot could not write or put back — a file, or the cover — each with its own reason. */
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

/** What an ended job is said to have come to. Every number is a field of lb-bot's last answer. */
private fun outcomeText(outcome: AlbumOutcome): LbSentence = when (outcome) {
	is AlbumOutcome.Wrote ->
		LbSentence(plural = Res.plurals.info_album_match_wrote, quantity = outcome.files, args = listOf(outcome.files))
	// Files only. lb-bot's `failures` are counted apart, as problems: one of them may be the
	// cover, which is not a file that "could not be written" (ruling X9).
	is AlbumOutcome.WrotePartly -> LbSentence(
		plural = Res.plurals.info_album_match_wrote_partly,
		quantity = outcome.total,
		args = listOf(outcome.done, outcome.total)
	)
	is AlbumOutcome.DidNotFinish -> LbSentence(
		plural = Res.plurals.info_album_match_not_finished,
		quantity = outcome.total,
		args = listOf(outcome.done, outcome.total)
	)
	AlbumOutcome.Removed -> LbSentence(string = Res.string.info_album_match_removed)
	AlbumOutcome.NotRemoved -> LbSentence(string = Res.string.info_album_match_not_removed)
	AlbumOutcome.Gone -> LbSentence(string = Res.string.info_album_match_gone)
	AlbumOutcome.StillWorking -> LbSentence(string = Res.string.info_album_match_still_working)
}

/**
 * Where "Fix album match" lives: composed ONCE, above the pages (`App.kt`), on the album
 * [LbBotManager.albumMatch] names.
 *
 * Above the pages because a retag changes the album's Navidrome id: when lb-bot finishes, the
 * album's own page leaves the dead id, and a sheet composed inside that page would go with it
 * — taking what lb-bot wrote, and which files it could not write, with it unread.
 *
 * It also speaks for a job nobody is watching. The job outlives the sheet, so one that ends
 * after the sheet was swiped away is said in a snackbar, in the same words the sheet would use.
 * An artist name merge (I-023) is said from here too: this is the one composable that is
 * always there to say it.
 */
@Composable
fun AlbumMatchHost() {
	val lbBot = koinInject<LbBotManager>()
	val snackBarState = LocalSnackBarState.current
	val open by lbBot.albumMatch.collectAsState()

	LaunchedEffect(Unit) {
		lbBot.albumJobEnded.collect { job ->
			// A sheet open on this album is already showing the same outcome.
			if (lbBot.albumMatch.value?.albumId == job.albumId) return@collect
			val outcome = albumOutcome(job.poll) ?: return@collect
			// With how many problems lb-bot listed: the list itself is in the sheet.
			val said = outcomeText(outcome).withProblems(albumJobFailures(job.poll).size)
			snackBarState.showSnackbar(said.resolve())
		}
	}
	// The same for an artist name merge (I-023), whose block lives in the artist's own sheet:
	// said here unless a block that will still be there is showing it (`mergeAnnounced`).
	LaunchedEffect(Unit) {
		lbBot.mergeJobEnded.collect { job ->
			if (!mergeAnnounced(job, lbBot.mergeBlocksOn)) return@collect
			val outcome = mergeOutcome(job.poll) ?: return@collect
			val said = mergeOutcomeSentence(outcome).withProblems(mergeJobFailures(job.poll).size)
			snackBarState.showSnackbar(said.resolve())
		}
	}

	open?.let { target ->
		// Keyed on the album: another album is another sheet, with none of this one's state.
		key(target.albumId) {
			AlbumMatchSheet(target = target, onDismissRequest = { lbBot.closeAlbumMatch() })
		}
	}
}

/**
 * An apply or a remove being followed, then what it came to. While it runs there is nothing to
 * pick: the album is being rewritten. The job is the manager's — closing the sheet here leaves
 * it running — so the sheet's own button only ever closes it, or opens the album where lb-bot
 * says it is now.
 */
@Composable
private fun AlbumJobBlock(
	job: AlbumJobState,
	albumOnTop: String?,
	enabled: Boolean,
	undoing: Boolean,
	undoError: LbBotManager.LbError?,
	onUndo: (LbAlbumOverrideRow) -> Unit,
	onOpenAlbum: (String) -> Unit,
	onClose: () -> Unit
) {
	val poll = job.poll
	val row = poll.row
	val outcome = albumOutcome(poll)
	if (outcome == null) {
		when (poll.job) {
			AlbumJob.APPLY -> {
				// Progress is lb-bot's own `done` / `total`, and is not invented before it sent any.
				val total = row?.total ?: 0
				val done = row?.done ?: 0
				// Every file dealt with and the row still `applying`: lb-bot is waiting for
				// Navidrome to index the album again, for a minute at most. Said as a wait —
				// "11 of 12" on a bar that has stopped reads as stuck (`albumWaitingForIndex`).
				val waiting = albumWaitingForIndex(row)
				Text(
					when {
						waiting -> stringResource(Res.string.info_album_match_indexing)
						total <= 0 -> stringResource(Res.string.info_album_match_applying)
						else -> stringResource(Res.string.info_album_match_applying_count, done, total)
					},
					style = MaterialTheme.typography.bodyMedium
				)
				if (!waiting && total > 0) {
					LinearProgressIndicator(
						progress = { (done.toFloat() / total).coerceIn(0f, 1f) },
						modifier = Modifier.fillMaxWidth()
					)
				} else {
					LinearProgressIndicator(Modifier.fillMaxWidth())
				}
			}
			AlbumJob.REMOVE -> {
				Text(
					stringResource(
						if (albumWaitingForIndex(row)) Res.string.info_album_match_restored_indexing
						else Res.string.info_album_match_removing
					),
					style = MaterialTheme.typography.bodyMedium
				)
				LinearProgressIndicator(Modifier.fillMaxWidth())
			}
		}
		// Closing is always possible: the job goes on without the sheet.
		TextButton(onClick = onClose) { Text(stringResource(Res.string.action_close)) }
		return
	}

	Text(outcomeText(outcome).text(), style = MaterialTheme.typography.bodyMedium)
	// lb-bot's reasons, wherever the job ended with some — a remove that did not finish too:
	// its reason is what says "undo again" (`albumJobFailures`).
	FailureList(albumJobFailures(poll))
	// Said only of a change that landed with no id for the album: lb-bot's "" is Navidrome
	// not having indexed it again yet.
	val landed = poll.phase == AlbumPollPhase.DONE && poll.job == AlbumJob.APPLY
	if (landed && row?.albumId.isNullOrBlank()) {
		SheetCaption(stringResource(Res.string.info_album_match_not_indexed))
	}
	LbErrorLine(undoError)

	// Null until the local library has been settled; only then is the new album's page there.
	val moved = (job.after as? AlbumAfter.Album)?.albumId
	ActionRow(undoing) {
		if (moved != null && moved != albumOnTop) {
			Button(enabled = enabled, onClick = { onOpenAlbum(moved) }) {
				Text(stringResource(Res.string.action_album_match_open_album))
			}
			TextButton(enabled = enabled, onClick = onClose) { Text(stringResource(Res.string.action_close)) }
		} else {
			Button(enabled = enabled, onClick = onClose) { Text(stringResource(Res.string.action_close)) }
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
