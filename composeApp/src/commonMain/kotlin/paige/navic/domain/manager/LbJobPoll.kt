package paige.navic.domain.manager

/*
 * The poll that follows a background job on lb-bot, as one rule shared by every kind of job
 * (I-022 album override, I-023 artist name merge).
 *
 * lb-bot answers an apply, a remove, a merge and an undo at once and works in the background;
 * the job is then followed by its row's id, one short request per tick, until lb-bot's own state
 * ends it or the row has not moved for three minutes. What differs between the jobs is only the vocabulary —
 * which state means "working", which ends it well, which badly, and whether the row being gone
 * is the finish or a surprise — and that is an [LbJobSpec]. The reading, the row and the
 * sentences stay with each feature (`AlbumOverride.kt`, `ArtistMerge.kt`).
 *
 * Feishin's `PollSpec` / `pollStep` is the twin.
 */

/** How often a followed job is asked about. */
const val LB_JOB_POLL_INTERVAL_MS = 2_000L

/**
 * After this long **with the row not moving** the client stops asking and says lb-bot is still
 * working ([lbJobStep]).
 */
const val LB_JOB_GIVE_UP_MS = 180_000L

enum class LbJobPhase {
	POLLING,

	/** lb-bot's settled state (`applied`, `merged`), or the row gone where that is the finish. */
	DONE,

	/** lb-bot says the job failed, or a remove / an undo left the row standing. */
	FAILED,

	/** The row vanished where that is not the finish: undone from somewhere else meanwhile. */
	GONE,

	/** Three minutes passed. Nothing is known to have failed — lb-bot may still be working. */
	TIMED_OUT
}

/**
 * One kind of job, in lb-bot's own state strings.
 *
 * @property active lb-bot is working on it (`applying`, `removing`, `merging`, `undoing`).
 * @property settled states that end it well. Empty for a job whose finish is the row's
 *   disappearance.
 * @property failed states that end it badly.
 * @property failedNeedsActive a [failed] state only counts once [active] is known to have been
 *   reached. A remove or an undo starts from a row at rest, and a state read before lb-bot
 *   flipped it would be the one the job started from, not a verdict. "Known" is a poll that saw
 *   it, the row a running job was found with, or — ruling X1 — the POST's own answer, which
 *   names the state lb-bot put the row in before answering: a job that fails within the first
 *   two seconds is then still seen to fail.
 * @property goneIsDone the row being gone is the finish (a remove, an undo) rather than its own,
 *   unexpected, ending.
 */
data class LbJobSpec(
	val active: String,
	val settled: Set<String>,
	val failed: Set<String>,
	val failedNeedsActive: Boolean,
	val goneIsDone: Boolean
)

/** What one tick read, reduced to what the rule needs. */
sealed interface LbJobRead {
	/** lb-bot answered with the row, which is in [state] and has written [done] files. */
	data class State(val state: String, val done: Int = 0) : LbJobRead

	/** lb-bot holds no such row (any more). */
	data object Gone : LbJobRead

	/**
	 * No answer: the hub out of reach, a timeout, a 5xx, a body that is not a row. **A failed
	 * poll is not an answer** — it ends nothing and changes nothing but the clock.
	 */
	data object Missed : LbJobRead
}

/** Where a job stands after a tick. [quietSinceMs] is when its row last moved. */
data class LbJobProgress(val phase: LbJobPhase, val sawActive: Boolean, val quietSinceMs: Long)

/**
 * One tick. **An answer is judged before the clock**, so one that lands at the deadline still
 * counts; a missed poll moves nothing but the clock; an ended job stays ended; a state this
 * client has never heard of keeps the poll going.
 *
 * **The clock measures silence, not length** (ruling X2). [quietSinceMs] is when the row last
 * moved — when the job began, until it has — and it restarts whenever an answer's `state` or
 * `done` differs from the answer before it ([last]). A job that advances never times out; one
 * that stops advancing is given up on three minutes after it stopped. A large merge plus lb-bot's
 * wait for Navidrome can run past three minutes with nothing wrong. The first answer is not a
 * movement: there is nothing before it to differ from.
 */
internal fun lbJobStep(
	spec: LbJobSpec,
	phase: LbJobPhase,
	sawActive: Boolean,
	quietSinceMs: Long,
	last: LbJobRead.State?,
	read: LbJobRead,
	nowMs: Long
): LbJobProgress {
	if (phase != LbJobPhase.POLLING) return LbJobProgress(phase, sawActive, quietSinceMs)
	var saw = sawActive
	var quiet = quietSinceMs
	when (read) {
		LbJobRead.Missed -> Unit
		LbJobRead.Gone ->
			return LbJobProgress(if (spec.goneIsDone) LbJobPhase.DONE else LbJobPhase.GONE, saw, quiet)
		is LbJobRead.State -> {
			if (last != null && (read.state != last.state || read.done != last.done)) quiet = nowMs
			saw = saw || read.state == spec.active
			if (read.state in spec.settled) return LbJobProgress(LbJobPhase.DONE, saw, quiet)
			if (read.state in spec.failed && (saw || !spec.failedNeedsActive)) {
				return LbJobProgress(LbJobPhase.FAILED, saw, quiet)
			}
		}
	}
	return LbJobProgress(
		if (nowMs - quiet >= LB_JOB_GIVE_UP_MS) LbJobPhase.TIMED_OUT else LbJobPhase.POLLING,
		saw,
		quiet
	)
}

/**
 * **The wait for Navidrome reads as a wait** (ruling X4). lb-bot holds a row in its active state
 * after its last write until Navidrome has scanned, up to a minute. `done` counts successes
 * only, so "done == total" never comes when one file failed: the writes are over when successes
 * plus failures reach the total, and the row is still in an active state ([working]). The
 * surface then says the tags are written and lb-bot is waiting, with no "N of N" and no
 * determinate bar. Feishin's `awaitingIndex`.
 */
internal fun lbWritesOver(working: Boolean, done: Int, total: Int, failures: Int): Boolean =
	working && total > 0 && done + failures >= total
