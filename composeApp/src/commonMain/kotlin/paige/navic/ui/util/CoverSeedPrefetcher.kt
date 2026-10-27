package paige.navic.ui.util

import com.kmpalette.loader.NetworkLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.SessionManager
import paige.navic.util.CoverPlaceholder
import kotlin.time.TimeSource

/**
 * Works out cover colours BEFORE a page needs them (I-043): every cover a tile shows, and the next
 * songs in the queue, at low priority, into [CoverSeedStore]. A page opened from a tile, or a song
 * changed to one queued a few seconds earlier, then finds its colours in the store and is coloured
 * in its first frame instead of fetching after it is up.
 *
 * At most [PARALLEL] fetches at once ([PrefetchQueue]): the next songs first, in their order, then
 * the tiles newest first (what was scrolled past matters less than what is on screen now). A failed cover is not asked for again for a minute, so
 * offline or a slow server costs a bounded number of attempts, not a loop. Nothing at all while
 * cover theming is off (CLAUDE.md §6 rule 7). Main thread only: [request] is called from
 * composition effects, and the work is scheduled on [Dispatchers.Main].
 */
class CoverSeedPrefetcher(
	private val store: CoverSeedStore,
	private val sessionManager: SessionManager,
	private val preferences: PreferenceManager
) {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
	private val clockOrigin = TimeSource.Monotonic.markNow()
	private val queue = PrefetchQueue(now = { clockOrigin.elapsedNow().inWholeMilliseconds })
	private var running = 0

	/** [nextSong]: one of the next songs in the queue, which no tile request displaces (audit L4a-08). */
	fun request(coverArtId: String?, nextSong: Boolean = false) {
		if (coverArtId.isNullOrBlank() || CoverPlaceholder.isPlaceholder(coverArtId)) return
		if (!preferences.dynamicTheming || store.contains(coverArtId)) return
		if (queue.offer(coverArtId, urgent = nextSong)) pump()
	}

	private fun pump() {
		while (running < PARALLEL) {
			val id = queue.next() ?: return
			if (store.contains(id)) {
				queue.done(id)
				continue
			}
			running++
			scope.launch {
				try {
					// A loader per fetch: its request builder is one mutable object, and two
					// fetches run at once.
					val seeds = fetchCoverSeeds(NetworkLoader(paletteHttpClient), sessionManager.getCoverArtUrl(id, size = PALETTE_PX))
					if (seeds != null) {
						store.put(id, seeds)
						queue.done(id)
					} else {
						queue.failed(id)
					}
				} finally {
					running--
					pump()
				}
			}
		}
	}

	private companion object {
		const val PARALLEL = 2
	}
}

/**
 * The prefetcher's work list, two lanes. Urgent ids (the next songs) are served first, in the order
 * they were offered, at most [urgentCapacity] of them; the rest (tiles) newest first, at most
 * [capacity] waiting (the oldest waiting id is dropped). A tile flood never drops an urgent id, and a
 * waiting tile offered as urgent moves up. An id is not taken again while it waits, while it is being
 * fetched, or for [retryAfterMs] after it failed. Pure, with an injected clock, so the rules are
 * testable.
 */
internal class PrefetchQueue(
	private val capacity: Int = 48,
	private val retryAfterMs: Long = 60_000,
	private val urgentCapacity: Int = 8,
	private val now: () -> Long
) {
	private val urgentPending = ArrayDeque<String>()
	private val pending = ArrayDeque<String>()
	private val inFlight = mutableSetOf<String>()
	private val failedAt = mutableMapOf<String, Long>()

	/**
	 * False when [id] is already waiting in its lane (or as urgent), being fetched, or failed within the
	 * retry window. A tile id offered as [urgent] moves to the urgent lane.
	 */
	fun offer(id: String, urgent: Boolean = false): Boolean {
		if (id in inFlight || id in urgentPending) return false
		if (!urgent && id in pending) return false
		failedAt[id]?.let { at ->
			if (now() - at < retryAfterMs) return false
			failedAt.remove(id)
		}
		if (urgent) {
			pending.remove(id)
			urgentPending.addLast(id)
			while (urgentPending.size > urgentCapacity) urgentPending.removeFirst()
		} else {
			pending.addLast(id)
			while (pending.size > capacity) pending.removeFirst()
		}
		return true
	}

	/** The next id to fetch, now counted as being fetched: the oldest urgent, else the newest tile. */
	fun next(): String? {
		val id = urgentPending.removeFirstOrNull() ?: pending.removeLastOrNull() ?: return null
		inFlight += id
		return id
	}

	fun done(id: String) {
		inFlight -= id
	}

	fun failed(id: String) {
		inFlight -= id
		failedAt[id] = now()
	}
}
