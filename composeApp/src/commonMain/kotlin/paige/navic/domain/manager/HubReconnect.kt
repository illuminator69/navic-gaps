package paige.navic.domain.manager

/*
 * When the hub link dials again, kept apart from the socket so the rules run in a test
 * (`HubReconnectTest`). `HubManager.runLoop` owns one [HubReconnectBackoff] per loop.
 *
 * Only the main hub socket uses this. The cast bridges have their own sockets, their own
 * backoff (`ReconnectBackoff`) and arbitration rules about who may claim a speaker, and are
 * never kicked.
 */

/**
 * The hub link's reconnect backoff: doubles per attempt up to [maxMs], and starts over only
 * once a link has proved itself.
 *
 * It used to start over on every `welcome`, so a link that got `welcome` and then dropped
 * redialled a second later, for ever, and every one of those welcomes costs a saved-queue sync,
 * an index pull and a settle window (Q-048). A link now has to stay up [stableMs] past its
 * `welcome` to count.
 */
internal class HubReconnectBackoff(
	private val initialMs: Long,
	private val maxMs: Long,
	private val stableMs: Long
) {
	private var currentMs = initialMs

	/** The wait before the next attempt. The one after it doubles, up to [maxMs]. */
	fun next(): Long {
		val wait = currentMs
		currentMs = (currentMs * 2).coerceAtMost(maxMs)
		return wait
	}

	/** Start over. A kick does this: what the backoff was measured against is gone. */
	fun reset() {
		currentMs = initialMs
	}

	/**
	 * An attempt is over. [welcomedAtMs] is when the hub said `welcome` on it, null when it
	 * never did.
	 */
	fun onLinkEnded(welcomedAtMs: Long?, endedAtMs: Long) {
		if (welcomedAtMs != null && endedAtMs - welcomedAtMs >= stableMs) reset()
	}
}

/**
 * How long a kicked attempt still waits. Nothing, unless the kicked attempt before it started
 * under [floorMs] ago ([sinceLastKickedMs]): a network that flaps would otherwise buy a full
 * handshake per flap. Never longer than what was left of the wait the kick cut short
 * ([backoffLeftMs]), so a kick cannot make an attempt later than it was going to be.
 */
internal fun kickedWaitMs(sinceLastKickedMs: Long, backoffLeftMs: Long, floorMs: Long): Long =
	(floorMs - sinceLastKickedMs).coerceIn(0, backoffLeftMs.coerceAtLeast(0))
