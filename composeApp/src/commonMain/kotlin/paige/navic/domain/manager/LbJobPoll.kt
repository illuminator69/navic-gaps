package paige.navic.domain.manager

/*
 * The poll that follows a background job on lb-bot, as one rule shared by every kind of job
 * (I-022 album override, I-023 artist name merge).
 *
 * lb-bot answers an apply, a remove, a merge and an undo at once and works in the background;
 * the job is then followed by its row's id, one short request per tick, until lb-bot's own state
 * ends it or three minutes have passed. What differs between the jobs is only the vocabulary —
 * which state means "working", which ends it well, which badly, and whether the row being gone
 * is the finish or a surprise — and that is an [LbJobSpec]. The reading, the row and the
 * sentences stay with each feature (`AlbumOverride.kt`, `ArtistMerge.kt`).
 *
 * Feishin's `PollSpec` / `pollStep` is the twin.
 */

/** How often a followed job is asked about. */
const val LB_JOB_POLL_INTERVAL_MS = 2_000L

/** After this long the client stops asking and says lb-bot is still working. */
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
 * @property failedNeedsActive a [failed] state only counts once [active] has been seen. A remove
 *   or an undo can be polled before lb-bot has flipped the row, and the state read then is the
 *   one it started from, not a verdict.
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
	/** lb-bot answered with the row, which is in [state]. */
	data class State(val state: String) : LbJobRead

	/** lb-bot holds no such row (any more). */
	data object Gone : LbJobRead

	/**
	 * No answer: the hub out of reach, a timeout, a 5xx, a body that is not a row. **A failed
	 * poll is not an answer** — it ends nothing and changes nothing but the clock.
	 */
	data object Missed : LbJobRead
}

/** Where a job stands after a tick. */
data class LbJobProgress(val phase: LbJobPhase, val sawActive: Boolean)

/**
 * One tick. **An answer is judged before the clock**, so one that lands at the deadline still
 * counts; a missed poll moves nothing but the clock; an ended job stays ended; a state this
 * client has never heard of keeps the poll going.
 */
internal fun lbJobStep(
	spec: LbJobSpec,
	phase: LbJobPhase,
	sawActive: Boolean,
	startedAtMs: Long,
	read: LbJobRead,
	nowMs: Long
): LbJobProgress {
	if (phase != LbJobPhase.POLLING) return LbJobProgress(phase, sawActive)
	var saw = sawActive
	when (read) {
		LbJobRead.Missed -> Unit
		LbJobRead.Gone ->
			return LbJobProgress(if (spec.goneIsDone) LbJobPhase.DONE else LbJobPhase.GONE, saw)
		is LbJobRead.State -> {
			saw = saw || read.state == spec.active
			if (read.state in spec.settled) return LbJobProgress(LbJobPhase.DONE, saw)
			if (read.state in spec.failed && (saw || !spec.failedNeedsActive)) {
				return LbJobProgress(LbJobPhase.FAILED, saw)
			}
		}
	}
	return LbJobProgress(
		if (nowMs - startedAtMs >= LB_JOB_GIVE_UP_MS) LbJobPhase.TIMED_OUT else LbJobPhase.POLLING,
		saw
	)
}
