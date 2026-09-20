package paige.navic.ui.screens.fresh.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import paige.navic.domain.manager.ConnectivityManager
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbFreshRelease
import paige.navic.domain.manager.PreferenceManager

/** Which releases the page is scoped to. */
enum class FreshScope { YOURS, ALL }

/** Newest-first (in week buckets) or a flat A-Z by artist. */
enum class FreshSort { DATE, ARTIST }

/**
 * lb-bot's type vocabulary, bucketed for filtering.
 *
 * `OTHER` deliberately catches everything that is not a plain album/EP/single —
 * broadcasts, compilations, untyped rows — so nothing silently disappears from a
 * filter. A row with no type at all is the common case, not an edge one.
 */
enum class FreshType { ALL, ALBUM, EP, SINGLE, OTHER }

/** A run of releases under one divider heading. Empty groups are never emitted. */
data class FreshGroup(
	/** -1 upcoming, 0 this week, 1 last week, 2, 3, 4 earlier; null for the flat A-Z list. */
	val bucket: Int?,
	val items: List<LbFreshRelease>
)

data class FreshUi(
	val loading: Boolean = true,
	/** lb-bot answered but had nothing to say, versus it not answering at all. */
	val failed: Boolean = false,
	val days: Int = 30,
	val scope: FreshScope = FreshScope.YOURS,
	val sort: FreshSort = FreshSort.DATE,
	val type: FreshType = FreshType.ALL,
	/** Everything in the window, unfiltered — the chips' counts read off this. */
	val all: List<LbFreshRelease> = emptyList(),
	/** How many rows lb-bot had before its cut, and whether it cut. Every artist
	 *  in the library survives the cut, so this only ever means "there is more by
	 *  artists you don't have". */
	val total: Int = 0,
	val truncated: Boolean = false,
	val groups: List<FreshGroup> = emptyList(),
	val ownedArtistCount: Int = 0,
	val typeCounts: Map<FreshType, Int> = emptyMap()
)

/**
 * ListenBrainz's site-wide fresh-releases feed.
 *
 * Filtering is done here rather than in the composable so the grid recomposes on a
 * settled list rather than re-deriving one per frame, and so the filters can be
 * persisted in one place. They ARE persisted: scope, window, type and sort are
 * decisions about how someone reads this page, not per-visit state.
 */
class FreshViewModel(
	private val lbBotManager: LbBotManager,
	private val preferenceManager: PreferenceManager,
	connectivityManager: ConnectivityManager
) : ViewModel() {
	private val isOnline = connectivityManager.isOnline
	private val _state = MutableStateFlow(
		FreshUi(
			days = preferenceManager.freshDays,
			scope = runCatching { FreshScope.valueOf(preferenceManager.freshScope) }
				.getOrDefault(FreshScope.YOURS),
			sort = runCatching { FreshSort.valueOf(preferenceManager.freshSort) }
				.getOrDefault(FreshSort.DATE),
			type = runCatching { FreshType.valueOf(preferenceManager.freshType) }
				.getOrDefault(FreshType.ALL)
		)
	)
	val state = _state.asStateFlow()

	init { load() }

	/**
	 * Read the feed.
	 *
	 * Gated on connectivity the way `ArtistDetailViewModel.loadDiscography` is:
	 * this runs from `init`, i.e. on the screen's first frame, and a cold start
	 * restored straight onto this tab would otherwise burn its one attempt before
	 * the network is up. There is no automatic retry by design — a feed that
	 * updates hourly does not deserve a poll — so the failure state carries a
	 * Retry button, and that is the only thing standing between one unlucky frame
	 * and a permanently empty tab.
	 */
	fun load() {
		viewModelScope.launch {
			_state.value = _state.value.copy(loading = true, failed = false)
			if (!isOnline.value) {
				_state.value = _state.value.copy(loading = false, failed = true)
				return@launch
			}
			val feed = lbBotManager.freshReleases(_state.value.days)
			_state.value = recompute(
				_state.value.copy(
					loading = false,
					failed = feed == null,
					all = feed?.releases.orEmpty(),
					total = feed?.total ?: 0,
					truncated = feed?.truncated == true
				)
			)
		}
	}

	fun setDays(days: Int) {
		if (days == _state.value.days) return
		preferenceManager.freshDays = days
		_state.value = _state.value.copy(days = days)
		load()          // a different window is a different request, not a filter
	}

	fun setScope(scope: FreshScope) {
		preferenceManager.freshScope = scope.name
		_state.value = recompute(_state.value.copy(scope = scope))
	}

	fun setSort(sort: FreshSort) {
		preferenceManager.freshSort = sort.name
		_state.value = recompute(_state.value.copy(sort = sort))
	}

	fun setType(type: FreshType) {
		preferenceManager.freshType = type.name
		_state.value = recompute(_state.value.copy(type = type))
	}

	private fun recompute(ui: FreshUi): FreshUi {
		// "Your artists" is artistOwned — the artist being in the library — NOT
		// releaseOwned, which is only this exact release-group being on disk. An
		// owned artist with a brand-new album is precisely what this page is for,
		// and conflating the two would filter it out.
		val scoped = if (ui.scope == FreshScope.YOURS) ui.all.filter { it.artistOwned } else ui.all
		val counts = FreshType.entries.associateWith { bucket ->
			if (bucket == FreshType.ALL) scoped.size else scoped.count { typeOf(it) == bucket }
		}
		val shown = if (ui.type == FreshType.ALL) scoped else scoped.filter { typeOf(it) == ui.type }
		return ui.copy(
			ownedArtistCount = ui.all.count { it.artistOwned },
			typeCounts = counts,
			groups = group(shown, ui.sort)
		)
	}

	private fun group(rows: List<LbFreshRelease>, sort: FreshSort): List<FreshGroup> {
		if (sort == FreshSort.ARTIST) {
			val sorted = rows.sortedWith(
				compareBy({ it.artist.lowercase() }, { it.releaseName.lowercase() })
			)
			return if (sorted.isEmpty()) emptyList() else listOf(FreshGroup(null, sorted))
		}
		return rows.groupBy { ageBucket(it.releaseDate) }
			.toSortedMap()
			.map { (bucket, items) ->
				FreshGroup(bucket, items.sortedByDescending { it.releaseDate })
			}
	}

	companion object {
		/** lb-bot's primary type, bucketed. Anything unrecognised — including a row
		 *  with no type at all — lands in OTHER rather than vanishing. */
		fun typeOf(row: LbFreshRelease): FreshType = when (row.type.lowercase()) {
			"album" -> FreshType.ALBUM
			"ep" -> FreshType.EP
			"single" -> FreshType.SINGLE
			else -> FreshType.OTHER
		}

		/**
		 * Which weekly bucket a release falls into, by whole days from its date.
		 *
		 * A future date is `-1` (Upcoming) — normal for this feed, not an error — and
		 * anything undated or unparseable sinks to `4` (Earlier) so it still renders
		 * somewhere rather than being dropped.
		 */
		fun ageBucket(iso: String): Int {
			val days = daysSince(iso) ?: return 4
			return when {
				days < 0 -> -1
				days < 7 -> 0
				days < 14 -> 1
				days < 21 -> 2
				days < 28 -> 3
				else -> 4
			}
		}

		/** Whole days between an ISO `yyyy-MM-dd` and today; null if unparseable. */
		fun daysSince(iso: String): Int? {
			val date = runCatching { LocalDate.parse(iso.take(10)) }.getOrNull() ?: return null
			val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
			return date.daysUntil(today)
		}
	}
}
