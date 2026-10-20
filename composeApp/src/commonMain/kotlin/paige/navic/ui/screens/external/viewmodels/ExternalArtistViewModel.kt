package paige.navic.ui.screens.external.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import paige.navic.domain.manager.ConnectivityManager
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbCachePolicy
import paige.navic.domain.manager.LbMeta
import paige.navic.domain.manager.LbDiscography
import paige.navic.domain.manager.LbIndexSync
import paige.navic.domain.manager.LbRescanStart
import paige.navic.domain.manager.LbScanOutcome
import paige.navic.domain.manager.LbRelease
import paige.navic.util.Logger
import paige.navic.ui.screens.artist.viewmodels.DiscographyEntry
import paige.navic.ui.screens.artist.viewmodels.DiscographySection
import paige.navic.ui.screens.artist.viewmodels.DiscographyUi

/**
 * An artist the library does not have, rendered from lb-bot alone.
 *
 * Deliberately separate from `ArtistDetailViewModel`, which loads its artist from
 * Room and errors when it is absent — keeping that screen strictly Navidrome-backed
 * is what makes its DB error path correct, and an lb-bot artist has no Navidrome
 * row by definition.
 *
 * The id form is `mb:<artist-mbid>`, which lb-bot's discography route already
 * understands: it short-circuits the library match and classifies everything
 * `missing`. So every entry here is absent, and the shelf renders that honestly
 * rather than pretending to know about ownership it never looked up.
 *
 * **An artist lb-bot has never walked is scanned on arrival, once per visit (P4, call 7).** The
 * user chose this artist; a "Scan" button would be a tap with no information in it. Once means a
 * flag, not a check of what is shown: a mirror emission or a recreated composition must not
 * start a second walk of MusicBrainz's one request a second. A failure is never retried by
 * itself; the page's "Try again" calls [indexArtist].
 */
class ExternalArtistViewModel(
	private val artistMbid: String,
	private val artistName: String,
	private val lbBotManager: LbBotManager,
	private val lbIndexSync: LbIndexSync,
	private val connectivityManager: ConnectivityManager
) : ViewModel() {
	private val _discography = MutableStateFlow(DiscographyUi())
	val discography = _discography.asStateFlow()

	private val _name = MutableStateFlow(artistName)
	val name = _name.asStateFlow()

	/** Until the mirror or the network has answered, either way: the page shows it is loading. */
	private val _loading = MutableStateFlow(artistMbid.isNotBlank())
	val loading = _loading.asStateFlow()

	/** True once the index mirror has answered for this artist; the network is then never
	 *  asked again, and a network answer landing after it is dropped. Main-thread only. */
	private var fromMirror = false

	/** The discography last painted, from either source — the rescan's "before". */
	private var shown: LbDiscography? = null

	/** The scan on arrival has been started (or decided against); never twice in one visit. */
	private var autoScanned = false

	private val _meta = MutableStateFlow<LbMeta?>(null)

	/** lb-bot's About for this artist (`/lb/meta/artist`); null when it has none. */
	val meta = _meta.asStateFlow()

	private val _aboutOpen = MutableStateFlow(false)
	val aboutOpen = _aboutOpen.asStateFlow()

	init {
		load()
		loadMeta()
	}

	/**
	 * The mirror first, the network only when the mirror does not hold this artist.
	 *
	 * The mirror lookup is keyed as the network read is (`mb:<mbid>` plus the MBID, which is
	 * lb-bot's key for a scanned artist), so both name the same record. It is observed for the
	 * life of the page: a rescan, a single-release add from the album page, or the first sync
	 * reaching this artist all arrive without a refetch.
	 */
	fun load() {
		if (artistMbid.isBlank()) return
		viewModelScope.launch {
			lbIndexSync.observeDiscography("mb:$artistMbid", artistMbid)
				.catch { Logger.w("ExternalArtistViewModel", "index mirror observe failed", it) }
				.collect { data ->
					if (data == null || !lbBotManager.isConfigured) {
						// Not mirrored (yet). The FIRST such answer sends the network read;
						// later ones (a resync wipe) leave what is shown alone.
						if (!fromMirror && shown == null) loadFromNetwork()
						return@collect
					}
					fromMirror = true
					show(data)
				}
		}
	}

	private fun loadFromNetwork() {
		viewModelScope.launch {
			if (!lbBotManager.ensureAvailability()) {
				if (!fromMirror) _discography.value = DiscographyUi(available = false)
				_loading.value = false
				return@launch
			}
			val data = lbBotManager.discography("mb:$artistMbid", artistMbid)
			if (fromMirror) return@launch
			if (data == null) {
				_discography.value = DiscographyUi(available = false)
				_loading.value = false
				return@launch
			}
			show(data)
		}
	}

	/** Start the scan this visit owes an artist lb-bot has never walked: once, and only then. */
	private fun scanOnArrival() {
		if (autoScanned) return
		val ui = _discography.value
		if (!ui.available || ui.indexed || ui.indexing) return
		autoScanned = true
		indexArtist()
	}

	/** Paint [data], keeping any scan in flight. */
	private fun show(data: LbDiscography) {
		_loading.value = false
		if (data == shown) return
		shown = data
		// lb-bot knows the artist's real name once it has scanned them; the name
		// carried on the route is whatever the fresh feed called them.
		data.artistName.takeIf { it.isNotBlank() }?.let { _name.value = it }
		val previous = _discography.value
		_discography.value = DiscographyUi(
			available = true,
			indexed = data.indexed,
			indexing = previous.indexing,
			scanError = previous.scanError,
			scanShared = previous.scanShared,
			scannedAt = (data.scannedAt * 1000).toLong(),
			stale = data.stale,
			sections = sections(data.releases)
		)
		scanOnArrival()
	}

	/** Kick the MusicBrainz walk, then wait for the index to fill — the same
	 *  `scanned_at` rule the owned artist page uses, and for the same reason: a
	 *  rescan of an already-indexed artist never flips `indexed`. The same race too:
	 *  the mirror Flow is the feedback, the 15 s poll the fallback and the only one
	 *  that can report a failed scan (see `ArtistDetailViewModel.indexArtist`). */
	fun indexArtist() {
		if (artistMbid.isBlank()) return
		// A scan this visit already owes: an explicit tap counts as the one on arrival too.
		autoScanned = true
		viewModelScope.launch {
			_discography.value =
				_discography.value.copy(indexing = true, scanError = "", scanShared = false)
			val before = shown?.takeIf { it.indexed }?.scannedAt
				?: lbBotManager.discography("mb:$artistMbid", artistMbid)?.scannedAt
				?: 0.0
			val taskId = when (val start = lbBotManager.indexArtist(artistMbid, _name.value, "mb:$artistMbid")) {
				is LbRescanStart.Started -> start.taskId
				// Nothing to wait on: a library artist holds this discography (as on the owned
				// artist page). No spinner, and the shelf says so.
				LbRescanStart.Shared -> {
					_discography.value = _discography.value.copy(indexing = false, scanShared = true)
					return@launch
				}
				LbRescanStart.NotStarted -> {
					_discography.value = _discography.value.copy(
						indexing = false,
						scanError = "lb-bot did not start the scan"
					)
					return@launch
				}
			}
			val outcome = coroutineScope {
				val viaMirror = async<LbScanOutcome> {
					try {
						LbScanOutcome.Done(
							lbIndexSync.observeDiscography("mb:$artistMbid", artistMbid)
								.filterNotNull()
								.first { it.indexed && it.scannedAt > before }
						)
					} catch (e: CancellationException) {
						throw e
					} catch (e: Exception) {
						Logger.w("ExternalArtistViewModel", "index mirror watch failed", e)
						awaitCancellation()
					}
				}
				val viaPoll = async {
					lbBotManager.awaitArtistScan("mb:$artistMbid", artistMbid, taskId, before)
				}
				select<LbScanOutcome> {
					viaMirror.onAwait { it }
					viaPoll.onAwait { it }
				}.also {
					viaMirror.cancel()
					viaPoll.cancel()
				}
			}
			when (outcome) {
				is LbScanOutcome.Done -> {
					_discography.value = _discography.value.copy(indexing = false)
					show(outcome.data)
				}
				is LbScanOutcome.Failed -> _discography.value = _discography.value.copy(
					indexing = false,
					scanError = outcome.error
				)
				LbScanOutcome.TimedOut -> _discography.value = _discography.value.copy(indexing = false)
			}
		}
	}

	/**
	 * The About an unowned artist can have: lb-bot's, the cached article first (no network,
	 * offline too), then, once online, revalidated as the owned artist page does. An answer
	 * replaces what is shown only if it has something or nothing is shown.
	 */
	private fun loadMeta() {
		if (artistMbid.isBlank()) return
		viewModelScope.launch {
			if (!lbBotManager.isConfigured) return@launch
			lbBotManager.artistMeta(artistMbid, _name.value, LbCachePolicy.CACHE_ONLY)
				.collect { cached -> if (cached != null) _meta.value = cached }
			val online = connectivityManager.isOnline
			if (!online.value) online.first { it }
			if (!lbBotManager.ensureAvailability()) return@launch
			lbBotManager.artistMeta(artistMbid, _name.value).collect { meta ->
				if (meta != null || _meta.value == null) _meta.value = meta
			}
		}
	}

	fun openAbout() {
		_aboutOpen.value = true
	}

	fun dismissAbout() {
		_aboutOpen.value = false
	}

	/** Group by release type, in lb-bot's own order — the same shape the owned
	 *  artist page builds, so the two groupings cannot diverge. */
	private fun sections(releases: List<LbRelease>): List<DiscographySection> =
		releases
			.map { release ->
				DiscographyEntry(
					key = release.rgid,
					title = release.title,
					year = release.year,
					album = null,
					release = release
				)
			}
			.groupBy { entry ->
				entry.release?.effectiveType?.takeIf { it.isNotBlank() }
					?: entry.release?.primaryType?.takeIf { it.isNotBlank() }
					?: "album"
			}
			.map { (type, entries) ->
				DiscographySection(type, entries.sortedByDescending { it.year })
			}

}
