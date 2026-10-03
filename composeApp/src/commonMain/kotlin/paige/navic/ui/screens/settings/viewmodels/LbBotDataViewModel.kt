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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import paige.navic.data.database.dao.ArtistDao
import paige.navic.domain.manager.LbArtistOverride
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbIndexRun
import paige.navic.domain.manager.LbIndexSync
import paige.navic.domain.manager.LbListeningStatus
import paige.navic.domain.manager.LbMatchToCheck
import paige.navic.domain.manager.LbMirrorInfo
import paige.navic.domain.manager.LbPanelData
import paige.navic.domain.manager.ListeningPhase
import paige.navic.domain.manager.ListeningSyncOutcome
import paige.navic.domain.manager.formatListenedAt
import paige.navic.domain.manager.indexRunLineAfterTap
import paige.navic.domain.manager.listeningPhase
import paige.navic.domain.manager.listeningStatusLine
import paige.navic.domain.manager.listeningSyncBaseline
import paige.navic.domain.manager.listeningSyncLine
import paige.navic.domain.manager.listeningSyncOutcome
import paige.navic.domain.manager.matchCanKeep
import paige.navic.domain.manager.matchesSectionShown
import paige.navic.domain.manager.matchesToShow
import paige.navic.domain.manager.overrideWriteLine
import paige.navic.domain.manager.panelData
import paige.navic.domain.manager.panelReading

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

	/**
	 * How many reads of the overrides that THIS panel started are out. A count, not a flag:
	 * the panel opening and "Sync now" can each have one, and the first to come back must not
	 * end the state for the other.
	 */
	private val overrideReads = MutableStateFlow(0)

	/**
	 * True until the first read of a layer that is there has started. The section asks for that
	 * read as it appears, a moment after these flows are first collected; without this the list
	 * would say "lb-bot is not reachable" for the frame in between, about a read not yet made.
	 */
	private val overridesReadDue = MutableStateFlow(true)

	private val overridesReading = combine(overrideReads, overridesReadDue) { inFlight, due ->
		panelReading(inFlight, due)
	}

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

	/**
	 * "Sync now" as the mirror's line sees it: the pass serial as it stood at the tap, and
	 * whether a few seconds have since gone by with no pass running. Null before any tap.
	 */
	private data class IndexTap(val serial: Long, val overdue: Boolean = false)

	private val indexTap = MutableStateFlow<IndexTap?>(null)
	private var indexTapJob: Job? = null

	/**
	 * The mirror's last pull in words, live. After a tap it is never the EARLIER pass's verdict
	 * shown as if it answered the tap: until a pass newer than the one seen at the tap has
	 * started it says the pull has been asked for, and then, if none starts, that none did
	 * (`indexRunLineAfterTap`, over `LbIndexRun.serial`).
	 */
	val indexLine: StateFlow<String> = combine(lbIndexSync.lastRun, indexTap) { run, tap ->
		indexRunLineAfterTap(run, tap?.serial, tap?.overdue == true)
	}.stateIn(
		viewModelScope,
		SharingStarted.WhileSubscribed(5000),
		indexRunLineAfterTap(lbIndexSync.lastRun.value, null, false)
	)

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

	/**
	 * "Sync now" is at work. The button is disabled and says so for as long as this is true —
	 * a second tap used to be swallowed without a sign for up to a minute.
	 */
	private val _syncing = MutableStateFlow(false)
	val syncing = _syncing.asStateFlow()

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
		if (!lbBotManager.isConfigured) {
			// No layer, nothing to read — and the one that appears next has not been read yet.
			overridesReadDue.value = true
			return
		}
		overrideReads.update { it + 1 }
		overridesReadDue.value = false
		try {
			hearHub()
			if (lbBotManager.artistOverridesSupported.value) lbBotManager.refreshArtistOverrides()
		} finally {
			overrideReads.update { it - 1 }
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
	 * - the mirror's line is [indexLine]: what `LbIndexSync` records as the pull runs and ends,
	 *   and, until a pass this tap can have caused has started, that the pull was asked for;
	 * - the listening line is the request's own answer, then the status it leads to. The
	 *   request returns at once, so "synced" is only said once `lastSyncAt` has moved past a
	 *   baseline read immediately before the request; until then it says the pull was asked for
	 *   and has not been seen to finish. The status is read a bounded number of times, and
	 *   running out of reads changes no claim — it just stops asking.
	 *
	 * The pull is requested as a fresh start: a user asking now is as good a reason to stop
	 * waiting out a backoff as a new connection is.
	 */
	fun syncNow() {
		if (_syncing.value) return
		_syncing.value = true

		// The serial BEFORE the request, so the pass the request causes is newer than it.
		val tapSerial = lbIndexSync.lastRun.value.serial
		indexTap.value = IndexTap(tapSerial)
		lbIndexSync.requestSync("manual", freshStart = true)
		indexTapJob?.cancel()
		indexTapJob = viewModelScope.launch {
			// The clock starts once nothing is running: a pass already under way at the tap is
			// not "no pull has started", however long it takes.
			lbIndexSync.lastRun.first { it.serial > tapSerial || it !is LbIndexRun.Running }
			delay(PULL_START_WAIT_MS)
			indexTap.update { tap -> if (tap?.serial == tapSerial) tap.copy(overdue = true) else tap }
		}

		viewModelScope.launch { readOverrides() }
		viewModelScope.launch {
			try {
				syncListening()
			} finally {
				_syncing.value = false
			}
		}
	}

	private suspend fun syncListening() {
		if (!lbBotManager.isConfigured) return
		hearHub()
		currentCoroutineContext().ensureActive()
		if (!lbBotManager.listeningSupported.value) return
		listeningSync.value = ListeningSyncOutcome.Asking
		// The baseline is read NOW, immediately before the request. What the panel last showed
		// may be minutes old — or was never read — and judging against it would credit a sync
		// that finished before the tap to the tap. When this read fails there is no baseline,
		// and nothing is called finished until a later read supplies one.
		var baseline = listeningSyncBaseline(null, readListeningStatus())
		val post = lbBotManager.listeningSync()
		currentCoroutineContext().ensureActive()
		listeningSync.value = listeningSyncOutcome(baseline, post, null)
		if (post !is LbBotManager.LbResult.Ok) return
		repeat(STATUS_READS) { attempt ->
			if (attempt > 0) delay(STATUS_READ_GAP_MS)
			val after = readListeningStatus() ?: return
			val outcome = listeningSyncOutcome(baseline, post, after)
			listeningSync.value = outcome
			baseline = listeningSyncBaseline(baseline, after)
			// Anything but "not seen to finish yet" (or a status that could not be read, which
			// is not an answer) is the answer.
			if (outcome != ListeningSyncOutcome.Waiting && outcome != ListeningSyncOutcome.AskedUnread) {
				return
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
				_overrideLine.value = overrideWriteLine(
					lbBotManager.removeArtistOverride(ndArtistId),
					removing = true
				)
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
					lbBotManager.setArtistOverride(row.ndArtistId, row.mbid, row.name),
					removing = false
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
					_overrideLine.value = overrideWriteLine(result, removing = false)
				}
			}
		}
	}

	private companion object {
		/** How many times the status is read after a sync request, and how far apart. */
		const val STATUS_READS = 12
		const val STATUS_READ_GAP_MS = 5_000L

		/** How long after a tap, with nothing running, "no pull has started" is said. */
		const val PULL_START_WAIT_MS = 5_000L
	}
}
