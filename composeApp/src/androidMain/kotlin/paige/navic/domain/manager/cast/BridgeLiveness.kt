package paige.navic.domain.manager.cast

/**
 * How long a bridge may sit short of the hub, while the hub itself is reachable, before the
 * manager stops waiting for it and claims the speaker afresh (B-045).
 */
internal const val BRIDGE_STALE_MS = 2 * 60_000L

/**
 * One bridge's reconnect backoff: doubles per failed attempt up to [maxMs], and starts over once
 * the hub has answered `welcome` (B-045).
 *
 * It used to only double. A bridge whose link worked for an hour and then blipped retried at the
 * ceiling it had reached during some earlier outage, rather than at once.
 *
 * Only touched from the bridge's connect loop, `welcome` included (its frames are handled inline
 * on that coroutine), so it needs no locking.
 */
internal class ReconnectBackoff(private val initialMs: Long, private val maxMs: Long) {
	private var currentMs = initialMs

	/** The hub said `welcome`: the link worked, so the next drop retries from the start. */
	fun onWelcome() {
		currentMs = initialMs
	}

	/** The wait before the next attempt. The one after it doubles, up to [maxMs]. */
	fun next(): Long {
		val wait = currentMs
		currentMs = (currentMs * 2).coerceAtMost(maxMs)
		return wait
	}
}

/**
 * Should reconcile tear this bridge down and claim its speaker afresh? (B-045)
 *
 * A bridge's own loop is meant to get it back to the hub, and two things defeat it: the loop
 * ended (a stray cancellation used to end it for good, and [CastDeviceBridge.start] never
 * restarts one), or it runs but never gets a `welcome`. Either leaves the picker on "connecting…"
 * for as long as the app lives.
 *
 * - A bridge that is [standingDown] is on its way out through its own callback, and its loop
 *   ended on purpose: re-claiming it would race the stand-down.
 * - A dead loop is stale at once; it will never reconnect by itself.
 * - A live loop gets [BRIDGE_STALE_MS], counted only while the hub is up ([hubUpSinceMs] null
 *   while this device's own hub socket is down). While the hub is unreachable no bridge can get
 *   in, and tearing one down would close a cast channel that is still advancing the queue.
 */
internal fun bridgeIsStale(
	connected: Boolean,
	loopAlive: Boolean,
	standingDown: Boolean,
	downSinceMs: Long,
	hubUpSinceMs: Long?,
	nowMs: Long
): Boolean {
	if (connected || standingDown) return false
	if (!loopAlive) return true
	val hubUp = hubUpSinceMs ?: return false
	return nowMs - maxOf(downSinceMs, hubUp) > BRIDGE_STALE_MS
}
