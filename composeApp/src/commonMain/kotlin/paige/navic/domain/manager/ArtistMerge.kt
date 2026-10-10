package paige.navic.domain.manager

import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/*
 * "Merge into one name" (I-023): the wire shapes and the rules, kept free of the network, Room
 * and Compose so they can be run in a test (`ArtistMergeTest`). [LbBotManager] makes the calls
 * and follows a job to its end; the merge block in `MbidMatchSheet` draws what these decide.
 *
 * Navidrome keys artists by NAME, so "Belle & Sebastian" and "Belle and Sebastian" are two
 * artists even when both carry one MusicBrainz id. lb-bot retags the variant spelling's files to
 * the kept name — and keeps the previous tags, so it can be undone — when both entries resolve
 * to the same MusicBrainz artist. Folders are not renamed; what changes is what Navidrome lists.
 *
 * Feishin's `artist-merge.ts` is this file's twin, mirrored by hand.
 */

// ----- wire shapes --------------------------------------------------------------------------
// Field names mirror the wire verbatim; every field is defaulted.

/**
 * One side of a merge, by Navidrome id and by name. On a merge row the `from` side's id is the
 * rewritten spelling's **at the time of the merge** — dead once Navidrome has re-indexed — sent
 * so that a device that did not start the merge can tell which local artist to read again. An
 * lb-bot from before that field sends the name alone, and the id is then "".
 */
@Serializable
data class LbMergeArtist(
	val ndArtistId: String = "",
	val name: String = ""
)

@Serializable
data class LbMergeSample(
	val album: String = "",
	val tracks: Int = 0
)

/**
 * `GET /lb/artist/merge/preview`: what merging [from] into [into] would retag — [files] files
 * in [albums] albums — with at most ten of the albums as a [sample]. [into] is the entry whose
 * name is kept.
 */
@Serializable
data class LbMergePreview(
	val into: LbMergeArtist = LbMergeArtist(),
	val from: LbMergeArtist = LbMergeArtist(),
	val mbid: String = "",
	val albums: Int = 0,
	val files: Int = 0,
	val sample: List<LbMergeSample> = emptyList()
)

/** A file lb-bot could not retag, named by album and title: no path ever goes on the wire. */
@Serializable
data class LbMergeFailure(
	val album: String = "",
	val title: String = "",
	val reason: String = ""
)

/**
 * "lb-bot rewrote the files of [from] to the name of [into]". [state] is `merging`, `merged`,
 * `failed` or `undoing`; [done] / [total] the files written and to write. A row can be `merged`
 * WITH [failures]: a partial result, and the old spelling is then still an artist of its own.
 */
@Serializable
data class LbMergeRow(
	val id: String = "",
	val state: String = "",
	val into: LbMergeArtist = LbMergeArtist(),
	val from: LbMergeArtist = LbMergeArtist(),
	val mbid: String = "",
	val done: Int = 0,
	val total: Int = 0,
	val failures: List<LbMergeFailure> = emptyList(),
	val setAt: Double = 0.0
)

/** What `POST /lb/artist/merge` and `…/merge/undo` answer: the row to follow, and its state. */
@Serializable
data class LbMergeWrite(
	val ok: Boolean = false,
	val id: String = "",
	val state: String = ""
)

/**
 * One entry of a same-MBID group, and how many albums Navidrome files under it. [collab] is true
 * for a collaboration credit ("JPEGMAFIA × Danny Brown" beside "JPEGMAFIA"): not another
 * spelling of the artist, and never offered for a merge. Absent on the wire is false.
 */
@Serializable
data class LbSibling(
	val ndArtistId: String = "",
	val name: String = "",
	val albums: Int = 0,
	val collab: Boolean = false
)

/** Library artists whose effective MusicBrainz id is the same one: the spellings to merge. */
@Serializable
data class LbSiblingGroup(
	val mbid: String = "",
	val artists: List<LbSibling> = emptyList()
)

const val MERGE_STATE_MERGING = "merging"
const val MERGE_STATE_MERGED = "merged"
const val MERGE_STATE_FAILED = "failed"
const val MERGE_STATE_UNDOING = "undoing"

/** lb-bot's `code` for a merge id it does not hold: how a poll learns an undo finished. */
const val MERGE_CODE_NO_MERGE = "no_merge"

// ----- reading an answer --------------------------------------------------------------------
// As the album routes: the HTTP status and the body in, what the route said out. The refusals
// are the same shape (`albumRefused`): a bare 404 or a 405 is an lb-bot older than the hub, a
// 404 with a code, a 409 (`different_artists`, `busy`, `in_progress`) and a 502 with `retryable`
// are lb-bot's own answers in its own words.

/** `GET /lb/artist/merge/preview`. A preview that names no pair counts nothing. */
internal fun mergePreviewAnswer(status: Int, raw: String): LbBotManager.LbResult<LbMergePreview> {
	if (!isSuccess(status)) return albumRefused(status, raw)
	return albumBody(raw, serializer<LbMergePreview>()) {
		it.into.ndArtistId.isNotBlank() && it.from.ndArtistId.isNotBlank()
	}
}

/** `POST /lb/artist/merge` and `POST /lb/artist/merge/undo`: done only when it says `ok` and names the row. */
internal fun mergeWriteAnswer(status: Int, raw: String): LbBotManager.LbResult<LbMergeWrite> {
	if (!isSuccess(status)) return albumRefused(status, raw)
	return albumBody(raw, serializer<LbMergeWrite>(), WRITE_NOT_CONFIRMED) { it.ok && it.id.isNotBlank() }
}

/** `GET /lb/artist/merge?id=`. */
internal fun mergeRowAnswer(status: Int, raw: String): LbBotManager.LbResult<LbMergeRow> {
	if (!isSuccess(status)) return albumRefused(status, raw)
	return albumBody(raw, serializer<LbMergeRow>()) { it.id.isNotBlank() }
}

// ----- which spellings, and which name ------------------------------------------------------

/**
 * The group that holds this artist, or null: no list from lb-bot at all (an older one), no id,
 * or no group naming it. No group, no merge block.
 */
internal fun siblingGroupFor(siblings: List<LbSiblingGroup>?, ndId: String): LbSiblingGroup? =
	if (ndId.isBlank()) null
	else siblings?.firstOrNull { group -> group.artists.any { it.ndArtistId == ndId } }

/**
 * The other spellings in the group, in lb-bot's order. A row with no id cannot be merged with,
 * and this artist is never its own "other".
 *
 * **A collaboration credit is not a spelling** (ruling X5): an entry lb-bot marks `collab` is
 * never offered, and when this artist's OWN entry is one there is nothing to offer at all — so
 * a group that is one artist plus its collaboration credits shows no merge block on any of its
 * pages, where lb-bot would only refuse (409 `collaboration`).
 */
internal fun mergeOthers(group: LbSiblingGroup?, ndId: String): List<LbSibling> {
	val artists = group?.artists.orEmpty()
	if (artists.any { it.ndArtistId == ndId && it.collab }) return emptyList()
	return artists
		.filter { it.ndArtistId.isNotBlank() && it.ndArtistId != ndId && !it.collab }
		.distinctBy { it.ndArtistId }
}

/** The name kept unless the user says otherwise: the spelling with the most albums, the first on a tie. */
internal fun mergeDefaultKeep(pair: List<LbSibling>): String {
	var best: LbSibling? = null
	for (artist in pair) {
		if (best == null || artist.albums > best.albums) best = artist
	}
	return best?.ndArtistId.orEmpty()
}

/**
 * A merge as lb-bot is asked for it. **[ndId] is the entry whose name is KEPT; [fromNdId] the
 * spelling whose files are rewritten.**
 */
data class MergePlan(val ndId: String, val fromNdId: String)

/**
 * [thisId] is the artist the sheet is open on, [otherId] the spelling picked to merge with it,
 * and [keepId] whichever of the two names the user keeps. Keeping the OTHER spelling's name
 * swaps the pair: it is then this artist's files that are rewritten. Null unless there are two
 * different artists and the kept name is one of them — a swapped or half-formed pair must never
 * reach lb-bot.
 */
internal fun planMerge(thisId: String, otherId: String, keepId: String): MergePlan? = when {
	thisId.isBlank() || otherId.isBlank() || thisId == otherId -> null
	keepId == thisId -> MergePlan(ndId = thisId, fromNdId = otherId)
	keepId == otherId -> MergePlan(ndId = otherId, fromNdId = thisId)
	else -> null
}

// ----- the merge / undo poll ----------------------------------------------------------------
// The shared job poll (`LbJobPoll.kt`) in the merge's vocabulary: `GET /lb/artist/merge?id=`
// every two seconds, until the row has not moved for three minutes.

enum class MergeJob { MERGE, UNDO }

/** A merge: `merging` → `merged` | `failed`. The row vanishing is its own ending. */
private val MERGE_SPEC = LbJobSpec(
	active = MERGE_STATE_MERGING,
	settled = setOf(MERGE_STATE_MERGED),
	failed = setOf(MERGE_STATE_FAILED),
	failedNeedsActive = false,
	goneIsDone = false
)

/** An undo: `undoing`, then the row is deleted — 404 `no_merge` is the finish. */
private val UNDO_SPEC = LbJobSpec(
	active = MERGE_STATE_UNDOING,
	settled = emptySet(),
	failed = setOf(MERGE_STATE_MERGED, MERGE_STATE_FAILED),
	failedNeedsActive = true,
	goneIsDone = true
)

private fun mergeSpec(job: MergeJob): LbJobSpec = when (job) {
	MergeJob.MERGE -> MERGE_SPEC
	MergeJob.UNDO -> UNDO_SPEC
}

/** What one tick of the poll read. */
sealed interface MergePollRead {
	data class Row(val row: LbMergeRow) : MergePollRead

	/** 404 `no_merge`: lb-bot holds no such merge (any more). */
	data object Gone : MergePollRead

	/** No answer. A failed poll is not one: it ends nothing. */
	data object Missed : MergePollRead
}

/** A merge or an undo being followed, or its ending. [row] is the last answer, kept across misses. */
data class MergePoll(
	val job: MergeJob,
	val startedAtMs: Long,
	val phase: LbJobPhase = LbJobPhase.POLLING,
	val row: LbMergeRow? = null,
	val sawActive: Boolean = false,
	val quietSinceMs: Long = startedAtMs
)

/**
 * [seen] is the row as already read, for a job found running (another client's, or after a
 * restart). [postState] is the `state` of the POST's own answer (ruling X1, as
 * [albumPollStart]): lb-bot flips the row to `undoing` before it answers, and can reach `failed`
 * before the first poll.
 */
internal fun mergePollStart(
	job: MergeJob,
	nowMs: Long,
	seen: LbMergeRow? = null,
	postState: String = ""
): MergePoll =
	MergePoll(
		job = job,
		startedAtMs = nowMs,
		row = seen,
		sawActive = seen?.state == mergeSpec(job).active || postState == mergeSpec(job).active
	)

/** The job lb-bot is already running on a row, if any. */
internal fun mergeJobInFlight(row: LbMergeRow): MergeJob? = when (row.state) {
	MERGE_STATE_MERGING -> MergeJob.MERGE
	MERGE_STATE_UNDOING -> MergeJob.UNDO
	else -> null
}

/**
 * A merge or an undo lb-bot is still working on that concerns [artistId] — as the kept name or
 * as the rewritten spelling — and that this device is not following: one to pick up, started on
 * another client or before this app was restarted. It is followed from where it is
 * ([mergePollStart]'s `seen`), so the artist's block shows the job instead of a form lb-bot
 * could only refuse.
 *
 * Decided on ids. lb-bot's row names both artists by id; an lb-bot from before
 * `from.ndArtistId` names the rewritten spelling by name only, and then only the kept artist's
 * block can pick the job up.
 *
 * What is left alone (ruling X3):
 * - a row still in [jobs], ended ones too — so a poll that gave up is not started again while
 *   its "still working" is on screen. Once that job has been dismissed, and lb-bot still
 *   reports it running, it may be picked up again;
 * - a merge this device already followed **to rest** ([finished], by [mergeRowKey]) — never
 *   again in this process. The list can go on saying `merging` for a merge that has ended (a
 *   copy of it up to a minute old), and picking that up showed a finished merge a second time.
 */
internal fun mergeToPickUp(
	controls: Boolean,
	merges: List<LbMergeRow>?,
	jobs: Map<String, MergeJobState>,
	artistId: String,
	finished: Set<String> = emptySet()
): LbMergeRow? =
	if (!controls || artistId.isBlank()) {
		null
	} else {
		merges?.firstOrNull { row ->
			row.id.isNotBlank() &&
				(row.into.ndArtistId == artistId || row.from.ndArtistId == artistId) &&
				mergeJobInFlight(row) != null && row.id !in jobs && mergeRowKey(row) !in finished
		}
	}

/**
 * One merge as lb-bot's rows name it across time: `id@setAt`. The id alone is not enough — it
 * is lb-bot's to mint, and a later merge may carry the same one.
 */
internal fun mergeRowKey(row: LbMergeRow): String = "${row.id}@${row.setAt}"

/**
 * The key ([mergeRowKey]) under which a job that **ended at rest** is remembered for the rest of
 * the process, or null (ruling X3). At rest is lb-bot's own verdict with a row to name it:
 * landed, failed, undone, not undone. A job this device gave up on is not at rest — lb-bot may
 * still be working — and may be picked up again.
 */
internal fun mergeFinishedKey(poll: MergePoll): String? = when (poll.phase) {
	LbJobPhase.DONE, LbJobPhase.FAILED -> poll.row?.let { mergeRowKey(it) }
	LbJobPhase.POLLING, LbJobPhase.GONE, LbJobPhase.TIMED_OUT -> null
}

/**
 * The merges that **finished on another device** and that the local library has not been
 * brought to agree with: `merged` rows that name the rewritten spelling's id
 * (`from.ndArtistId`), that this device is not following and never followed to rest ([jobs],
 * [finished]), and has not already re-read for ([resynced], by [mergeRowKey]). The caller then
 * checks that Room still holds that artist and reads it again — once per row per process.
 */
internal fun mergesToResync(
	merges: List<LbMergeRow>?,
	jobs: Map<String, MergeJobState>,
	finished: Set<String>,
	resynced: Set<String>
): List<LbMergeRow> = merges.orEmpty().filter { row ->
	val key = mergeRowKey(row)
	row.state == MERGE_STATE_MERGED && row.from.ndArtistId.isNotBlank() &&
		row.id !in jobs && key !in finished && key !in resynced
}

/** A poll's request as what it read. Only the row, or lb-bot's own `no_merge`, is an answer. */
internal fun mergePollRead(result: LbBotManager.LbResult<LbMergeRow>): MergePollRead = when (result) {
	is LbBotManager.LbResult.Ok -> MergePollRead.Row(result.value)
	is LbBotManager.LbResult.Failed -> {
		val error = result.error
		if (error is LbBotManager.LbError.Rejected && error.status == 404 && error.code == MERGE_CODE_NO_MERGE) {
			MergePollRead.Gone
		} else {
			MergePollRead.Missed
		}
	}
}

/** One tick ([lbJobStep]): a merge ends on `merged` or `failed`, an undo when the row is gone. */
internal fun mergePollStep(poll: MergePoll, read: MergePollRead, nowMs: Long): MergePoll {
	if (poll.phase != LbJobPhase.POLLING) return poll
	val heard = when (read) {
		MergePollRead.Missed -> LbJobRead.Missed
		MergePollRead.Gone -> LbJobRead.Gone
		is MergePollRead.Row -> LbJobRead.State(read.row.state, read.row.done)
	}
	val last = poll.row?.let { LbJobRead.State(it.state, it.done) }
	val next = lbJobStep(mergeSpec(poll.job), poll.phase, poll.sawActive, poll.quietSinceMs, last, heard, nowMs)
	return poll.copy(
		phase = next.phase,
		sawActive = next.sawActive,
		quietSinceMs = next.quietSinceMs,
		row = (read as? MergePollRead.Row)?.row ?: poll.row
	)
}

/** Every file is dealt with and lb-bot still holds the row `merging` or `undoing` ([lbWritesOver]). */
internal fun mergeWaitingForIndex(row: LbMergeRow?): Boolean =
	row != null && lbWritesOver(
		working = row.state == MERGE_STATE_MERGING || row.state == MERGE_STATE_UNDOING,
		done = row.done,
		total = row.total,
		failures = row.failures.size
	)

/**
 * lb-bot's reasons, where an ended job has any to read ([albumJobFailures]): a merge that landed
 * with failures or did not finish, and an undo that did not finish.
 */
internal fun mergeJobFailures(poll: MergePoll): List<LbMergeFailure> = when (poll.phase) {
	LbJobPhase.FAILED -> poll.row?.failures.orEmpty()
	LbJobPhase.DONE -> if (poll.job == MergeJob.MERGE) poll.row?.failures.orEmpty() else emptyList()
	LbJobPhase.POLLING, LbJobPhase.GONE, LbJobPhase.TIMED_OUT -> emptyList()
}

/**
 * Whether the ended job's row can be undone from where it is shown: a merge at rest — landed, or
 * failed part-way — and **an undo that did not finish**, which lb-bot expects to be pressed
 * again (to restore a file that is back, or to give up on it).
 */
internal fun mergeJobCanUndo(poll: MergePoll): Boolean {
	if (poll.row?.id.isNullOrBlank()) return false
	return when (poll.phase) {
		LbJobPhase.FAILED -> true
		LbJobPhase.DONE -> poll.job == MergeJob.MERGE
		LbJobPhase.POLLING, LbJobPhase.GONE, LbJobPhase.TIMED_OUT -> false
	}
}

/** What an ended poll has to say. Every number in it is a field of lb-bot's last answer. */
sealed interface MergeOutcome {
	/** `merged`, no failures. */
	data class Retagged(val files: Int) : MergeOutcome

	/** `merged` with failures: a partial result, and the old spelling still exists. */
	data class RetaggedPartly(val done: Int, val total: Int, val failed: Int) : MergeOutcome

	/** `failed`. Undo restores what was written. */
	data class DidNotFinish(val done: Int, val total: Int) : MergeOutcome

	/** The row is gone after an undo: the previous names are back. */
	data object Undone : MergeOutcome

	/** An undo that left the row standing. */
	data object NotUndone : MergeOutcome

	/** The row vanished under a merge. */
	data object Gone : MergeOutcome

	/** Three minutes without an ending. */
	data object StillWorking : MergeOutcome
}

/** Null while the poll is still running. */
internal fun mergeOutcome(poll: MergePoll): MergeOutcome? {
	val row = poll.row
	return when (poll.phase) {
		LbJobPhase.POLLING -> null
		LbJobPhase.TIMED_OUT -> MergeOutcome.StillWorking
		LbJobPhase.GONE -> MergeOutcome.Gone
		LbJobPhase.DONE -> when {
			poll.job == MergeJob.UNDO -> MergeOutcome.Undone
			row != null && row.failures.isNotEmpty() ->
				MergeOutcome.RetaggedPartly(row.done, row.total, row.failures.size)
			else -> MergeOutcome.Retagged(row?.done ?: 0)
		}
		LbJobPhase.FAILED -> when (poll.job) {
			MergeJob.UNDO -> MergeOutcome.NotUndone
			MergeJob.MERGE -> MergeOutcome.DidNotFinish(row?.done ?: 0, row?.total ?: 0)
		}
	}
}

// ----- the job, which is the manager's ------------------------------------------------------
// As for an album (`AlbumOverride.kt`): the job outlives the sheet that started it, and what
// follows its end is decided from the job alone.

/**
 * A merge or an undo this device is following, or one it has followed to its end.
 *
 * @property intoId the entry whose name is kept.
 * @property fromId the spelling being rewritten — **the id that dies when a merge lands**. ""
 *   only when nobody could say: a job picked up from a row of an lb-bot older than
 *   `from.ndArtistId`, which names that spelling by name alone.
 * @property intoName and [fromName]: the names as the starter knew them, shown until lb-bot's
 *   row names them itself.
 * @property endedAtMs when the job was over AND the local library had been brought to agree,
 *   on this device's clock; 0 until then.
 */
data class MergeJobState(
	val rowId: String,
	val intoId: String,
	val fromId: String,
	val intoName: String,
	val fromName: String,
	val poll: MergePoll,
	val endedAtMs: Long = 0L
) {
	/** Still polled, or still being settled. */
	val unfinished: Boolean get() = endedAtMs == 0L
}

/** What a second merge or undo is refused with while one is followed: nothing was sent to lb-bot. */
const val MERGE_JOB_RUNNING = "A merge of these artists is still running."

/** What the merge block shows. */
sealed interface MergeBlockView {
	/** No block: no other spelling, or no merge route. */
	data object Hidden : MergeBlockView

	/** Pick the spelling and the name to keep, read lb-bot's count, merge. */
	data object Form : MergeBlockView

	/** A job, running or over, between [from] (the rewritten spelling) and [into] (the kept one). */
	data class Job(val from: String, val into: String, val undo: Boolean) : MergeBlockView
}

/**
 * **The result must survive its own cause.** A finished merge leaves one spelling, and lb-bot's
 * `siblings` only lists groups of two or more — so the group that offered the merge disappears
 * *because the merge worked*. While a job concerns this artist ([job]) the block shows the job:
 * its outcome, the files that failed, Undo. Its names are the job's own — lb-bot's row once it
 * has answered, the names the job was started with until then — **never** the sibling list's.
 *
 * Without a job: the form, when the hub offers merging ([controls]) and the artist's group
 * holds another spelling ([hasOthers]); otherwise nothing.
 */
internal fun mergeBlockView(controls: Boolean, hasOthers: Boolean, job: MergeJobState?): MergeBlockView {
	if (job != null) {
		val row = job.poll.row
		return MergeBlockView.Job(
			from = row?.from?.name.orEmpty().ifBlank { job.fromName },
			into = row?.into?.name.orEmpty().ifBlank { job.intoName },
			undo = job.poll.job == MergeJob.UNDO
		)
	}
	return if (controls && hasOthers) MergeBlockView.Form else MergeBlockView.Hidden
}

/** The newest job that concerns [artistId], as the kept name or as the rewritten spelling. */
internal fun mergeJobFor(jobs: Collection<MergeJobState>, artistId: String): MergeJobState? =
	if (artistId.isBlank()) null
	else jobs.lastOrNull { it.intoId == artistId || it.fromId == artistId }

/**
 * The followed job that stands in the way of another merge or undo: one still unfinished that
 * touches any of [artistIds], or is the row [rowId] itself. As for an album, **a second change
 * is refused, unsent**.
 */
internal fun mergeJobInWay(
	jobs: Collection<MergeJobState>,
	artistIds: Collection<String>,
	rowId: String = ""
): MergeJobState? {
	val ids = artistIds.filter { it.isNotBlank() }.toSet()
	return jobs.lastOrNull { job ->
		job.unfinished && (
			(rowId.isNotBlank() && job.rowId == rowId) ||
				job.intoId in ids || job.fromId in ids
			)
	}
}

/** What a screen does when a merge has ended. */
sealed interface MergeLeave {
	data object Stay : MergeLeave

	/** The page's artist was the rewritten spelling, whose id is dead: the kept artist's page. */
	data class ToArtist(val artistId: String) : MergeLeave
}

/**
 * Where a screen showing artist [shownArtistId] goes when [job] has ended. Feishin's
 * `decideMergeLeave`, decided on ids.
 *
 * Only a merge that LANDED kills an id — the rewritten spelling's — and only a page showing that
 * artist moves, to the kept one. An undo kills nothing: the old spelling comes back as an artist
 * of its own. Nothing moves before the job is over and the library settled, and a page opened
 * after that ([onScreenSinceMs] later than the ending) is left alone: a merge with files lb-bot
 * could not write leaves the old spelling an artist in its own right. [onScreenSinceMs] is when
 * the page was opened, not when it was last drawn — the screen keeps it with its back-stack
 * entry, so a page that was under another one when the merge ended still moves.
 *
 * A merge that ended `merged` WITH failures moves the page too, as Feishin's does: most of the
 * artist is under the kept name now, and what is left is reachable from the artist list.
 */
internal fun mergeLeave(job: MergeJobState, shownArtistId: String, onScreenSinceMs: Long): MergeLeave {
	val landed = !job.unfinished && job.poll.job == MergeJob.MERGE && job.poll.phase == LbJobPhase.DONE
	return when {
		!landed -> MergeLeave.Stay
		job.fromId.isBlank() || job.intoId.isBlank() || shownArtistId != job.fromId -> MergeLeave.Stay
		onScreenSinceMs > job.endedAtMs -> MergeLeave.Stay
		else -> MergeLeave.ToArtist(job.intoId)
	}
}

/**
 * Whether an ended job is said in a snackbar: yes, unless a merge block that will still be
 * there is already showing it. [blocksOn] are the artists whose block is on screen. A block on
 * the rewritten spelling's page does not count once the merge has landed — that page is about
 * to be left, and its block with it.
 */
internal fun mergeAnnounced(job: MergeJobState, blocksOn: Set<String>): Boolean {
	val landed = job.poll.job == MergeJob.MERGE && job.poll.phase == LbJobPhase.DONE
	val shownOnKept = job.intoId.isNotBlank() && job.intoId in blocksOn
	val shownOnRewritten = job.fromId.isNotBlank() && job.fromId in blocksOn && !landed
	return !(shownOnKept || shownOnRewritten)
}

/**
 * The artist whose albums the local library reads again once [poll] has ended — together with
 * the artist list — or **null for "read nothing"**.
 *
 * A merge moved the rewritten spelling's albums to the kept artist — some of them even when it
 * failed part-way — and an undo moved some of the kept artist's back. Room files an album under
 * the artist it had when it was last read, and the syncs that follow a retag do not re-read an
 * album whose id, track count and cover are unchanged. A job that said nothing (a timeout, a
 * vanished row) is no reason to read anything.
 *
 * **"" is not null.** It is a job that did change the library, by an artist this device cannot
 * name by id — a merge picked up from the row of an lb-bot older than `from.ndArtistId`, which
 * names the rewritten spelling by name alone. The artist list is then read on its own: which
 * artists the server still lists needs no id to ask.
 */
internal fun mergeResyncArtistId(poll: MergePoll, intoId: String, fromId: String): String? = when (poll.phase) {
	LbJobPhase.DONE, LbJobPhase.FAILED -> when (poll.job) {
		MergeJob.MERGE -> fromId
		MergeJob.UNDO -> intoId
	}
	LbJobPhase.POLLING, LbJobPhase.GONE, LbJobPhase.TIMED_OUT -> null
}
