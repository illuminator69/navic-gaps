package paige.navic.ui.screens.fresh.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import paige.navic.domain.manager.ConnectivityManager
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbCachePolicy
import paige.navic.domain.manager.LbFreshRelease
import paige.navic.domain.manager.PreferenceManager

/**
 * Which releases the page is scoped to. [PLAYED] is "Artists you play" (I-006): rows whose artists
 * have plays in lb-bot's listens mirror, most played first.
 */
enum class FreshScope { YOURS, ALL, PLAYED }

/**
 * Whether the "Artists you play" scope is offered: only when some row HAS plays.
 *
 * Not "when the feed carries the rider". A current lb-bot always sends `artistPlays`, and sends
 * 0 on every row while its listens mirror is off or still importing — so a scope offered on the
 * field alone showed "(0)" over a sentence saying none of the artists you play released
 * anything, which nothing had said. The wire cannot tell that from a mirror that is not ready,
 * so an empty scope is never offered with a claim about either. An older lb-bot sends no field
 * at all, and falls out of the same rule.
 */
internal fun freshPlayedOffered(rows: List<LbFreshRelease>): Boolean =
	rows.any { (it.artistPlays ?: 0) > 0 }

/**
 * The scope the page actually applies. The stored choice is kept as it is — the rider may be
 * back on the next read — but a scope the feed cannot answer falls back to the default rather
 * than rendering an empty page under a chip that is not there.
 */
internal fun freshEffectiveScope(scope: FreshScope, playedOffered: Boolean): FreshScope =
	if (scope == FreshScope.PLAYED && !playedOffered) FreshScope.YOURS else scope

/**
 * The rows a scope shows.
 *
 * "Your artists" is `artistOwned` — the artist being in the library — NOT `releaseOwned`, which
 * is only this exact release-group being on disk. An owned artist with a brand-new album is
 * precisely what this page is for, and conflating the two would filter it out.
 *
 * "Artists you play" is the only scope that also ORDERS: most played first, then newest, then by
 * name, so the order is the same on every read.
 */
internal fun freshScoped(rows: List<LbFreshRelease>, scope: FreshScope): List<LbFreshRelease> =
	when (scope) {
		FreshScope.YOURS -> rows.filter { it.artistOwned }
		FreshScope.ALL -> rows
		FreshScope.PLAYED -> rows
			.filter { (it.artistPlays ?: 0) > 0 }
			.sortedWith(
				compareByDescending<LbFreshRelease> { it.artistPlays ?: 0 }
					.thenByDescending { it.releaseDate }
					.thenBy { it.releaseName.lowercase() }
			)
	}

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
	/** The scope APPLIED — the stored choice, or its fallback when the feed cannot answer it. */
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
	/** Some row has plays (`freshPlayedOffered`); otherwise the "Artists you play" chip is hidden. */
	val playedOffered: Boolean = false,
	/** Rows whose artists have plays — the "Artists you play" chip's count. */
	val playedCount: Int = 0,
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

	/** The scope the user chose, as stored. [FreshUi.scope] is what [recompute] makes of it. */
	private var chosenScope = runCatching { FreshScope.valueOf(preferenceManager.freshScope) }
		.getOrDefault(FreshScope.YOURS)

	private val _state = MutableStateFlow(
		FreshUi(
			days = preferenceManager.freshDays,
			scope = freshEffectiveScope(chosenScope, playedOffered = false),
			sort = runCatching { FreshSort.valueOf(preferenceManager.freshSort) }
				.getOrDefault(FreshSort.DATE),
			type = runCatching { FreshType.valueOf(preferenceManager.freshType) }
				.getOrDefault(FreshType.ALL)
		)
	)
	val state = _state.asStateFlow()

	private var loadJob: Job? = null

	init { load() }

	/**
	 * Read the feed — stale-while-revalidate over `lb_response_cache`.
	 *
	 * The cached feed (any age) renders first, straight from Room; a request follows only when
	 * it is older than ten minutes, or older than the last library change (every row carries
	 * `artistOwned` / `releaseOwned`, which a landing falsifies), or when [policy] is
	 * [LbCachePolicy.REFRESH]. A failed request never replaces a feed on screen.
	 *
	 * Offline is [LbCachePolicy.CACHE_ONLY], gated on connectivity the way
	 * `ArtistDetailViewModel.loadDiscography` is: this runs from `init`, i.e. on the screen's
	 * first frame, and a cold start restored straight onto this tab would otherwise burn its one
	 * attempt before the network is up. There is no automatic retry by design — a feed that
	 * updates hourly does not deserve a poll — so with nothing cached the failure state carries a
	 * Retry button, and that is the only thing standing between one unlucky frame and a
	 * permanently empty tab.
	 *
	 * [quiet] keeps the spinner off: the screen's own revalidation on every visit
	 * ([revalidate]) must not flash a loading state over a feed that is already there.
	 */
	fun load(policy: LbCachePolicy = LbCachePolicy.NORMAL, quiet: Boolean = false) {
		loadJob?.cancel()
		loadJob = viewModelScope.launch {
			if (!quiet) _state.value = _state.value.copy(loading = true, failed = false)
			val days = _state.value.days
			val effective = if (isOnline.value) policy else LbCachePolicy.CACHE_ONLY
			lbBotManager.freshReleases(days, policy = effective).collect { feed ->
				// A window change is a different request: drop an answer for the old one.
				if (_state.value.days != days) return@collect
				// `null` only when nothing was cached AND nothing came back, so it replaces
				// only an empty (or other-window) feed — never one the user is reading.
				_state.value = recompute(
					_state.value.copy(
						failed = feed == null,
						all = feed?.releases.orEmpty(),
						total = feed?.total ?: 0,
						truncated = feed?.truncated == true
					)
				)
			}
			// After the Flow, not on its first emission: `loading` is "a request is in flight",
			// so a pull-to-refresh spins until the network has answered rather than stopping on
			// the cached feed it painted first.
			_state.value = _state.value.copy(loading = false)
		}
	}

	/** Pull-to-refresh and Retry: revalidate whatever the cached feed's age. */
	fun refresh() = load(LbCachePolicy.REFRESH)

	/**
	 * Every visit to the screen. The ViewModel outlives the visit (a root tab keeps it for the
	 * process), so `init` alone would show the first feed of the session forever; this is a Room
	 * read and, only when the cached feed has gone stale, one request.
	 */
	fun revalidate() {
		if (loadJob?.isActive == true) return
		load(quiet = true)
	}

	fun setDays(days: Int) {
		if (days == _state.value.days) return
		preferenceManager.freshDays = days
		_state.value = _state.value.copy(days = days)
		load()          // a different window is a different request, not a filter
	}

	fun setScope(scope: FreshScope) {
		chosenScope = scope
		preferenceManager.freshScope = scope.name
		_state.value = recompute(_state.value)
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
		val playedOffered = freshPlayedOffered(ui.all)
		val scope = freshEffectiveScope(chosenScope, playedOffered)
		val scoped = freshScoped(ui.all, scope)
		val counts = FreshType.entries.associateWith { bucket ->
			if (bucket == FreshType.ALL) scoped.size else scoped.count { typeOf(it) == bucket }
		}
		return ui.copy(
			scope = scope,
			ownedArtistCount = ui.all.count { it.artistOwned },
			playedOffered = playedOffered,
			playedCount = ui.all.count { (it.artistPlays ?: 0) > 0 },
			typeCounts = counts,
			groups = freshGroups(ui.all, chosenScope, ui.sort, ui.type)
		)
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

/**
 * What the page shows, grouped as it shows it: [all] in the scope chosen ([freshEffectiveScope]),
 * of [type], by [sort] (weekly date groups, newest first, or one list by artist). "Artists you play"
 * is one flat list in [freshScoped]'s own order, most played first: the sort does not apply there,
 * and the page hides it. Home's Fresh shelf reads the same (P5 round 3, the owner's note: the shelf
 * and the page showed different releases), through [freshOrdered].
 */
internal fun freshGroups(
	all: List<LbFreshRelease>,
	chosenScope: FreshScope,
	sort: FreshSort,
	type: FreshType
): List<FreshGroup> {
	val scope = freshEffectiveScope(chosenScope, freshPlayedOffered(all))
	val scoped = freshScoped(all, scope)
	val shown = if (type == FreshType.ALL) scoped else scoped.filter { FreshViewModel.typeOf(it) == type }
	if (shown.isEmpty()) return emptyList()
	if (scope == FreshScope.PLAYED) return listOf(FreshGroup(null, shown))
	if (sort == FreshSort.ARTIST) {
		return listOf(FreshGroup(null, shown.sortedWith(compareBy({ it.artist.lowercase() }, { it.releaseName.lowercase() }))))
	}
	return shown.groupBy { FreshViewModel.ageBucket(it.releaseDate) }
		.toSortedMap()
		.map { (bucket, items) -> FreshGroup(bucket, items.sortedByDescending { it.releaseDate }) }
}

/** The page's releases in the order it shows them, top to bottom ([freshGroups]). */
internal fun freshOrdered(
	all: List<LbFreshRelease>,
	chosenScope: FreshScope,
	sort: FreshSort,
	type: FreshType
): List<LbFreshRelease> = freshGroups(all, chosenScope, sort, type).flatMap { it.items }

/** The page's scope, sort and type as stored, each falling back to the page's own default. */
internal data class FreshFilters(val scope: FreshScope, val sort: FreshSort, val type: FreshType) {
	companion object {
		fun of(preferenceManager: PreferenceManager) = FreshFilters(
			scope = runCatching { FreshScope.valueOf(preferenceManager.freshScope) }.getOrDefault(FreshScope.YOURS),
			sort = runCatching { FreshSort.valueOf(preferenceManager.freshSort) }.getOrDefault(FreshSort.DATE),
			type = runCatching { FreshType.valueOf(preferenceManager.freshType) }.getOrDefault(FreshType.ALL)
		)
	}
}
