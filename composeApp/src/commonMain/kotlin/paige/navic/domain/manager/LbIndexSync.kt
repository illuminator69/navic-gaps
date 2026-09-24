package paige.navic.domain.manager

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import paige.navic.data.database.dao.LbIndexDao
import paige.navic.data.database.entities.LbIndexMetaEntity
import paige.navic.data.database.planIndexDrift
import paige.navic.data.database.relations.LbIndexArtistWithReleases
import paige.navic.data.database.toDiscography
import paige.navic.data.database.toWire
import paige.navic.data.database.entities.LbIndexArtistEntity
import paige.navic.util.Logger
import kotlin.random.Random
import kotlin.time.Clock

/**
 * Keeps the local mirror of lb-bot's library index (the `lb_index_*` tables) converged with
 * lb-bot, by pulling its change feed through the hub (navi-connect contract §1a, "Client mirror
 * rules"). The mirror is what lets the artist page paint lb-bot's discography in its first frame,
 * offline, with no `ensureAvailability` → `discography` waterfall in front of it.
 *
 * **When it pulls — three triggers, so the mirror is never silently behind:**
 * - every hub `welcome` (HubManager). `welcome.lbIndex` is only a hint and is not consulted: an
 *   up-to-date pull costs ~100 bytes, and a notify missed while the hub was down would leave the
 *   hint itself stale — this is the "app closed for days" path;
 * - every `index` frame (HubManager), which lb-bot sends at most once per 2 s while its index is
 *   changing;
 * - the 15-minute [SyncManager] cycle, as the backstop.
 *
 * **One sync in flight.** Triggers go into a conflated channel drained by a single worker, so a
 * burst of frames during a bulk scan collapses into "one sync now, at most one more after it" —
 * and a frame that lands mid-sync is not lost, because the follow-up pull starts from the cursor
 * the running one leaves.
 *
 * **Failures leave the mirror alone.** A 503 means another client holds the hub's single `sync`
 * slot; it, a 502/504 and a network error all back off (10 s doubling to 5 min, each step jittered
 * ±25 %, then wait for the next trigger) with the cursor untouched. Only a `resync` answer wipes
 * rows. An old hub (no such route) or no lb-bot at all is simply "skip": the mirror stays empty
 * and every page keeps its network path, exactly as before this existed — and a 404 from the feed
 * is remembered until the hub could have changed, so no trigger asks again before then.
 *
 * Only this class writes the mirror, and only from the feed. Nothing a user does writes a row.
 */
class LbIndexSync(
	private val lbBotManager: LbBotManager,
	private val dao: LbIndexDao
) {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val triggers = Channel<String>(Channel.CONFLATED)
	/** Written by the worker, read by [requestSync] on whatever thread a frame arrives on. */
	@kotlin.concurrent.Volatile
	private var retryJob: Job? = null
	/** Worker-only. A fresh start is handed over through [backoffReset] instead. */
	private var consecutiveFailures = 0

	/**
	 * Set by a trigger that starts afresh ([requestSync]'s `freshStart`), consumed by the worker
	 * before its next pass: the backoff ladder goes back to its first rung. A flag rather than a
	 * direct write because [consecutiveFailures] belongs to the worker.
	 */
	@kotlin.concurrent.Volatile
	private var backoffReset = false

	/**
	 * Worker-only. The hub answered 404 for the feed: it (or the lb-bot behind it) is older than
	 * the mirror, and every `index` frame, 15-minute tick and retry would otherwise ask again and
	 * get the same 404. Cleared by a fresh start ([backoffReset] — a new `welcome` or lb-bot
	 * coming back, the only ways a hub gets newer) and by an `index` frame ([lbProvenAlive]),
	 * which only a hub and lb-bot that speak the feed can send. As Feishin's `feedMissing`.
	 */
	private var feedMissing = false

	private enum class Outcome { DONE, SKIPPED, BACKOFF }

	init {
		scope.launch {
			for (why in triggers) {
				if (backoffReset) {
					backoffReset = false
					consecutiveFailures = 0
					feedMissing = false
				}
				val outcome = try {
					sync(why)
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					// A Room failure mid-page rolls the page's transaction back; the cursor did
					// not move, so the retry resumes exactly where this one stopped.
					Logger.e(TAG, "index sync ($why) failed", e)
					Outcome.BACKOFF
				}
				when (outcome) {
					Outcome.DONE -> {
						consecutiveFailures = 0
						retryJob?.cancel()
					}
					Outcome.SKIPPED -> consecutiveFailures = 0
					Outcome.BACKOFF -> scheduleRetry()
				}
			}
		}
	}

	/**
	 * Set by a trigger that itself proves lb-bot is up — an `index` frame is lb-bot pushing through
	 * the hub — and consumed by the next pass, which then skips the availability gate.
	 *
	 * Without it, a hub that has just restarted says `welcome.lb.available: false` until its first
	 * probe, [LbBotManager.ensureAvailability] caches that for a minute, and the very frame that
	 * announces lb-bot's first change would be dropped by a gate it had already disproved.
	 */
	@kotlin.concurrent.Volatile
	private var lbProvenAlive = false

	/**
	 * Ask for a pull. Never blocks, never runs two at once; safe from any thread or frame handler.
	 * [lbAlive] is true only when the trigger is itself evidence lb-bot is reachable.
	 *
	 * **A pending backoff retry holds every other trigger off** (review ruling R20b). Without
	 * this the `index` frames — up to one per 2 s during a bulk build — and the 15-minute tick
	 * walked straight past the backoff, so a client contending for the hub's single `sync` slot
	 * retried every ~2 s instead of backing off, which is the load the backoff exists to shed.
	 * The retry itself still runs, and a trigger it swallowed loses nothing: the retry pulls from
	 * the same cursor. An `index` frame's proof that lb-bot is up is kept for that retry.
	 *
	 * [freshStart] is the exception: a new hub connection (`welcome`), or the hub announcing
	 * lb-bot has just come back (`lb` frame, R18). Either invalidates the reason for the wait —
	 * the backoff was measured against a socket or an lb-bot that no longer exists — so it cancels
	 * the pending retry, resets the ladder and pulls now, as Feishin does on a welcome.
	 */
	fun requestSync(why: String, lbAlive: Boolean = false, freshStart: Boolean = false) {
		if (lbAlive) lbProvenAlive = true
		if (freshStart) {
			backoffReset = true
			retryJob?.cancel()
		} else if (why != RETRY && retryJob?.isActive == true) {
			return
		}
		triggers.trySend(why)
	}

	private fun scheduleRetry() {
		consecutiveFailures++
		if (consecutiveFailures > MAX_RETRIES) {
			Logger.w(TAG, "index sync still failing after $MAX_RETRIES retries; waiting for the next trigger")
			consecutiveFailures = 0
			return
		}
		// Jittered ±25 %, as Feishin does: two devices bounced off the hub's single `sync` slot at
		// the same moment would otherwise come back in lockstep and collide again, rung after rung.
		val step = (RETRY_BASE_MS shl (consecutiveFailures - 1)).coerceAtMost(RETRY_MAX_MS)
		val wait = (step * (0.75 + Random.nextDouble() * 0.5)).toLong()
		retryJob?.cancel()
		retryJob = scope.launch {
			delay(wait)
			requestSync(RETRY)
		}
	}

	private fun outcomeFor(error: LbBotManager.LbError, what: String): Outcome = when (error) {
		// No hub, or a hub too old to proxy the feed: the mirror is simply not available.
		LbBotManager.LbError.NotConfigured -> Outcome.SKIPPED
		LbBotManager.LbError.RouteUnknown -> {
			Logger.i(TAG, "$what: the hub has no index feed; the network path stays in use")
			Outcome.SKIPPED
		}
		LbBotManager.LbError.Busy -> Outcome.BACKOFF
		is LbBotManager.LbError.Unreachable -> Outcome.BACKOFF
		// 503 is the hub's `{"busy": true}` for its single sync slot — or lb-bot refusing to
		// hand out a seq it could not make durable. Both mean "later", never "wrong".
		is LbBotManager.LbError.Rejected ->
			if (error.status >= 500) Outcome.BACKOFF else Outcome.SKIPPED
	}

	/**
	 * One convergence pass: pull pages until `more` is false, then the drift check — at most one
	 * drift repair per pass (contract ruling R6).
	 */
	private suspend fun sync(why: String): Outcome {
		if (!lbBotManager.supportsIndexMirror) return Outcome.SKIPPED
		val proven = lbProvenAlive
		lbProvenAlive = false
		if (proven) feedMissing = false
		if (feedMissing) return Outcome.SKIPPED
		if (!proven && !lbBotManager.ensureAvailability()) return Outcome.SKIPPED
		// Asked AGAIN, after the probe (review ruling R20a). On a hub too old to send `welcome.lb`
		// nothing has filled the route list before the first probe, and an empty list means
		// "advertises everything" — so the check above passed vacuously and every trigger sent one
		// `/lb/index/changes` that 404'd. The probe has now filled the list if the hub has one.
		if (!lbBotManager.supportsIndexMirror) return Outcome.SKIPPED

		var force: Set<String> = emptySet()
		var driftChecked = false
		var resyncs = 0
		var applied = 0
		var pages = 0
		while (true) {
			val meta = dao.getMeta() ?: LbIndexMetaEntity()
			val since = meta.cursor
			val page = when (val result = lbBotManager.indexChanges(since, meta.epoch)) {
				is LbBotManager.LbResult.Ok -> result.value
				is LbBotManager.LbResult.Failed -> {
					// Only the feed's own 404 is remembered: the keys route below arrived with it,
					// and a one-off 404 there is no evidence the feed is gone.
					if (result.error == LbBotManager.LbError.RouteUnknown) feedMissing = true
					return outcomeFor(result.error, "index changes")
				}
			}

			// Another epoch, or lb-bot restored to a point behind this mirror. The one answer that
			// wipes: every row is from an index that no longer exists. Also taken if lb-bot ever
			// answers a normal page under an epoch other than ours, which it should not.
			val foreignEpoch = !page.resync && meta.epoch.isNotBlank() &&
				page.epoch.isNotBlank() && page.epoch != meta.epoch
			if (page.resync || foreignEpoch) {
				if (++resyncs > MAX_RESYNCS) {
					Logger.w(TAG, "lb-bot keeps answering resync; giving up this pass")
					return Outcome.BACKOFF
				}
				Logger.i(TAG, "index resync: epoch ${meta.epoch.ifBlank { "-" }} -> ${page.epoch}")
				dao.resetForEpoch(page.epoch, page.scanVersion, page.ttlDays)
				force = emptySet()
				continue
			}

			val plan = dao.applyPage(page, force)
			pages++
			applied += plan.upserts.size + plan.deletes.size
			if (page.more) {
				// A server that says "more" without moving the cursor would spin this loop forever.
				if (plan.newCursor <= since) {
					Logger.w(TAG, "index page did not advance past $since; stopping this pass")
					return Outcome.BACKOFF
				}
				continue
			}

			// The final page. Its artistCount / seqSum come from the same snapshot as its items,
			// so a bulk build running on lb-bot right now cannot make this check loop.
			if (driftChecked) break
			driftChecked = true
			val totals = dao.getTotals()
			if (totals.artistCount == page.artistCount && totals.seqSum == page.seqSum) break

			Logger.w(
				TAG,
				"index drift: local ${totals.artistCount}/${totals.seqSum}, " +
					"lb-bot ${page.artistCount}/${page.seqSum}; comparing keys"
			)
			val keys = when (val result = lbBotManager.indexKeys()) {
				is LbBotManager.LbResult.Ok -> result.value
				is LbBotManager.LbResult.Failed -> return outcomeFor(result.error, "index keys")
			}
			// The epoch turned over between the two calls: the next page answers resync.
			if (keys.epoch.isNotBlank() && keys.epoch != page.epoch) continue
			val drift = planIndexDrift(
				dao.getKeySeqs().associate { it.artistKey to it.seq },
				keys.keys
			)
			// Never a wipe: only the keys lb-bot no longer has go, and they go directly, because
			// the feed will never mention them again.
			if (drift.deletes.isNotEmpty()) dao.deleteArtists(drift.deletes)
			val from = drift.refetchFrom ?: break
			force = drift.refetch
			dao.setCursor(from)
		}
		if (applied > 0 || pages > 1) {
			Logger.i(TAG, "index sync ($why): $applied artist change(s) over $pages page(s)")
		}
		return Outcome.DONE
	}

	// ----- reads, for the pages that render lb-bot's discography ----------------------------- //

	/**
	 * The mirrored artist for a Navidrome artist id (and optionally its MBID), resolved in
	 * lb-bot's own order — see [LbIndexDao.findArtist]. Null means "not in the mirror yet", and
	 * the caller keeps its network path for exactly that case.
	 */
	suspend fun findArtist(ndId: String, mbid: String? = null): LbIndexArtistWithReleases? =
		dao.findArtist(ndId, mbid.orEmpty())

	/** [findArtist], re-emitting whenever a sync changes that artist (or the lookup's winner). */
	fun observeArtist(ndId: String, mbid: String? = null): Flow<LbIndexArtistWithReleases?> =
		dao.observeArtist(ndId, mbid.orEmpty()).distinctUntilChanged()

	/**
	 * [findArtist] in the exact shape `GET /lb/artist/discography` answers, `stale` computed here
	 * against the envelope (contract §1a) — so a caller can use the mirror and the network
	 * interchangeably. No `scan` record: that is scan-task state, not index state.
	 */
	suspend fun discography(ndId: String, mbid: String? = null): LbDiscography? {
		val found = findArtist(ndId, mbid) ?: return null
		return found.toDiscography(dao.getMeta())
	}

	/** [discography] as a Flow: the artist page's live view of the mirror. */
	fun observeDiscography(ndId: String, mbid: String? = null): Flow<LbDiscography?> =
		combine(observeArtist(ndId, mbid), dao.observeMeta()) { found, meta ->
			found?.toDiscography(meta)
		}.distinctUntilChanged()

	private fun LbIndexArtistWithReleases.toDiscography(meta: LbIndexMetaEntity?): LbDiscography =
		artist.toDiscography(
			releases = releases,
			// The meta row is written in the same transaction as every artist, so it exists
			// whenever an artist does; the fallbacks only keep a corrupt state from calling
			// everything stale.
			envelopeScanVersion = meta?.scanVersion ?: artist.scanVersion,
			ttlDays = meta?.ttlDays ?: DEFAULT_TTL_DAYS,
			nowSeconds = Clock.System.now().toEpochMilliseconds() / 1000.0
		)

	/**
	 * Every mirrored row for one release-group, each with its artist — a collaboration is filed
	 * under each credited artist. [preferArtistKey] (an artist MBID, which is lb-bot's key for a
	 * scanned artist) sorts that artist's row first; after it, a row that names a Navidrome album,
	 * since that is the answer "does the library hold this" needs. Empty = not in the mirror.
	 */
	suspend fun releasesByRgid(rgid: String, preferArtistKey: String = ""): List<LbMirroredRelease> {
		if (rgid.isBlank()) return emptyList()
		return dao.releasesByRgid(rgid)
			.map { row -> LbMirroredRelease(row.toWire(), dao.getArtistByKey(row.artistKey)) }
			.sortedWith(
				compareByDescending<LbMirroredRelease> {
					preferArtistKey.isNotBlank() && it.artist?.artistKey == preferArtistKey
				}.thenByDescending { it.release.navidromeAlbumIds.any { id -> id.isNotBlank() } }
			)
	}

	/**
	 * The mirrored rows that claim a Navidrome album id — the owned album page's way from an
	 * album to its release-group without asking lb-bot for the artist's whole discography.
	 * [ndArtistId] sorts that artist's row first. The DAO's quoted `LIKE` over the JSON list is
	 * a substring match, so the id is re-checked against the decoded list here.
	 */
	suspend fun releasesForAlbum(ndAlbumId: String, ndArtistId: String = ""): List<LbMirroredRelease> {
		if (ndAlbumId.isBlank()) return emptyList()
		return dao.releasesByNavidromeAlbumId(ndAlbumId)
			.map { row -> LbMirroredRelease(row.toWire(), dao.getArtistByKey(row.artistKey)) }
			.filter { ndAlbumId in it.release.navidromeAlbumIds }
			.sortedByDescending { ndArtistId.isNotBlank() && it.artist?.ndArtistId == ndArtistId }
	}

	private companion object {
		const val TAG = "LbIndexSync"
		const val RETRY = "retry"
		const val MAX_RESYNCS = 2
		const val MAX_RETRIES = 6
		const val RETRY_BASE_MS = 10_000L
		const val RETRY_MAX_MS = 5 * 60_000L
		/** lb-bot's own default for LB_BOT_INDEX_TTL_DAYS. */
		const val DEFAULT_TTL_DAYS = 30.0
	}
}

/** One mirrored release-group row with the mirrored artist it is filed under (null only if the
 *  artist row vanished between the two reads — a sync landing mid-lookup). */
data class LbMirroredRelease(
	val release: LbRelease,
	val artist: LbIndexArtistEntity?
)
