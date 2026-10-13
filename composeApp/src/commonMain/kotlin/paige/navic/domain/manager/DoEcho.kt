package paige.navic.domain.manager

import kotlin.math.abs

/*
 * The command echo, as the playing device keeps it (navi-connect PROTOCOL §5.2-5.3).
 *
 * The hub numbers the directives that change what a report says (`do.seq`), and this device
 * answers in every report with the number of the last one it has carried out (`applied`).
 * `applied: N` promises two things: every numbered directive up to N has been carried out or
 * given up on, and the report's fields were read from the player after that. The hub drops a
 * report whose number is behind what it last sent, so a report that left before a command took
 * effect can no longer put the old track or the old play state back on every device.
 *
 * The number may therefore only move once the command's effect is IN the player's state. A
 * directive whose command has run leaves a [DoExpectation] behind, which [settledBy] checks
 * against the player: usually true at once, and otherwise on a later state or at the deadline.
 *
 * Kept free of the socket and of media3, like `CommandHold.kt`, so that the rules run in a test
 * (`DoEchoTest`). `HubManager` holds one [DoEcho] in a `MutableStateFlow`: its frame coroutine
 * and its reporter both move it, and each move is one compare-and-set of these functions.
 */

/** What the local player says, as far as a directive's effect shows in it. */
data class EchoPlayerState(
	val isPaused: Boolean,
	val index: Int,
	val positionMs: Long,
	val queueEmpty: Boolean
)

/**
 * A numbered directive whose command has run, and what the player has to say before `applied`
 * may reach [seq]. A null field is not asked about.
 */
data class DoExpectation(
	val seq: Int,
	val isPaused: Boolean? = null,
	val index: Int? = null,
	val positionMs: Long? = null,
	val queueEmpty: Boolean? = null,
	val deadlineMs: Long
)

/**
 * How long a directive may wait to be seen in the player. Past it the receiver gives up and
 * moves `applied` anyway (§5.3, duty 5): from then on its reports are simply the truth.
 */
internal const val DO_ECHO_DEADLINE_MS = 1_500L

/** How near its target a position must be to be the seek's. The line `CommandHold.kt` draws. */
internal const val DO_ECHO_SEEK_NEAR_MS = 1_500L

/**
 * What [cmd] has to leave in the player, or null where there is nothing to wait for: a `load`
 * is settled by its own awaited outcome, and the unnumbered directives never get here.
 */
internal fun doExpectation(
	cmd: String?,
	seq: Int,
	index: Int?,
	positionMs: Long?,
	nowMs: Long
): DoExpectation? {
	val deadline = nowMs + DO_ECHO_DEADLINE_MS
	return when (cmd) {
		"play" -> DoExpectation(seq, isPaused = false, deadlineMs = deadline)
		"pause" -> DoExpectation(seq, isPaused = true, deadlineMs = deadline)
		"jump", "queueChanged" -> index?.let { DoExpectation(seq, index = it, deadlineMs = deadline) }
		"seek" -> positionMs?.let { DoExpectation(seq, positionMs = it, deadlineMs = deadline) }
		// Empty AND stopped: an emptied player that still says "playing" is the report the
		// hub's two-second clock used to hide.
		"clear" -> DoExpectation(seq, isPaused = true, queueEmpty = true, deadlineMs = deadline)
		else -> null
	}
}

/**
 * Does [state] show this directive's effect, or has it waited long enough?
 *
 * A clock that went backwards counts as the deadline too, so nothing waits for ever.
 */
internal fun DoExpectation.settledBy(state: EchoPlayerState, nowMs: Long): Boolean {
	if (nowMs - (deadlineMs - DO_ECHO_DEADLINE_MS) !in 0 until DO_ECHO_DEADLINE_MS) return true
	return (isPaused == null || state.isPaused == isPaused) &&
		(index == null || state.index == index) &&
		(positionMs == null || abs(state.positionMs - positionMs) < DO_ECHO_SEEK_NEAR_MS) &&
		(queueEmpty == null || state.queueEmpty == queueEmpty)
}

/**
 * The echo state of one hub connection.
 *
 * [applied] goes into every report. [pending] is the newest directive still waiting to be seen
 * in the player. [connection] counts the hub connections, so that a directive which began on
 * one cannot move the number of the next.
 */
data class DoEcho(
	val connection: Int = 0,
	val applied: Int = 0,
	val pending: DoExpectation? = null
)

/** A new connection: `applied` is 0 again and nothing is waited for (§5.2, the one reset). */
internal fun DoEcho.onNewConnection(): DoEcho = DoEcho(connection = connection + 1)

/**
 * Directive [seq], received on [connection], is over: carried out, refused, failed or
 * superseded. `applied` only ever moves forward, and whatever was waiting at or below [seq]
 * is covered by it.
 */
internal fun DoEcho.settled(connection: Int, seq: Int): DoEcho {
	if (connection != this.connection) return this
	return copy(
		applied = maxOf(applied, seq),
		pending = pending?.takeIf { it.seq > seq }
	)
}

/**
 * The command of a directive received on [connection] has run, and [expectation] is what it
 * should leave behind. The newest one replaces one still waiting: the frame loop is serial, so
 * the newer command ran after the older, and settling it covers both.
 */
internal fun DoEcho.expecting(connection: Int, expectation: DoExpectation): DoEcho {
	if (connection != this.connection) return this
	if (expectation.seq <= applied) return this
	if (pending != null && pending.seq > expectation.seq) return this
	return copy(pending = expectation)
}

/** The echo after the player was seen in [state]: `applied` moves if that settles [pending]. */
internal fun DoEcho.evaluated(state: EchoPlayerState, nowMs: Long): DoEcho {
	val waiting = pending ?: return this
	return if (waiting.settledBy(state, nowMs)) settled(connection, waiting.seq) else this
}
