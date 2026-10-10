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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import paige.navic.data.database.dao.AlbumDao
import paige.navic.data.database.dao.ArtistDao
import paige.navic.data.database.mappers.toDomainModel
import paige.navic.domain.manager.HistoryMenu
import paige.navic.domain.manager.HistorySong
import paige.navic.domain.manager.heldInLibrary
import paige.navic.domain.manager.historyMenu
import paige.navic.domain.models.DomainAlbum
import paige.navic.domain.models.DomainArtist
import paige.navic.domain.models.DomainSong
import paige.navic.domain.manager.ConnectivityManager
import paige.navic.domain.manager.LISTENING_KIND_ALBUM
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbCachePolicy
import paige.navic.domain.manager.LbListeningStatus
import paige.navic.domain.manager.LbListeningSummary
import paige.navic.domain.manager.LbRecentPlay
import paige.navic.domain.manager.LbUnownedRow
import paige.navic.domain.manager.ListeningListFailure
import paige.navic.domain.manager.ListeningPeriod
import paige.navic.domain.manager.ListeningPhase
import paige.navic.domain.manager.ListeningSyncOutcome
import paige.navic.domain.manager.formatListenedAt
import paige.navic.domain.manager.importFinished
import paige.navic.domain.manager.listeningListFailure
import paige.navic.domain.manager.listeningPhase
import paige.navic.domain.manager.listeningStatusLine
import paige.navic.domain.manager.listeningSyncBaseline
import paige.navic.domain.manager.listeningSyncLine
import paige.navic.domain.manager.listeningSyncOutcome
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
	/**
	 * Why the unowned list could not be read, or null. Not a flag: lb-bot says why it refused
	 * (a 503 while Navidrome's lists are unavailable, a 400), and an lb-bot older than the route
	 * is a fact, not a failure to retry (`listeningListFailure`).
	 */
	val unownedFailure: ListeningListFailure? = null,
	val recent: List<LbRecentPlay> = emptyList(),
	/** `before` for the next page of [recent]; null on the last page. */
	val recentNextBefore: Double? = null,
	val recentLoading: Boolean = false,
	val recentFailed: Boolean = false,
	/**
	 * The albums and artists Room holds, each id with its cover art id. An id is only a link
	 * when its page exists ([knownAlbumIds], [knownArtistIds]) — and what Room holds is also
	 * what a card or a row can draw the library's own picture of (`albumCardArt`,
	 * `libraryArtistArt`). One query each for the whole page.
	 */
	val albumArt: Map<String, String?> = emptyMap(),
	val artistArt: Map<String, String?> = emptyMap()
) {
	val knownAlbumIds: Set<String> get() = albumArt.keys
	val knownArtistIds: Set<String> get() = artistArt.keys
}

/** What a History row's menu was opened on: the library's own song, album or artist. */
sealed interface HistoryMenuUi {
	data class Song(val song: DomainSong) : HistoryMenuUi
	data class Album(val album: DomainAlbum) : HistoryMenuUi
	data class Artist(val artist: DomainArtist) : HistoryMenuUi
}

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

	/**
	 * The status as last read: its `lastSyncAt` is what a summary's age is judged against, and
	 * its count and last sync are the status line's ([statusLine]).
	 */
	private val _status = MutableStateFlow<LbListeningStatus?>(null)
	private var status: LbListeningStatus?
		get() = _status.value
		set(value) { _status.value = value }

	// ----- the history's own status line and "Sync now" --------------------------------------- //
	// Both lived in the "lb-bot data" section of the settings, beside the index mirror, under one
	// "Sync now" that did two things. They are this page's now (I-026), and the button does and
	// reports the listening half alone; the mirror has its own Refresh on Library Matches.

	/**
	 * The mirror's state in words (`listeningStatusLine`): how many plays lb-bot holds and when
	 * it last synced. Every figure is the status's own.
	 */
	val statusLine: StateFlow<String> = combine(
		lbBotManager.hubHeard,
		lbBotManager.listeningSupported,
		_state,
		_status
	) { hubHeard, advertised, ui, read ->
		listeningStatusLine(
			hubHeard = hubHeard,
			advertised = advertised,
			phase = ui.phase,
			count = read?.count ?: 0,
			lastSync = read?.lastSyncAt
				?.let { formatListenedAt(it, TimeZone.currentSystemDefault()) }.orEmpty()
		)
	}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

	/** What "Sync now" came to; null before it was pressed. */
	private val syncOutcome = MutableStateFlow<ListeningSyncOutcome?>(null)
	val syncLine: StateFlow<String> = syncOutcome
		.map { outcome -> if (outcome == null) "" else listeningSyncLine(outcome) }
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

	/**
	 * "Sync now" is at work. The button is disabled and says so for as long as this is true —
	 * a second tap would otherwise do nothing and show nothing.
	 */
	private val _syncing = MutableStateFlow(false)
	val syncing = _syncing.asStateFlow()

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
			// This replaces whatever [load] was doing — possibly a pull-to-refresh that has
			// set `loading` and not yet heard back. The status it was waiting for is the one
			// just read, so the spinner it owns ends here, not at the next pull.
			loadJob?.cancel()
			_state.update { it.copy(loading = false) }
			loadJob = viewModelScope.launch { readContent(next, LbCachePolicy.REFRESH) }
		}
	}

	/**
	 * "Sync now": ask lb-bot to pull from ListenBrainz, and report what happened — the request's
	 * own answer, then the status it leads to (`listeningSyncOutcome`). The request returns at
	 * once, so "synced" is only said once `lastSyncAt` has moved past a baseline read
	 * **immediately before the request**: what the page last showed may be minutes old, and
	 * judging against it would credit a sync that finished before the tap to the tap. The status
	 * is read a bounded number of times, and running out of reads changes no claim — it just
	 * stops asking. When the sync is seen to have finished the page is read again: what it shows
	 * was computed before the plays that just arrived.
	 */
	fun syncNow() {
		if (_syncing.value) return
		_syncing.value = true
		viewModelScope.launch {
			try {
				syncListening()
			} finally {
				_syncing.value = false
			}
		}
	}

	/** One status read for the sync: an answer also moves what the page says the mirror is. */
	private suspend fun readStatusForSync(): LbBotManager.LbResult<LbListeningStatus> {
		val result = lbBotManager.listeningStatus()
		stillWanted()
		(result as? LbBotManager.LbResult.Ok)?.let {
			status = it.value
			val phase = listeningPhase(result)
			_state.update { ui -> ui.copy(phase = phase) }
		}
		return result
	}

	private suspend fun syncListening() {
		if (!lbBotManager.isConfigured) return
		// A hub that has not spoken in this process is asked once, so its silence is not read
		// as "this hub does not offer it".
		if (!lbBotManager.hubHeard.value) lbBotManager.ensureAvailability()
		stillWanted()
		if (!lbBotManager.listeningSupported.value) return
		syncOutcome.value = ListeningSyncOutcome.Asking
		var baseline = listeningSyncBaseline(null, readStatusForSync())
		val post = lbBotManager.listeningSync()
		stillWanted()
		syncOutcome.value = listeningSyncOutcome(baseline, post, null)
		if (post !is LbBotManager.LbResult.Ok) return
		repeat(SYNC_STATUS_READS) { attempt ->
			if (attempt > 0) delay(SYNC_STATUS_READ_GAP_MS)
			val after = readStatusForSync()
			val outcome = listeningSyncOutcome(baseline, post, after)
			syncOutcome.value = outcome
			baseline = listeningSyncBaseline(baseline, after)
			// Anything but "not seen to finish yet" (or a status that could not be read, which
			// is not an answer) is the answer.
			if (outcome != ListeningSyncOutcome.Waiting && outcome != ListeningSyncOutcome.AskedUnread) {
				if (outcome is ListeningSyncOutcome.Finished) load(LbCachePolicy.REFRESH)
				return
			}
		}
	}

	// ----- a History row's menu ----------------------------------------------------------------- //

	private val _historyMenu = MutableStateFlow<HistoryMenuUi?>(null)
	val historyMenu = _historyMenu.asStateFlow()

	/**
	 * A long press on a recent play. The play carries no Navidrome song id, so the song is
	 * looked for now, among the songs of the album Room files it under — and **a menu is only
	 * opened on a song when exactly one fits** (`historyMenu`: by the recording id, else by
	 * title). Otherwise it is the album's menu, or the artist's when the library holds no album
	 * for the play, or nothing.
	 */
	fun openHistoryMenu(row: LbRecentPlay) {
		viewModelScope.launch {
			val album = row.ndAlbumId.takeIf { it.isNotBlank() }?.let { id ->
				runCatching { albumDao.getAlbumById(id)?.toDomainModel() }.getOrNull()
			}
			val songs = album?.songs?.map { HistorySong(it.id, it.title, it.musicBrainzId.orEmpty()) }
			val artistHeld = heldInLibrary(row.ndArtistId, _state.value.artistArt)
			_historyMenu.value = when (val menu = historyMenu(row, songs, artistHeld)) {
				is HistoryMenu.Song ->
					album?.songs?.firstOrNull { it.id == menu.songId }?.let { HistoryMenuUi.Song(it) }
				is HistoryMenu.Album -> album?.let { HistoryMenuUi.Album(it) }
				is HistoryMenu.Artist ->
					runCatching { artistDao.getArtistById(menu.artistId)?.toDomainModel() }.getOrNull()
						?.let { HistoryMenuUi.Artist(it) }
				HistoryMenu.None -> null
			}
		}
	}

	fun dismissHistoryMenu() {
		_historyMenu.value = null
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
			// The list was replaced while this page was in flight: the cursor it was asked with is
			// gone, and appending it would repeat rows under the new first page. Every path that
			// re-reads or clears the list cancels this job first, so this is a safety net — and
			// a page that is dropped here ends the spinner it set, since nothing else would.
			if (_state.value.recentNextBefore != before) {
				_state.update { it.copy(recentLoading = false) }
				return@launch
			}
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
			ListeningPhase.Off, ListeningPhase.Older, ListeningPhase.Loading -> {
				// The recent list's read is its own job now, so the load being replaced no
				// longer stops it: a page still in flight must not land rows on a page that
				// has just been emptied, or leave its spinner behind.
				recentJob?.cancel()
				_state.update {
					it.copy(
						summary = null, summaryFailed = false, summaryLoading = false,
						unowned = emptyList(), unownedFailure = null,
						recent = emptyList(), recentNextBefore = null, recentFailed = false,
						recentLoading = false
					)
				}
			}
			// lb-bot did not answer: the summary this device last saw, and only that. The two
			// lists are live reads and keep whatever they showed.
			ListeningPhase.Unreachable -> readSummary(LbCachePolicy.CACHE_ONLY)
			// Three requests at once, under the hub's four shared lb-bot slots.
			is ListeningPhase.Importing, ListeningPhase.Ready, is ListeningPhase.SyncFailed -> {
				// The recent list does not depend on the period, so its first page is NOT this
				// load's child. As one, a period change — which cancels the load and re-reads
				// only what the period decides — cancelled it halfway, after it had set
				// `recentLoading`, and nothing cleared the spinner or read the list again. It
				// runs under [recentJob], which only a re-read of the list itself replaces (and
				// which a "Show more" page in flight, belonging to the list about to be
				// replaced, gives way to here).
				recentJob?.cancel()
				val firstPage = viewModelScope.launch { readRecent() }
				recentJob = firstPage
				coroutineScope {
					launch { readSummaryFollowingSync(policy) }
					launch { readUnowned() }
					// The load still ends when all three have; cancelling the load only stops
					// the wait, not the read.
					launch { firstPage.join() }
				}
			}
		}
	}

	/**
	 * Read the summary, then read it again when it is older than the mirror's last sync
	 * ([summaryNeedsRefresh]) — a body cached mid-import, or before the latest pull, is missing
	 * listens the mirror now holds, and its age alone does not say so.
	 *
	 * A current hub passes the summary straight through, and the re-read then gets lb-bot's
	 * own answer. **The second read below is a fallback for an older hub**, which kept its own
	 * copy of the summary (and of the unowned list) for a minute that a sync landing on lb-bot
	 * did not flush: there the re-read can be handed the very body it was meant to replace, so
	 * when the answer is still older than the sync one more read is made once that copy has
	 * expired. Against a current hub the first re-read is already newer and this returns.
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
		_state.update { it.copy(unownedLoading = true, unownedFailure = null) }
		val result = lbBotManager.listeningUnowned(period, kind)
		stillWanted()
		if (_state.value.period != period || _state.value.unownedKind != kind) return
		when (result) {
			is LbBotManager.LbResult.Ok ->
				_state.update { it.copy(unowned = result.value.rows, unownedLoading = false) }
			is LbBotManager.LbResult.Failed -> _state.update {
				it.copy(unownedLoading = false, unownedFailure = listeningListFailure(result.error))
			}
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
		val albums = runCatching {
			albumDao.getAlbumFingerprints().associate { it.albumId to it.coverArtId as String? }
		}.onFailure { Logger.w("ListeningViewModel", "album ids read failed", it) }.getOrNull()
		val artists = runCatching {
			artistDao.getArtistArt().associate { it.artistId to it.coverArtId }
		}.onFailure { Logger.w("ListeningViewModel", "artist ids read failed", it) }.getOrNull()
		_state.update {
			it.copy(
				albumArt = albums ?: it.albumArt,
				artistArt = artists ?: it.artistArt
			)
		}
	}

	private companion object {
		/**
		 * An OLDER hub's lb-bot result cache lived 60 s; a little past that, its copy is gone.
		 * A current hub does not cache the listening routes at all, and never gets this far.
		 */
		const val HUB_CACHE_EXPIRY_MS = 65_000L

		/** How many times the status is read after a sync request, and how far apart. */
		const val SYNC_STATUS_READS = 12
		const val SYNC_STATUS_READ_GAP_MS = 5_000L
	}
}
