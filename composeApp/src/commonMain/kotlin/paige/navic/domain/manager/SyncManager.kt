package paige.navic.domain.manager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.info_status_idle
import org.jetbrains.compose.resources.StringResource
import paige.navic.data.database.dao.AlbumDao
import paige.navic.data.database.dao.SyncActionDao
import paige.navic.data.database.entities.SyncActionEntity
import paige.navic.data.database.entities.SyncActionType
import paige.navic.domain.repositories.DbRepository
import paige.navic.util.Logger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

data class SyncState(
	val isSyncing: Boolean = false,
	val progress: Float = 0f,
	val message: StringResource = Res.string.info_status_idle,
	/**
	 * True when the last full-library sync attempt failed at the top level (e.g. server unreachable),
	 * so the UI can say "showing cached library, sync failed" instead of implying the library is
	 * empty/broken. Cleared the moment a sync succeeds or a new one starts.
	 */
	val lastSyncFailed: Boolean = false
)

class SyncManager(
	private val repository: DbRepository,
	private val syncDao: SyncActionDao,
	private val albumDao: AlbumDao,
	private val connectivityManager: ConnectivityManager,
	private val sessionManager: SessionManager,
	private val preferenceManager: PreferenceManager,
	private val lbBotManager: LbBotManager
) {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private var syncJob: Job? = null
	private val syncMutex = Mutex()

	private val fullSyncThreshold = 1.hours

	private companion object {
		/** An `albumIndexed` with no ids: Navidrome has it, only which album is unknown. */
		val LB_BOT_NEWEST_DEBOUNCE_INDEXED = 2.seconds

		/**
		 * An `albumPlaced` with no `albumIndexed` behind it (an lb-bot predating the event):
		 * give Navidrome's quick scan time to pick the folder up before looking.
		 */
		val LB_BOT_NEWEST_DEBOUNCE_PLACED = 20.seconds

		/** Feishin showed the real cover about ten seconds after landing; allow for slower. */
		val ARTWORK_RESYNC_DELAYS = listOf(15.seconds, 45.seconds)

		/** How often to ask Navidrome whether a scan ran. One tiny request. */
		val SCAN_WATCH_INTERVAL = 30.seconds

		/** After a scan ends: long enough for Navidrome to settle the album's cover too. */
		val SCAN_SETTLE_DELAY = 5.seconds
	}

	private val _syncState = MutableStateFlow(SyncState())
	val syncState = _syncState.asStateFlow()

	/**
	 * Pull a landed album into Room the moment Navidrome has it.
	 *
	 * This used to answer every fill with a FULL library pull (a `getAlbum` per album in
	 * the library) behind a 90 s debounce and a 5-minute floor — minutes between Navidrome
	 * showing an album and this app showing it. lb-bot's verifier now announces
	 * `albumIndexed` with the Navidrome album ids once the scan has picked the files up, so
	 * a landing costs one request per album and happens as soon as the frame arrives.
	 *
	 * Events are handled one at a time (the collector is sequential), which is what keeps
	 * a burst of fills from racing each other's upserts. Removals are still the hourly full
	 * sync's job.
	 */
	private var lbBotNewestJob: Job? = null

	private fun observeLbBotFills() {
		scope.launch {
			lbBotManager.libraryEvents.collect { event ->
				// A discography scan changes lb-bot's index only; the artist page re-reads
				// that itself off the revision bump. Nothing in Navidrome moved.
				if (event.event == EVENT_ARTIST_SCANNED) return@collect
				if (event.ndAlbumIds.isNotEmpty()) {
					repository.syncAlbumsById(event.ndAlbumIds)
						.onSuccess {
							Logger.i("SyncManager", "lb-bot landing synced: ${event.ndAlbumIds}")
							lbBotManager.onLocalLibrarySynced()
							scheduleArtworkResync(event.ndAlbumIds)
						}
						.onFailure {
							Logger.w("SyncManager", "lb-bot landing sync failed, falling back", it)
							scheduleNewestSync(LB_BOT_NEWEST_DEBOUNCE_INDEXED)
						}
				} else {
					scheduleNewestSync(
						if (event.event == EVENT_INDEXED) LB_BOT_NEWEST_DEBOUNCE_INDEXED
						else LB_BOT_NEWEST_DEBOUNCE_PLACED
					)
				}
			}
		}
	}

	/**
	 * Pull a landed album again once Navidrome has had time to find its artwork.
	 *
	 * The landing sync runs the moment the scan indexes the tracks, which is often before
	 * Navidrome has resolved the cover — so the album arrived with no art, and the placeholder
	 * was cached under that cover id for good. Navidrome's cover id changes when the art does,
	 * so re-reading the album gives every cover view a new key and the real image loads.
	 * Idempotent upserts; only the ids from the event are touched.
	 */
	private fun scheduleArtworkResync(ids: List<String>) {
		scope.launch {
			for (wait in ARTWORK_RESYNC_DELAYS) {
				delay(wait)
				repository.syncAlbumsById(ids)
					.onSuccess { lbBotManager.onLocalLibrarySynced() }
					.onFailure { Logger.w("SyncManager", "artwork re-sync failed for $ids", it) }
			}
		}
	}

	/**
	 * Debounced: a burst of id-less events collapses into one walk.
	 *
	 * A changed-albums sweep, not a `newest` one: an id-less event is as likely to be a gap
	 * fill (an album that already existed and gained tracks) as a new album, and `newest`
	 * can't see the first kind at all.
	 */
	private fun scheduleNewestSync(after: kotlin.time.Duration) {
		lbBotNewestJob?.cancel()
		lbBotNewestJob = scope.launch {
			delay(after)
			syncChangedAlbums("lb-bot event")
		}
	}

	private val changedSyncMutex = Mutex()

	private suspend fun syncChangedAlbums(why: String) {
		// Never alongside the full pull, which re-reads everything anyway.
		if (syncMutex.isLocked) return
		changedSyncMutex.withLock {
			repository.syncChangedAlbums()
				.onSuccess { count ->
					if (count > 0) {
						Logger.i("SyncManager", "$why: synced $count changed album(s)")
						lbBotManager.onLocalLibrarySynced()
					}
				}
				.onFailure { Logger.w("SyncManager", "$why: changed-album sync failed", it) }
		}
	}

	/**
	 * Notice Navidrome scans that nobody told us about.
	 *
	 * Every way music reaches the library ends in a Navidrome scan, whether or not lb-bot
	 * or the hub announced it: lb-bot's own page, a fill started from Feishin, a folder
	 * matched by hand, a scheduled scan. Watching `getScanStatus` catches all of them. A
	 * scan short enough to fall between two polls still changes `count`, which is why
	 * either signal triggers the sweep.
	 */
	private var scanWatchJob: Job? = null

	private fun startScanWatch() {
		if (scanWatchJob?.isActive == true) return
		scanWatchJob = scope.launch {
			var wasScanning = false
			var lastCount: Int? = null
			while (isActive) {
				delay(SCAN_WATCH_INTERVAL)
				if (!connectivityManager.isOnline.value) continue
				val status = try {
					sessionManager.api.getScanStatus()
				} catch (e: Exception) {
					if (e is kotlin.coroutines.cancellation.CancellationException) throw e
					continue
				}
				val finished = wasScanning && !status.scanning
				val countMoved = lastCount != null && lastCount != status.count && !status.scanning
				wasScanning = status.scanning
				if (!status.scanning) lastCount = status.count
				if (finished || countMoved) {
					delay(SCAN_SETTLE_DELAY)
					syncChangedAlbums("Navidrome scan")
				}
			}
		}
	}

	init {
		observeLbBotFills()
		scope.launch {
			connectivityManager.isOnline.collect { isOnline ->
				if (!syncMutex.isLocked && isOnline) {
					syncMutex.withLock { processQueue() }
				}
			}
		}
	}

	fun startPeriodicSync() {
		Logger.i("SyncManager", "Starting periodic sync cicle.")
		if (syncJob?.isActive == true) return

		scope.launch {
			if (albumDao.getAlbumCount() == 0
				|| preferenceManager.lastFullSyncTime <= 0L) {
				Logger.i("SyncManager", "Syncing now because we haven't synced before")
				runSyncCycle()
			}
		}

		syncJob = scope.launch {
			while (isActive) {
				runSyncCycle()
				delay(15.minutes)
			}
		}
		startScanWatch()
	}

	fun triggerManualSync() {
		scope.launch {
			preferenceManager.lastFullSyncTime = 0
			runSyncCycle()
		}
	}

	fun stopPeriodicSync() {
		syncJob?.cancel()
		scanWatchJob?.cancel()
		_syncState.value = SyncState(isSyncing = false)
	}

	fun enqueueAction(actionType: SyncActionType, itemId: String) {
		scope.launch {
			syncDao.enqueue(SyncActionEntity(actionType = actionType, itemId = itemId))
			if (!syncMutex.isLocked) {
				syncMutex.withLock { processQueue() }
			}
		}
	}

	private suspend fun runSyncCycle() {
		syncMutex.withLock {
			processQueue()

			val currentTime = Clock.System.now()
			if (currentTime - Instant.fromEpochMilliseconds(preferenceManager.lastFullSyncTime) > fullSyncThreshold) {
				Logger.i("SyncManager", "Starting full library pull...")

				_syncState.update {
					// A new attempt clears any prior failure flag while it runs.
					it.copy(isSyncing = true, lastSyncFailed = false)
				}

				val result = repository.syncEverything { progress, message ->
					_syncState.update {
						it.copy(isSyncing = true, progress = progress, message = message)
					}
				}

				if (result.isSuccess) {
					preferenceManager.lastFullSyncTime = currentTime.toEpochMilliseconds()
					Logger.i("SyncManager", "Full library sync complete.")
				} else {
					// With the cause. Without it the log said a sync failed and nothing about why.
					Logger.e("SyncManager", "Full library sync failed; keeping cached library.", result.exceptionOrNull())
				}

				_syncState.update {
					it.copy(
						isSyncing = false,
						message = Res.string.info_status_idle,
						lastSyncFailed = result.isFailure
					)
				}
			}
		}
	}

	private suspend fun processQueue() {
		val actions = syncDao.getPendingActions()
		if (actions.isEmpty()) return

		for (action in actions) {
			try {
				when (action.actionType) {
					SyncActionType.STAR -> sessionManager.api.star(action.itemId)
					SyncActionType.UNSTAR -> sessionManager.api.unstar(action.itemId)
					SyncActionType.DELETE_PLAYLIST -> sessionManager.api.deletePlaylist(action.itemId)
					SyncActionType.SCROBBLE -> sessionManager.api.scrobble(action.itemId, submission = true)
					SyncActionType.STAR_0 -> sessionManager.api.setRating(action.itemId, 0)
					SyncActionType.STAR_1 -> sessionManager.api.setRating(action.itemId, 1)
					SyncActionType.STAR_2 -> sessionManager.api.setRating(action.itemId, 2)
					SyncActionType.STAR_3 -> sessionManager.api.setRating(action.itemId, 3)
					SyncActionType.STAR_4 -> sessionManager.api.setRating(action.itemId, 4)
					SyncActionType.STAR_5 -> sessionManager.api.setRating(action.itemId, 5)
				}

				syncDao.removeAction(action.id)
				Logger.i(
					"SyncManager",
					"Successfully synced ${action.actionType} for ${action.itemId}"
				)

			} catch (e: Exception) {
				Logger.e("SyncManager", "Network failed. Action left in queue.", e)
				break
			}
		}
	}
}
