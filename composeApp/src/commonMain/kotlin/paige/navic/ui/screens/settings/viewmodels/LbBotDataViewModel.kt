package paige.navic.ui.screens.settings.viewmodels

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import paige.navic.domain.manager.LbArtistOverride
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbIndexSync
import paige.navic.domain.manager.LbListeningStatus
import paige.navic.domain.manager.LbMirrorInfo
import paige.navic.domain.manager.LbPanelData
import paige.navic.domain.manager.ListeningPhase
import paige.navic.domain.manager.ListeningSyncOutcome
import paige.navic.domain.manager.formatListenedAt
import paige.navic.domain.manager.listeningPhase
import paige.navic.domain.manager.listeningStatusLine
import paige.navic.domain.manager.listeningSyncLine
import paige.navic.domain.manager.listeningSyncOutcome
import paige.navic.domain.manager.overrideWriteLine
import paige.navic.domain.manager.panelData

/** The overrides list as the panel shows it: what kind of data it is, and the rows. */
@Immutable
data class OverridesPanel(
	val data: LbPanelData = LbPanelData.READING,
	val rows: List<LbArtistOverride> = emptyList()
)

/**
 * The "lb-bot data" panel of the navi-connect settings screen (I-020): what this device holds of
 * lb-bot's — the artist MBID overrides, the index mirror, the listening history — and one "Sync
 * now" for all of it.
 *
 * It holds no data of its own. The overrides are `LbBotManager`'s, the mirror's numbers and its
 * last pull are `LbIndexSync`'s, and the listening status is read from lb-bot; every sentence
 * the panel shows is a pure function of those facts (`LbBotData.kt`), so nothing here can say
 * "up to date" or "loading" on its own authority.
 */
class LbBotDataViewModel(
	private val lbBotManager: LbBotManager,
	private val lbIndexSync: LbIndexSync
) : ViewModel() {

	/** Whether the lb-bot layer exists at all. Read live: the toggle is on this very screen. */
	val configured: Boolean get() = lbBotManager.isConfigured

	// ----- overrides ------------------------------------------------------------------------ //

	/** A read of the overrides that THIS panel started is in flight. */
	private val overridesReading = MutableStateFlow(false)

	val overrides: StateFlow<OverridesPanel> = combine(
		lbBotManager.artistOverrides,
		lbBotManager.artistOverridesSupported,
		lbBotManager.artistOverridesLoaded,
		lbBotManager.artistOverridesReadFailed,
		overridesReading
	) { map, supported, loaded, readFailed, reading ->
		OverridesPanel(
			data = panelData(supported, reading, loaded, readFailed),
			// By name: the map's own order is the order lb-bot happened to list them in.
			rows = map.values.sortedBy { it.name.lowercase() }
		)
	}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OverridesPanel())

	/** The Navidrome artist id of the row a write is in flight for, so its button can wait. */
	private val _busyOverride = MutableStateFlow<String?>(null)
	val busyOverride = _busyOverride.asStateFlow()

	/** What the last set or remove made from this panel did; blank before any. */
	private val _overrideLine = MutableStateFlow("")
	val overrideLine = _overrideLine.asStateFlow()

	// ----- the index mirror ---------------------------------------------------------------- //

	/** False only when the hub advertised a route list without the change feed. */
	val mirrorSupported: Boolean get() = lbBotManager.supportsIndexMirror

	val mirror: StateFlow<LbMirrorInfo> = lbIndexSync.observeMirrorInfo()
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LbMirrorInfo())

	/** The mirror's last pull, live: running, finished on which change, skipped or failed. */
	val indexRun = lbIndexSync.lastRun

	// ----- listening history ----------------------------------------------------------------- //

	private val listeningPhase = MutableStateFlow<ListeningPhase>(ListeningPhase.Loading)
	private val listeningStatus = MutableStateFlow<LbListeningStatus?>(null)

	val listeningLine: StateFlow<String> = combine(
		lbBotManager.listeningSupported,
		listeningPhase,
		listeningStatus
	) { advertised, phase, status ->
		listeningStatusLine(
			advertised = advertised,
			phase = phase,
			count = status?.count ?: 0,
			lastSync = status?.lastSyncAt
				?.let { formatListenedAt(it, TimeZone.currentSystemDefault()) }.orEmpty()
		)
	}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

	/** What "Sync now" came to for the listening half; null before it was pressed. */
	private val listeningSync = MutableStateFlow<ListeningSyncOutcome?>(null)
	val listeningSyncLine: StateFlow<String> = combine(listeningSync, lbBotManager.listeningSupported) { outcome, advertised ->
		// With no listening routes on the hub the status line above already says so.
		if (outcome == null || !advertised) "" else listeningSyncLine(outcome)
	}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

	private var syncJob: Job? = null

	init { read() }

	/** Read what lb-bot holds, as the panel opens. Never a sync: nothing is asked to pull. */
	fun read() {
		viewModelScope.launch { readOverrides() }
		viewModelScope.launch { readListeningStatus() }
	}

	private suspend fun readOverrides() {
		if (!lbBotManager.isConfigured || !lbBotManager.artistOverridesSupported.value) return
		overridesReading.value = true
		try {
			lbBotManager.refreshArtistOverrides()
		} finally {
			overridesReading.value = false
		}
	}

	private suspend fun readListeningStatus(): LbBotManager.LbResult<LbListeningStatus>? {
		if (!lbBotManager.isConfigured || !lbBotManager.listeningSupported.value) return null
		val result = lbBotManager.listeningStatus()
		currentCoroutineContext().ensureActive()
		(result as? LbBotManager.LbResult.Ok)?.let { listeningStatus.value = it.value }
		listeningPhase.value = listeningPhase(result)
		return result
	}

	/**
	 * "Sync now": pull the index mirror, re-read the overrides, and ask lb-bot to pull from
	 * ListenBrainz. What came of each is reported from what happened, not from the tap:
	 *
	 * - the mirror's line is [indexRun], which `LbIndexSync` writes as the pull runs and ends;
	 * - the listening line is the request's own answer, then the status it leads to. The
	 *   request returns at once, so "synced" is only said once `lastSyncAt` has moved; until
	 *   then it says the pull was asked for and has not finished. The status is read a bounded
	 *   number of times, and running out of reads changes no claim — it just stops asking.
	 *
	 * The pull is requested as a fresh start: a user asking now is as good a reason to stop
	 * waiting out a backoff as a new connection is.
	 */
	fun syncNow() {
		if (syncJob?.isActive == true) return
		lbIndexSync.requestSync("manual", freshStart = true)
		viewModelScope.launch { readOverrides() }
		syncJob = viewModelScope.launch {
			if (!lbBotManager.isConfigured || !lbBotManager.listeningSupported.value) return@launch
			listeningSync.value = ListeningSyncOutcome.Asking
			val before = listeningStatus.value
			val post = lbBotManager.listeningSync()
			currentCoroutineContext().ensureActive()
			listeningSync.value = listeningSyncOutcome(before, post, null)
			if (post !is LbBotManager.LbResult.Ok) return@launch
			repeat(STATUS_READS) { attempt ->
				if (attempt > 0) delay(STATUS_READ_GAP_MS)
				val after = readListeningStatus() ?: return@launch
				val outcome = listeningSyncOutcome(before, post, after)
				listeningSync.value = outcome
				// Anything but "not finished yet" (or a status that could not be read, which
				// is not an answer) is the answer.
				if (outcome != ListeningSyncOutcome.Waiting && outcome != ListeningSyncOutcome.AskedUnread) {
					return@launch
				}
			}
		}
	}

	/**
	 * Remove an override. An ordinary remove, exactly as the artist page makes it: lb-bot decides
	 * whether to rescan, and [overrideWriteLine] only says a scan is running when it answered
	 * with one. Nothing here starts a scan.
	 */
	fun removeOverride(ndArtistId: String) {
		if (_busyOverride.value != null) return
		_busyOverride.value = ndArtistId
		viewModelScope.launch {
			try {
				_overrideLine.value = overrideWriteLine(lbBotManager.removeArtistOverride(ndArtistId))
			} finally {
				_busyOverride.value = null
			}
		}
	}

	private companion object {
		/** How many times the status is read after a sync request, and how far apart. */
		const val STATUS_READS = 12
		const val STATUS_READ_GAP_MS = 5_000L
	}
}
