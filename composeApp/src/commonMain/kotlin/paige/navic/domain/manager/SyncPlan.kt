package paige.navic.domain.manager

import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.info_status_idle

/**
 * The library sync's decisions, pure (B-098, B-100, B-109).
 *
 * A logged-out launch used to start the periodic sync anyway: two full pulls against an empty
 * server address, a sync notification before anyone had logged in, and a `lastSyncFailed` that
 * nothing cleared, so the first login landed on "sync failed".
 */

/** The periodic cycle (and the scan watch it starts) runs only while there is a session. */
internal fun periodicSyncAllowed(loggedIn: Boolean): Boolean = loggedIn

/**
 * Is a full library pull due? Never without a session. With one: when Room holds no album (a new
 * install, or a cache that was dropped because it was corrupt, B-167, while the last pull's
 * stamp is still recent), when no pull has ever finished, or when the last one finished more
 * than [thresholdMs] ago.
 */
internal fun fullPullDue(
	loggedIn: Boolean,
	albumCount: Int,
	lastFullMs: Long,
	nowMs: Long,
	thresholdMs: Long
): Boolean = loggedIn && (albumCount == 0 || lastFullMs <= 0L || nowMs - lastFullMs > thresholdMs)

/** A full pull has started: the failure flag is cleared while it runs. */
internal fun SyncState.fullPullStarted(): SyncState = copy(isSyncing = true, lastSyncFailed = false)

/**
 * A full pull has ended, the login's own included (it goes through [SyncManager.firstPull] now,
 * B-109): the flag says whether THIS pull failed, so a login that pulled the library clears a
 * failure an earlier attempt left.
 */
internal fun SyncState.fullPullEnded(succeeded: Boolean): SyncState = copy(
	isSyncing = false,
	message = Res.string.info_status_idle,
	lastSyncFailed = !succeeded
)
