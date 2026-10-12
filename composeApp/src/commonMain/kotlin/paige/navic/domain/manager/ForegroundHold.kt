package paige.navic.domain.manager

/*
 * What the foreground service is held for, and what the controller sends the system when that
 * changes — kept free of Android so the rules can be run in a test (`ForegroundHoldTest`).
 * `DownloadForegroundController.android.kt` keeps one [ForegroundHold] under its lock and makes
 * the calls; `DownloadService` answers them.
 *
 * **The crash these rules exist for (B-096).** `startForegroundService` is a promise that the
 * service will call `startForeground`. Bringing the service down before it has is not the no-op
 * that stopping an idle service is: the system kills the whole app with
 * `ForegroundServiceDidNotStartInTimeException`. The controller used to `stopService` whenever
 * the last reason was released, so any hold shorter than the main thread's way to
 * `onStartCommand` was fatal — a full pull that fails at once because nobody is logged in (every
 * launch of a fresh install), a download that is over at once.
 *
 * So a start is **pending** from `startForegroundService` until the `onStartCommand` it caused
 * has called `startForeground`, and while one is pending the controller sends nothing at all:
 * the service has not read the reasons yet, so there is nothing to tell it, and it must not be
 * stopped. That `onStartCommand` ends the wait and reads the reasons in one step ([answered]),
 * and takes itself down when nothing is held by then.
 *
 * **Why this cannot go stale the way a "service is running" flag did.** That flag claimed
 * something about the service, which also stops for reasons the controller never hears of.
 * [ForegroundHold.pendingStart] claims only that one start has not been answered yet, and names
 * it by a token the Intent carries. It is cleared by the call that set it throwing ([refused])
 * or by the one `onStartCommand` holding that token — succeeding ([answered]) or giving up
 * ([gaveUp]) — and by nothing else: not by a budget timeout, not by a command an older instance
 * is still working through. Android delivers that `onStartCommand` for every start it accepted,
 * and the state lives in the service's own process, so it dies with it.
 */

/** What the controller sends the system after a change to what is held. */
enum class ForegroundSignal {
	/** Nothing: no change, or a start is pending and its `onStartCommand` reads the reasons. */
	NONE,

	/** `startForegroundService`: the first reason, and nothing started yet. */
	START_FOREGROUND,

	/** `startService`: the service is up and has to pick its notification again. */
	REASONS_CHANGED,

	/** `stopService`: nothing is held and no start is waiting for its `startForeground`. */
	STOP
}

/**
 * [held] is who needs the process kept in the foreground. [pendingStart] is the token of the
 * `startForegroundService` whose `onStartCommand` has not answered yet, 0 when there is none.
 */
data class ForegroundHold(
	val held: Set<ForegroundReason> = emptySet(),
	val pendingStart: Int = 0
)

/**
 * [reason] has work in flight. [token] (never 0, never reused) names the start this asks for
 * when the answer is [ForegroundSignal.START_FOREGROUND]; it is unused otherwise.
 *
 * A reason that arrives while a start is pending sends nothing, whether it is a second one or
 * the same one released a moment ago: the pending `onStartCommand` will find it held. Asking for
 * another start instead would leave two answers outstanding and one token to tell them apart.
 */
fun ForegroundHold.acquire(reason: ForegroundReason, token: Int): Pair<ForegroundHold, ForegroundSignal> {
	// Already held: no Intent per claimed job.
	if (reason in held) return this to ForegroundSignal.NONE
	val next = copy(held = held + reason)
	return when {
		pendingStart != 0 -> next to ForegroundSignal.NONE
		held.isEmpty() -> next.copy(pendingStart = token) to ForegroundSignal.START_FOREGROUND
		else -> next to ForegroundSignal.REASONS_CHANGED
	}
}

/**
 * [reason] has nothing left to protect. Releasing the last one stops the service — unless a
 * start is pending, when stopping is the fatal case above and the service stops itself instead.
 *
 * With no start pending and nothing held this still answers [ForegroundSignal.STOP], also for a
 * reason that was not held: stopping a service that is not running is a no-op, and it costs no
 * "was it started" bookkeeping.
 */
fun ForegroundHold.release(reason: ForegroundReason): Pair<ForegroundHold, ForegroundSignal> {
	val next = copy(held = held - reason)
	return next to when {
		pendingStart != 0 -> ForegroundSignal.NONE
		next.held.isEmpty() -> ForegroundSignal.STOP
		else -> ForegroundSignal.REASONS_CHANGED
	}
}

/**
 * The system threw at the call [acquire] asked for ([token] 0 when that was not a start).
 * [reason] is not protected, and a start that was never accepted has no answer coming.
 */
fun ForegroundHold.refused(reason: ForegroundReason, token: Int): ForegroundHold =
	copy(held = held - reason, pendingStart = pendingStart.unless(token))

/**
 * The `onStartCommand` that carries [token] has called `startForeground`: from here the service
 * may be stopped like any other. A command with no token, or an older one, changes nothing.
 */
fun ForegroundHold.answered(token: Int): ForegroundHold =
	copy(pendingStart = pendingStart.unless(token))

/**
 * The service stopped ITSELF — `startForeground` refused, or the `dataSync` budget ran out — so
 * nothing is protected any more. [token] is the giving-up command's own (0 for a timeout, which
 * belongs to no command): another start that is still pending keeps waiting for its own answer.
 */
fun ForegroundHold.gaveUp(token: Int): ForegroundHold =
	ForegroundHold(held = emptySet(), pendingStart = pendingStart.unless(token))

private fun Int.unless(token: Int): Int = if (token != 0 && token == this) 0 else this
