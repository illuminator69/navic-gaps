package paige.navic.ui.screens.listening.viewmodels

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import paige.navic.data.database.dao.AlbumDao
import paige.navic.data.database.dao.ArtistDao
import paige.navic.domain.manager.ConnectivityManager
import paige.navic.domain.manager.LISTENING_KIND_ALBUM
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbCachePolicy
import paige.navic.domain.manager.LbListeningStatus
import paige.navic.domain.manager.LbListeningSummary
import paige.navic.domain.manager.LbRecentPlay
import paige.navic.domain.manager.LbUnownedRow
import paige.navic.domain.manager.ListeningPeriod
import paige.navic.domain.manager.ListeningPhase
import paige.navic.domain.manager.importFinished
import paige.navic.domain.manager.listeningPhase
import paige.navic.domain.manager.phaseAfterPoll
import paige.navic.domain.manager.summaryNeedsRefresh
import paige.navic.util.Logger

@Immutable
data class ListeningUi(
	/** The status is being read — the first open, a pull-to-refresh, a Retry. */
	val loading: Boolean = true,
	val phase: ListeningPhase = ListeningPhase.Loading,
	val period: ListeningPeriod = ListeningPeriod.MONTH,
	/** The summary for [period], or null: not read yet, or [summaryFailed]. Never another period's. */
	val summary: LbListeningSummary? = null,
	val summaryLoading: Boolean = false,
	/**
	 * A read ended with no summary at all — nothing cached and nothing came back. A failure, and
	 * rendered as one with a Retry: it is NOT an empty history, which is a summary of zeros.
	 */
	val summaryFailed: Boolean = false,
	val unownedKind: String = LISTENING_KIND_ALBUM,
	val unowned: List<LbUnownedRow> = emptyList(),
	val unownedLoading: Boolean = false,
	val unownedFailed: Boolean = false,
	val recent: List<LbRecentPlay> = emptyList(),
	/** `before` for the next page of [recent]; null on the last page. */
	val recentNextBefore: Double? = null,
	val recentLoading: Boolean = false,
	val recentFailed: Boolean = false,
	/** The album and artist ids Room holds: an id is only a link when its page exists. */
	val knownAlbumIds: Set<String> = emptySet(),
	val knownArtistIds: Set<String> = emptySet()
)

/**
 * My Listening (I-006): aggregates over the user's ListenBrainz history, mirrored and computed by
 * lb-bot. Nothing is computed here — every number on the screen is a field of lb-bot's answer —
 * and every decision about those answers is a pure function in `Listening.kt`.
 *
 * Three reads, of three kinds:
 * - the **status**, never cached, which decides what the page is at all ([ListeningPhase]) and is
 *   the one thing polled, and only while an import runs and the screen is visible ([pollStatus]);
 * - the **summary**, stale-while-revalidate over `lb_response_cache`, so the last body for a
 *   period paints first and offline;
 * - the **unowned** list and **recent plays**, plain reads: an ownership answer and a live list,
 *   neither worth painting stale.
 *
 * Rows link out and never download. Nothing here starts an acquisition.
 */
class ListeningViewModel(
	private val lbBotManager: LbBotManager,
	private val albumDao: AlbumDao,
	private val artistDao: ArtistDao,
	connectivityManager: ConnectivityManager
) : ViewModel() {
	private val isOnline = connectivityManager.isOnline

	private val _state = MutableStateFlow(ListeningUi())
	val state = _state.asStateFlow()

	/** The status as last read: its `lastSyncAt` is what a summary's age is judged against. */
	private var status: LbListeningStatus? = null

	private var loadJob: Job? = null
	private var unownedJob: Job? = null
	private var recentJob: Job? = null

	init { load() }

	/** The device's IANA zone, read per request: lb-bot runs in UTC and cannot know it. */
	private fun zone(): String = TimeZone.currentSystemDefault().id

	/**
	 * Read the status, then whatever it says there is to show. [policy] is the summary's:
	 * [LbCachePolicy.REFRESH] for a pull-to-refresh or a Retry.
	 *
	 * Offline, or with lb-bot not answering, the status cannot be read and the page falls back
	 * to the cached summary for the period — and to a failure line with a Retry when there is
	 * none, never to a blank page.
	 */
	fun load(policy: LbCachePolicy = LbCachePolicy.NORMAL) {
		loadJob?.cancel()
		loadJob = viewModelScope.launch {
			_state.update { it.copy(loading = true) }
			readKnownIds()
			val result = if (isOnline.value) {
				lbBotManager.listeningStatus()
			} else {
				LbBotManager.LbResult.Failed(LbBotManager.LbError.Unreachable("offline"))
			}
			stillWanted()
			status = (result as? LbBotManager.LbResult.Ok)?.value ?: status
			val phase = listeningPhase(result)
			_state.update { it.copy(loading = false, phase = phase) }
			readContent(phase, policy)
		}
	}

	/** Pull-to-refresh and Retry. */
	fun refresh() = load(LbCachePolicy.REFRESH)

	/**
	 * One poll of the status. The screen calls this every few seconds, and only while the phase
	 * is [ListeningPhase.Importing] and the screen is visible — the count moving is the progress
	 * readout. When the import ends the summary and the unowned list are read again: what was
	 * read mid-import covers only part of the history, and without the "importing" banner it
	 * would read as the whole of it.
	 */
	suspend fun pollStatus() {
		val previous = _state.value.phase
		if (previous !is ListeningPhase.Importing) return
		val result = lbBotManager.listeningStatus()
		stillWanted()
		(result as? LbBotManager.LbResult.Ok)?.let { status = it.value }
		val next = phaseAfterPoll(previous, listeningPhase(result))
		_state.update { it.copy(phase = next) }
		if (importFinished(previous, next)) {
			loadJob?.cancel()
			loadJob = viewModelScope.launch { readContent(next, LbCachePolicy.REFRESH) }
		}
	}

	fun setPeriod(period: ListeningPeriod) {
		if (period == _state.value.period) return
		// The other period's numbers must not sit under this period's chip, even for a moment.
		_state.update {
			it.copy(period = period, summary = null, summaryFailed = false, unowned = emptyList())
		}
		// The status has not answered yet (the first open, a refresh in flight): cancelling
		// that read here would leave the page loading for good, so it starts over instead.
		if (_state.value.loading) {
			load()
			return
		}
		val phase = _state.value.phase
		loadJob?.cancel()
		loadJob = viewModelScope.launch {
			when (phase) {
				ListeningPhase.Unreachable -> readSummary(LbCachePolicy.CACHE_ONLY)
				is ListeningPhase.Importing, ListeningPhase.Ready, is ListeningPhase.SyncFailed ->
					coroutineScope {
						launch { readSummaryFollowingSync(LbCachePolicy.NORMAL) }
						launch { readUnowned() }
					}
				else -> Unit
			}
		}
	}

	fun setUnownedKind(kind: String) {
		if (kind == _state.value.unownedKind) return
		_state.update { it.copy(unownedKind = kind, unowned = emptyList()) }
		unownedJob?.cancel()
		unownedJob = viewModelScope.launch { readUnowned() }
	}

	/** The next page of recent plays, appended. A failure keeps the pages already shown. */
	fun loadMoreRecent() {
		val before = _state.value.recentNextBefore ?: return
		if (recentJob?.isActive == true) return
		recentJob = viewModelScope.launch {
			_state.update { it.copy(recentLoading = true, recentFailed = false) }
			val result = lbBotManager.listeningRecent(before)
			stillWanted()
			when (result) {
				is LbBotManager.LbResult.Ok -> _state.update {
					it.copy(
						recent = it.recent + result.value.rows,
						recentNextBefore = result.value.nextBefore,
						recentLoading = false
					)
				}
				is LbBotManager.LbResult.Failed ->
					_state.update { it.copy(recentLoading = false, recentFailed = true) }
			}
		}
	}

	/** Retry for a list that failed on its own while the rest of the page loaded. */
	fun retryUnowned() {
		unownedJob?.cancel()
		unownedJob = viewModelScope.launch { readUnowned() }
	}

	fun retryRecent() {
		if (_state.value.recent.isNotEmpty()) {
			loadMoreRecent()
			return
		}
		recentJob?.cancel()
		recentJob = viewModelScope.launch { readRecent() }
	}

	// ------------------------------------------------------------------ //

	private suspend fun readContent(phase: ListeningPhase, policy: LbCachePolicy) {
		when (phase) {
			// Nothing to show and nothing to ask: no ListenBrainz user, or no such routes.
			ListeningPhase.Off, ListeningPhase.Older, ListeningPhase.Loading -> _state.update {
				it.copy(
					summary = null, summaryFailed = false, summaryLoading = false,
					unowned = emptyList(), unownedFailed = false,
					recent = emptyList(), recentNextBefore = null, recentFailed = false
				)
			}
			// lb-bot did not answer: the summary this device last saw, and only that. The two
			// lists are live reads and keep whatever they showed.
			ListeningPhase.Unreachable -> readSummary(LbCachePolicy.CACHE_ONLY)
			// Three requests at once, under the hub's four shared lb-bot slots.
			is ListeningPhase.Importing, ListeningPhase.Ready, is ListeningPhase.SyncFailed ->
				coroutineScope {
					launch { readSummaryFollowingSync(policy) }
					launch { readUnowned() }
					launch { readRecent() }
				}
		}
	}

	/**
	 * Read the summary, then read it again when it is older than the mirror's last sync
	 * ([summaryNeedsRefresh]) — a body cached mid-import, or before the latest pull, is missing
	 * listens the mirror now holds, and its age alone does not say so.
	 *
	 * The hub keeps its own copy of the summary for a minute and a sync landing on lb-bot does
	 * not flush it, so the re-read can be handed the very body it was meant to replace. When it
	 * is still older than the sync, one more read is made once that copy has expired, and the
	 * unowned list (cached the same way) with it.
	 */
	private suspend fun readSummaryFollowingSync(policy: LbCachePolicy) {
		var summary = readSummary(policy) ?: return
		val lastSyncAt = status?.lastSyncAt
		if (!summaryNeedsRefresh(summary.generatedAtSeconds, lastSyncAt)) return
		if (policy != LbCachePolicy.REFRESH) {
			summary = readSummary(LbCachePolicy.REFRESH) ?: return
			if (!summaryNeedsRefresh(summary.generatedAtSeconds, lastSyncAt)) return
		}
		// A summary whose time this build cannot read is always "older"; that is not a reason
		// to keep asking.
		if (summary.generatedAtSeconds <= 0.0) return
		delay(HUB_CACHE_EXPIRY_MS)
		readSummary(LbCachePolicy.REFRESH)
		readUnowned()
	}

	/**
	 * One read of the summary for the period on screen. The cached body is painted as soon as
	 * Room hands it over, the network's answer when it differs. A read that brings nothing
	 * never replaces a body; with no body at all it is [ListeningUi.summaryFailed].
	 */
	private suspend fun readSummary(policy: LbCachePolicy): LbListeningSummary? {
		val period = _state.value.period
		_state.update { it.copy(summaryLoading = true, summaryFailed = false) }
		var last: LbListeningSummary? = null
		lbBotManager.listeningSummary(period, zone(), policy).collect { body ->
			val summary = body?.takeIf { it.isAnswer } ?: return@collect
			if (_state.value.period != period) return@collect
			last = summary
			_state.update { it.copy(summary = summary) }
		}
		if (_state.value.period == period) {
			_state.update { it.copy(summaryLoading = false, summaryFailed = it.summary == null) }
		}
		return last
	}

	private suspend fun readUnowned() {
		val period = _state.value.period
		val kind = _state.value.unownedKind
		_state.update { it.copy(unownedLoading = true, unownedFailed = false) }
		val result = lbBotManager.listeningUnowned(period, kind)
		stillWanted()
		if (_state.value.period != period || _state.value.unownedKind != kind) return
		when (result) {
			is LbBotManager.LbResult.Ok ->
				_state.update { it.copy(unowned = result.value.rows, unownedLoading = false) }
			is LbBotManager.LbResult.Failed ->
				_state.update { it.copy(unownedLoading = false, unownedFailed = true) }
		}
	}

	/** The first page. A failure keeps the pages already shown rather than emptying the list. */
	private suspend fun readRecent() {
		_state.update { it.copy(recentLoading = true, recentFailed = false) }
		val result = lbBotManager.listeningRecent()
		stillWanted()
		when (result) {
			is LbBotManager.LbResult.Ok -> _state.update {
				it.copy(
					recent = result.value.rows,
					recentNextBefore = result.value.nextBefore,
					recentLoading = false
				)
			}
			is LbBotManager.LbResult.Failed ->
				_state.update { it.copy(recentLoading = false, recentFailed = true) }
		}
	}

	/**
	 * After every manager call. `LbBotManager` answers a cancelled request as a failure rather
	 * than rethrowing, and a read this view model has just superseded (a new period, a refresh)
	 * must not go on to paint "can't reach lb-bot" over the read that replaced it.
	 */
	private suspend fun stillWanted() = currentCoroutineContext().ensureActive()

	/**
	 * Which album and artist ids have a page here. lb-bot's ids are Navidrome's own, but this
	 * device only has a page for what it has synced, and only album artists have a row at all.
	 */
	private suspend fun readKnownIds() {
		val albums = runCatching { albumDao.getAllAlbumIds().toSet() }
			.onFailure { Logger.w("ListeningViewModel", "album ids read failed", it) }
			.getOrNull()
		val artists = runCatching { artistDao.getAllArtistIds().toSet() }
			.onFailure { Logger.w("ListeningViewModel", "artist ids read failed", it) }
			.getOrNull()
		_state.update {
			it.copy(
				knownAlbumIds = albums ?: it.knownAlbumIds,
				knownArtistIds = artists ?: it.knownArtistIds
			)
		}
	}

	private companion object {
		/** The hub's lb-bot result cache lives 60 s; a little past that, its copy is gone. */
		const val HUB_CACHE_EXPIRY_MS = 65_000L
	}
}
