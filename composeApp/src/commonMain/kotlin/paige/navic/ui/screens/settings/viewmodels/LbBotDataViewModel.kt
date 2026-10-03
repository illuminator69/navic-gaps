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
import paige.navic.data.database.dao.ArtistDao
import paige.navic.domain.manager.LbArtistOverride
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbIndexSync
import paige.navic.domain.manager.LbListeningStatus
import paige.navic.domain.manager.LbMatchToCheck
import paige.navic.domain.manager.LbMirrorInfo
import paige.navic.domain.manager.LbPanelData
import paige.navic.domain.manager.ListeningPhase
import paige.navic.domain.manager.ListeningSyncOutcome
import paige.navic.domain.manager.formatListenedAt
import paige.navic.domain.manager.listeningPhase
import paige.navic.domain.manager.listeningStatusLine
import paige.navic.domain.manager.listeningSyncLine
import paige.navic.domain.manager.listeningSyncOutcome
import paige.navic.domain.manager.matchCanKeep
import paige.navic.domain.manager.matchesSectionShown
import paige.navic.domain.manager.matchesToShow
import paige.navic.domain.manager.overrideWriteLine
import paige.navic.domain.manager.panelData

/** The overrides list as the panel shows it: what kind of data it is, and the rows. */
@Immutable
data class OverridesPanel(
	val data: LbPanelData = LbPanelData.READING,
	val rows: List<LbArtistOverride> = emptyList()
)

/** "Matches to check" as the panel shows it (I-021). [shown] false: no such section at all. */
@Immutable
data class MatchesPanel(
	val shown: Boolean = false,
	val data: LbPanelData = LbPanelData.READING,
	val rows: List<LbMatchToCheck> = emptyList()
)

/** The MusicBrainz picker opened from a "matches to check" row, and its write's state. */
@Immutable
data class MatchPick(
	val row: LbMatchToCheck,
	val working: Boolean = false,
	val error: LbBotManager.LbError? = null
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
	private val lbIndexSync: LbIndexSync,
	private val artistDao: ArtistDao
) : ViewModel() {

	/**
	 * Whether there is an lb-bot layer at all, in its two halves (`lbLayerLine`). The first is a
	 * preference read, live because the switch and the fields are on this very screen; the second
	 * is the hub's own word, and a Flow so the section is redrawn when the hub says otherwise.
	 */
	val hubSet: Boolean get() = lbBotManager.hubSet
	val hubSaysNoLbBot = lbBotManager.hubSaysNoLbBot

	// ----- overrides ------------------------------------------------------------------------ //

	/** A read of the overrides that THIS panel started is in flight. */
	private val overridesReading = MutableStateFlow(false)

	val overrides: StateFlow<OverridesPanel> = combine(
		lbBotManager.artistOverrides,
		// "Not supported" is only a verdict once the hub has spoken; the two travel together.
		combine(lbBotManager.hubHeard, lbBotManager.artistOverridesSupported, ::Pair),
		lbBotManager.artistOverridesLoaded,
		lbBotManager.artistOverridesReadFailed,
		overridesReading
	) { map, (hubHeard, supported), loaded, readFailed, reading ->
		OverridesPanel(
			data = panelData(hubHeard, supported, reading, loaded, readFailed),
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

	// ----- matches to check (I-021) ---------------------------------------------------------- //

	/**
	 * lb-bot's "matches to check", which arrive in the body the overrides come in — so they are
	 * exactly as loaded, stale or unreachable as the overrides are, and [overrides]'s verdict
	 * is theirs. An artist the map already holds an override for has left the list.
	 */
	val matches: StateFlow<MatchesPanel> = combine(
		lbBotManager.matchesToCheck,
		overrides
	) { toCheck, panel ->
		val rows = matchesToShow(toCheck, panel.rows.associateBy { it.ndArtistId })
		MatchesPanel(
			shown = matchesSectionShown(panel.data, rows),
			data = panel.data,
			rows = rows.orEmpty()
		)
	}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MatchesPanel())

	/** The picker a row's "Choose…" opened; null while it is closed. */
	private val _pick = MutableStateFlow<MatchPick?>(null)
	val pick = _pick.asStateFlow()

	/**
	 * The artists Room holds, so a row is only a link to an artist page that exists. The list is
	 * of library artists, but this device has a page only for the ones it has synced.
	 */
	private val _knownArtistIds = MutableStateFlow<Set<String>>(emptySet())
	val knownArtistIds = _knownArtistIds.asStateFlow()

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
		lbBotManager.hubHeard,
		lbBotManager.listeningSupported,
		listeningPhase,
		listeningStatus
	) { hubHeard, advertised, phase, status ->
		listeningStatusLine(
			hubHeard = hubHeard,
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

	/** The one question [hearHub] has out; both halves of the panel wait on the same one. */
	private var hubJob: Job? = null

	/**
	 * Read what lb-bot holds. Never a sync: nothing is asked to pull.
	 *
	 * Called by the section when it appears and again whenever the lb-bot layer's configuration
	 * changes under it — not from `init`: the hub is set up on this very screen, and a view model
	 * created before that would have read nothing and then sat on "Asking lb-bot…" with nothing
	 * asking.
	 */
	fun read() {
		viewModelScope.launch { readOverrides() }
		viewModelScope.launch { readListeningStatus() }
		viewModelScope.launch {
			runCatching { artistDao.getAllArtistIds().toSet() }
				.onSuccess { _knownArtistIds.value = it }
		}
	}

	/**
	 * Ask the hub what it proxies, when it has not said so in this process — a cold start with
	 * the socket not up yet, or the hub out of reach. Without this the panel would read that
	 * silence as "this hub is older", which nothing said. One `/lb/status`, shared by whoever
	 * asks while it is out; a hub that has spoken is not asked again.
	 */
	private suspend fun hearHub() {
		if (lbBotManager.hubHeard.value) return
		val asking = hubJob?.takeIf { it.isActive }
			?: viewModelScope.launch { lbBotManager.ensureAvailability() }.also { hubJob = it }
		asking.join()
	}

	private suspend fun readOverrides() {
		if (!lbBotManager.isConfigured) return
		overridesReading.value = true
		try {
			hearHub()
			if (lbBotManager.artistOverridesSupported.value) lbBotManager.refreshArtistOverrides()
		} finally {
			overridesReading.value = false
		}
	}

	private suspend fun readListeningStatus(): LbBotManager.LbResult<LbListeningStatus>? {
		if (!lbBotManager.isConfigured) return null
		hearHub()
		currentCoroutineContext().ensureActive()
		if (!lbBotManager.listeningSupported.value) {
			// Nothing to read. With the hub heard the line names that; with it still silent the
			// question has been asked and went unanswered, which must not stay "Asking lb-bot…".
			listeningPhase.value = ListeningPhase.Unreachable
			return null
		}
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
			if (!lbBotManager.isConfigured) return@launch
			hearHub()
			currentCoroutineContext().ensureActive()
			if (!lbBotManager.listeningSupported.value) return@launch
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

	/**
	 * "Keep this match" (I-021): make lb-bot's own guess the user's. An ordinary set of the
	 * guessed id through the normal route — it becomes "set by you", the row leaves the list, and
	 * whether a scan follows is lb-bot's decision, as on the artist page.
	 */
	fun keepMatch(row: LbMatchToCheck) {
		if (_busyOverride.value != null || !matchCanKeep(row)) return
		_busyOverride.value = row.ndArtistId
		viewModelScope.launch {
			try {
				_overrideLine.value = overrideWriteLine(
					lbBotManager.setArtistOverride(row.ndArtistId, row.mbid, row.name)
				)
			} finally {
				_busyOverride.value = null
			}
		}
	}

	/** "Choose…": the same picker the artist page opens, for this row's artist. */
	fun choose(row: LbMatchToCheck) {
		_pick.value = MatchPick(row)
	}

	fun dismissPick() {
		// A write in flight is not cancelled by closing the sheet; its outcome is the line
		// under the lists.
		_pick.value = null
	}

	/**
	 * The picker's pick. An ordinary set, like [keepMatch]. A refusal is shown in the picker
	 * while it is still open; an lb-bot too old for the route closes it, and the line under the
	 * lists says so — never a sheet that closes as though it had saved.
	 */
	fun pick(mbid: String) {
		val picking = _pick.value ?: return
		if (picking.working) return
		val row = picking.row
		_pick.value = picking.copy(working = true, error = null)
		viewModelScope.launch {
			val result = lbBotManager.setArtistOverride(row.ndArtistId, mbid, row.name)
			val stillOpen = _pick.value?.row?.ndArtistId == row.ndArtistId
			when {
				result is LbBotManager.LbResult.Failed &&
					result.error != LbBotManager.LbError.RouteUnknown && stillOpen ->
					_pick.value = MatchPick(row, working = false, error = result.error)
				else -> {
					if (stillOpen) _pick.value = null
					_overrideLine.value = overrideWriteLine(result)
				}
			}
		}
	}

	private companion object {
		/** How many times the status is read after a sync request, and how far apart. */
		const val STATUS_READS = 12
		const val STATUS_READ_GAP_MS = 5_000L
	}
}
