package paige.navic.ui.screens.matches.viewmodels

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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import paige.navic.data.database.dao.AlbumDao
import paige.navic.data.database.dao.ArtistDao
import paige.navic.domain.manager.AlbumJob
import paige.navic.domain.manager.LbAlbumOverrideRow
import paige.navic.domain.manager.LbArtistOverride
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbIndexRun
import paige.navic.domain.manager.LbIndexSync
import paige.navic.domain.manager.LbMatchToCheck
import paige.navic.domain.manager.LbMergeRow
import paige.navic.domain.manager.LbMirrorInfo
import paige.navic.domain.manager.LbPanelData
import paige.navic.domain.manager.MergeJob
import paige.navic.domain.manager.albumRowCanUndo
import paige.navic.domain.manager.indexRunLineAfterTap
import paige.navic.domain.manager.matchCanKeep
import paige.navic.domain.manager.matchesSectionShown
import paige.navic.domain.manager.matchesToShow
import paige.navic.domain.manager.mergeRowCanUndo
import paige.navic.domain.manager.overrideListShown
import paige.navic.domain.manager.overrideWriteLine
import paige.navic.domain.manager.panelData
import paige.navic.domain.manager.panelReading
import paige.navic.domain.manager.undoLine

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

/**
 * One of the two lists that ride the overrides body — the album overrides (I-022) or the name
 * merges (I-023). [shown] false: no such section at all (`overrideListShown`).
 */
@Immutable
data class OverrideListPanel<T>(
	val shown: Boolean = false,
	val data: LbPanelData = LbPanelData.READING,
	val rows: List<T> = emptyList()
)

/** The MusicBrainz picker opened from a "matches to check" row, and its write's state. */
@Immutable
data class MatchPick(
	val row: LbMatchToCheck,
	val working: Boolean = false,
	val error: LbBotManager.LbError? = null
)

/**
 * "Library Matches" (I-026) — the page that was the "lb-bot data" section of the navi-connect
 * settings (I-020): the artists whose MusicBrainz identity is worth a look, the artist MBID
 * overrides, the albums lb-bot retagged, the artist names it merged, and the index mirror with
 * its own "Refresh". The listening history's status and "Sync now" are Stats'.
 *
 * It holds no data of its own. The lists are `LbBotManager`'s, the mirror's numbers and its
 * last pull are `LbIndexSync`'s, and the pictures are what Room holds; every sentence the page
 * shows is a pure function of those facts (`LbBotData.kt`, `LibraryMatches.kt`), so nothing here
 * can say "up to date" or "loading" on its own authority.
 */
class LibraryMatchesViewModel(
	private val lbBotManager: LbBotManager,
	private val lbIndexSync: LbIndexSync,
	private val artistDao: ArtistDao,
	private val albumDao: AlbumDao
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
	 * What Room holds, each id with its cover art id: the artists and the albums a row can show
	 * a picture of and link to (`libraryArtistArt`, `matchAlbumArt`, `heldInLibrary`). One query
	 * each for the whole page, never a request per row — "To check" alone can be a hundred rows.
	 * Read when the page reads and again whenever the local library has changed, so a retag or
	 * a merge that has settled shows its new album and drops its dead artist.
	 */
	private val _artistArt = MutableStateFlow<Map<String, String?>>(emptyMap())
	val artistArt = _artistArt.asStateFlow()

	private val _albumArt = MutableStateFlow<Map<String, String?>>(emptyMap())
	val albumArt = _albumArt.asStateFlow()

	init {
		viewModelScope.launch { lbBotManager.libraryRevision.drop(1).collect { readArt() } }
	}

	// ----- album overrides and name merges (I-022, I-023) ------------------------------------ //

	/**
	 * The albums lb-bot retagged and the artist names it merged. Both arrive in the body the
	 * artist overrides come in, so [overrides]'s verdict is theirs; each is ABSENT from an lb-bot
	 * that predates it or could not work it out, and there is then no section. In lb-bot's own
	 * order, which is newest first.
	 */
	val albumRows: StateFlow<OverrideListPanel<LbAlbumOverrideRow>> = combine(
		lbBotManager.albumOverrideRows,
		lbBotManager.albumOverridesSupported,
		overrides
	) { rows, controls, panel ->
		OverrideListPanel(overrideListShown(panel.data, rows, controls), panel.data, rows.orEmpty())
	}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OverrideListPanel())

	val mergeRows: StateFlow<OverrideListPanel<LbMergeRow>> = combine(
		lbBotManager.artistMerges,
		lbBotManager.artistMergesSupported,
		overrides
	) { rows, controls, panel ->
		OverrideListPanel(overrideListShown(panel.data, rows, controls), panel.data, rows.orEmpty())
	}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OverrideListPanel())

	/**
	 * The jobs the manager follows. A row one of them is still working on has no Undo to press
	 * (`albumJobInWay`, `mergeJobInWay`) — the manager would refuse it, unsent.
	 */
	val albumJobs = lbBotManager.albumJobs
	val mergeJobs = lbBotManager.mergeJobs

	/** The row an Undo request from this panel is out for, so the buttons can wait. */
	private val _busyUndo = MutableStateFlow<String?>(null)
	val busyUndo = _busyUndo.asStateFlow()

	/** The last Undo pressed here, and what lb-bot answered the request with. */
	private data class UndoAnswered(
		val rowId: String,
		val merge: Boolean,
		val result: LbBotManager.LbResult<*>
	)

	private val undoAnswered = MutableStateFlow<UndoAnswered?>(null)

	/**
	 * What the last Undo came to, for as long as it is true (`undoLine`): the refusal; while the
	 * manager follows the job, that lb-bot is at work; then how it ended. The job read is the
	 * one this undo started — not the apply or the merge that made the row.
	 */
	val undoLine: StateFlow<String> = combine(
		undoAnswered,
		lbBotManager.albumJobs,
		lbBotManager.mergeJobs
	) { asked, albums, merges ->
		if (asked == null) {
			""
		} else {
			undoLine(
				started = asked.result,
				merge = asked.merge,
				phase = if (asked.merge) {
					merges[asked.rowId]?.poll?.takeIf { it.job == MergeJob.UNDO }?.phase
				} else {
					albums[asked.rowId]?.poll?.takeIf { it.job == AlbumJob.REMOVE }?.phase
				}
			)
		}
	}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

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

	/** "Refresh" is at work: the button is disabled, and says so, until this page's lists are back. */
	private val _refreshing = MutableStateFlow(false)
	val refreshing = _refreshing.asStateFlow()

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
	/** Both reads at once; the returned job ends with the later of them (a pull-to-refresh's end). */
	fun read(): Job = viewModelScope.launch {
		launch { readOverrides() }
		launch { readArt() }
	}

	private suspend fun readArt() {
		runCatching { artistDao.getArtistArt().associate { it.artistId to it.coverArtId } }
			.onSuccess { _artistArt.value = it }
		runCatching { albumDao.getAlbumFingerprints().associate { it.albumId to it.coverArtId as String? } }
			.onSuccess { _albumArt.value = it }
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

	/**
	 * "Refresh", at the foot of the page: pull the index mirror and read this page's own lists
	 * again. **It reports only what the mirror did** ([indexLine]) — what `LbIndexSync` records
	 * as the pull runs and ends, and, until a pass this tap can have caused has started, that the
	 * pull was asked for. The lists say of themselves what they are. The listening history's
	 * "Sync now" is Stats', and does and reports that half alone.
	 *
	 * The pull is requested as a fresh start: a user asking now is as good a reason to stop
	 * waiting out a backoff as a new connection is.
	 */
	fun refresh() {
		if (_refreshing.value) return
		_refreshing.value = true

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

		viewModelScope.launch {
			try {
				readOverrides()
				readArt()
			} finally {
				_refreshing.value = false
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

	/**
	 * Undo an album override (I-022): lb-bot puts the previous tags and cover back, in the
	 * background. The manager follows the job and settles the library, exactly as when the
	 * album's own sheet asks; [undoLine] says what the request and then the job came to, and the
	 * row leaves the list when lb-bot has finished.
	 */
	fun undoAlbum(row: LbAlbumOverrideRow) {
		if (_busyUndo.value != null || !albumRowCanUndo(row)) return
		_busyUndo.value = row.id
		viewModelScope.launch {
			try {
				// `albumId` is the album's current id, which the undo of a retag kills.
				val result = lbBotManager.removeAlbumOverride(row.id, row.albumId)
				undoAnswered.value = UndoAnswered(row.id, merge = false, result = result)
			} finally {
				_busyUndo.value = null
			}
		}
	}

	/**
	 * Undo a name merge (I-023): lb-bot puts the previous names back. The row names both
	 * artists — the rewritten spelling by the id it had when it was merged — and the manager is
	 * given what the row has.
	 */
	fun undoMerge(row: LbMergeRow) {
		if (_busyUndo.value != null || !mergeRowCanUndo(row)) return
		_busyUndo.value = row.id
		viewModelScope.launch {
			try {
				val result = lbBotManager.undoArtistMerge(
					id = row.id,
					intoId = row.into.ndArtistId,
					fromId = row.from.ndArtistId,
					intoName = row.into.name,
					fromName = row.from.name
				)
				undoAnswered.value = UndoAnswered(row.id, merge = true, result = result)
			} finally {
				_busyUndo.value = null
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
		/** How long after a tap, with nothing running, "no pull has started" is said. */
		const val PULL_START_WAIT_MS = 5_000L
	}
}
