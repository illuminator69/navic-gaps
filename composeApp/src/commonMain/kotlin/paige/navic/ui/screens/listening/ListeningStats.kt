@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package paige.navic.ui.screens.listening

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.plus
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.Surface
import androidx.compose.material3.toShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.graphics.shapes.RoundedPolygon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.toLowerCase
import kotlinx.collections.immutable.toImmutableList
import paige.navic.domain.manager.PreferenceManager
import paige.navic.icons.filled.Star
import paige.navic.icons.outlined.Album
import paige.navic.icons.outlined.Artist
import paige.navic.icons.outlined.History
import paige.navic.icons.outlined.Note
import paige.navic.icons.outlined.Refresh
import paige.navic.icons.outlined.Sparkle
import paige.navic.ui.components.common.PageChips
import paige.navic.ui.components.common.SegmentedListItem
import paige.navic.ui.components.common.SegmentedListItemDefaults
import paige.navic.ui.components.common.SegmentedListItemDefaults.segmentedShapes
import paige.navic.ui.components.common.SongRowDefaults
import paige.navic.ui.components.layouts.ArtCarousel
import paige.navic.ui.components.layouts.ArtCarouselItem
import paige.navic.ui.screens.stats.components.RankingItem
import paige.navic.ui.screens.stats.components.StatSummaryCard
import paige.navic.ui.theme.defaultFont
import paige.navic.ui.util.withoutTop
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_retry
import navic.composeapp.generated.resources.info_starred_songs
import navic.composeapp.generated.resources.label_not_in_library
import navic.composeapp.generated.resources.listening_days
import navic.composeapp.generated.resources.listening_genres
import navic.composeapp.generated.resources.listening_genres_from
import navic.composeapp.generated.resources.action_listening_sync_now
import navic.composeapp.generated.resources.action_listening_syncing
import navic.composeapp.generated.resources.listening_daily_title
import navic.composeapp.generated.resources.listening_daily_capped
import navic.composeapp.generated.resources.listening_heatmap_caption
import navic.composeapp.generated.resources.listening_heatmap_scale
import navic.composeapp.generated.resources.listening_needs_lbbot
import navic.composeapp.generated.resources.listening_tab_activity
import navic.composeapp.generated.resources.listening_tab_history
import navic.composeapp.generated.resources.listening_tab_overview
import navic.composeapp.generated.resources.listening_tab_unowned
import navic.composeapp.generated.resources.listening_heatmap_title
import navic.composeapp.generated.resources.listening_hours_title
import navic.composeapp.generated.resources.listening_importing
import navic.composeapp.generated.resources.listening_importing_note
import navic.composeapp.generated.resources.listening_importing_unknown
import navic.composeapp.generated.resources.listening_list_failed
import navic.composeapp.generated.resources.listening_no_plays
import navic.composeapp.generated.resources.listening_older
import navic.composeapp.generated.resources.listening_period_30d
import navic.composeapp.generated.resources.listening_period_365d
import navic.composeapp.generated.resources.listening_period_7d
import navic.composeapp.generated.resources.listening_period_90d
import navic.composeapp.generated.resources.listening_period_all
import navic.composeapp.generated.resources.listening_plays
import navic.composeapp.generated.resources.listening_recent_empty
import navic.composeapp.generated.resources.listening_recent_more
import navic.composeapp.generated.resources.listening_recent_title
import navic.composeapp.generated.resources.listening_stat_albums
import navic.composeapp.generated.resources.listening_stat_artists
import navic.composeapp.generated.resources.listening_stat_longest_streak
import navic.composeapp.generated.resources.listening_stat_plays
import navic.composeapp.generated.resources.listening_stat_streak
import navic.composeapp.generated.resources.listening_stat_time
import navic.composeapp.generated.resources.listening_summary_failed
import navic.composeapp.generated.resources.listening_sync_failed
import navic.composeapp.generated.resources.listening_sync_failed_note
import navic.composeapp.generated.resources.listening_sync_failed_plain
import navic.composeapp.generated.resources.listening_time_dh
import navic.composeapp.generated.resources.listening_time_from
import navic.composeapp.generated.resources.listening_time_hm
import navic.composeapp.generated.resources.listening_time_m
import navic.composeapp.generated.resources.listening_top_albums
import navic.composeapp.generated.resources.listening_top_artists
import navic.composeapp.generated.resources.listening_top_tracks
import navic.composeapp.generated.resources.listening_unowned_albums
import navic.composeapp.generated.resources.listening_unowned_artists
import navic.composeapp.generated.resources.listening_unowned_empty
import navic.composeapp.generated.resources.listening_unowned_title
import navic.composeapp.generated.resources.listening_unreachable
import navic.composeapp.generated.resources.listening_unreachable_cached
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.di.LocalNavStack
import paige.navic.domain.manager.LISTENING_KIND_ALBUM
import paige.navic.domain.manager.LISTENING_KIND_ARTIST
import paige.navic.domain.manager.LbListeningSummary
import paige.navic.domain.manager.LbRecentPlay
import paige.navic.domain.manager.LbArt
import paige.navic.domain.manager.ListeningTab
import paige.navic.domain.manager.albumCardArt
import paige.navic.domain.manager.HistoryRowGesture
import paige.navic.domain.manager.LbDayCount
import paige.navic.domain.manager.dayAxisLabels
import paige.navic.domain.manager.daySeries
import paige.navic.domain.manager.heatmapCellSize
import paige.navic.domain.manager.historyRowGesture
import paige.navic.domain.manager.LISTENING_DAILY_MAX_DAYS
import paige.navic.domain.manager.genreBarFraction
import paige.navic.domain.manager.heatmapSpan
import paige.navic.domain.manager.heldInLibrary
import paige.navic.domain.manager.listeningDailyCapped
import paige.navic.domain.manager.libraryArtistArt
import paige.navic.domain.manager.unownedCardArt
import paige.navic.domain.manager.ListeningHeatmap
import paige.navic.domain.manager.ListeningListFailure
import paige.navic.domain.manager.ListeningPeriod
import paige.navic.domain.manager.ListeningPhase
import paige.navic.domain.manager.ListeningTarget
import paige.navic.domain.manager.albumTarget
import paige.navic.domain.manager.artistTarget
import paige.navic.domain.manager.formatListenedAt
import paige.navic.domain.manager.heatmapGrid
import paige.navic.domain.manager.listeningStatusLineShown
import paige.navic.domain.manager.listeningSyncOffered
import paige.navic.domain.manager.listeningTabsShown
import paige.navic.domain.manager.hourBars
import paige.navic.domain.manager.listeningDuration
import paige.navic.domain.manager.listeningTimeCoverage
import paige.navic.domain.manager.recentPlayKey
import paige.navic.domain.manager.recentPlayTarget
import paige.navic.domain.manager.unownedTarget
import paige.navic.icons.Icons
import paige.navic.icons.filled.Headphones
import paige.navic.ui.components.common.ContentUnavailable
import paige.navic.ui.components.common.LbArtImage
import paige.navic.ui.components.sheets.ArtistSheet
import paige.navic.ui.components.sheets.CollectionSheet
import paige.navic.ui.components.sheets.SongSheet
import paige.navic.ui.screens.listening.viewmodels.HistoryMenuUi
import paige.navic.domain.manager.RadioManager
import paige.navic.shared.MediaPlayerViewModel
import org.koin.compose.koinInject
import paige.navic.ui.components.layouts.PullToRefreshBox
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.listening.viewmodels.ListeningUi
import paige.navic.ui.screens.listening.viewmodels.ListeningViewModel

/**
 * How often the status is read while an import runs. It is the progress readout, nothing else is
 * polled, and it stops the moment the screen is not visible or the import ends.
 */
private const val STATUS_POLL_MS = 5_000L

/** How far figures on their way out are dimmed while the next period's are read. */
private const val STALE_ALPHA = 0.5f

/** The largest a heat-map square is drawn, however few weeks there are. */
private val HEATMAP_CELL_MAX = 24.dp

/**
 * Stats' listening half (I-006; a page of its own, My Listening, until I-039): the user's
 * ListenBrainz history, by the numbers. `StatisticsScreen` shows it in place of upstream's
 * Navidrome figures wherever lb-bot holds a history (`listeningOnStats`), under Stats' own title,
 * with the tabs ([ListeningTabs]) under it as upstream's search categories sit.
 *
 * lb-bot mirrors the listens and computes every figure here; this only lays them out, and says
 * what each was measured over where that is less than everything (the listening time, the
 * genres). What it is at all follows the mirror's state:
 *
 * - **importing** — the first backfill runs: "N of M" from the status, polled, over whatever the
 *   summary already holds;
 * - **ready** — the history;
 * - **error** — the last sync failed: lb-bot's own text, over the plays already stored;
 * - lb-bot **unreachable** — the summary this device loaded last, said to be that; or a failure
 *   line with a Retry when there is none.
 *
 * (No ListenBrainz user, and an lb-bot older than the routes, are Stats' own page.)
 *
 * Rows link out and never download: what the library holds opens its library page, what it does
 * not opens the external album or artist page, where the reviewed acquire flow already lives.
 *
 * **Four tabs** (I-026), the same four under the same names as Feishin's page: **Overview** (the
 * numbers as Stats' cards, top artists as Stats' ranking, albums and tracks as its carousels, the
 * genres ranked the same way), **Activity** (the heat map, then the period's plays per day and by
 * hour), **History** (recent plays as queue-like rows with covers) and **Not in library** (cover
 * cards of the albums or artists most played that the library lacks). One period for the page,
 * its picker drawn where it changes something. The history's status line and "Sync now" sit above
 * the tab's content, and every state above keeps its meaning and its wording.
 */
@Composable
fun ListeningStats(
	viewModel: ListeningViewModel,
	tab: ListeningTab,
	innerPadding: PaddingValues,
	/** Upstream's starred-songs card, which Stats keeps among the history's. */
	starredSongs: Int,
	modifier: Modifier = Modifier
) {
	val read by viewModel.state.collectAsStateWithLifecycle()
	// A period change clears the summary (and the unowned list) until the new period's is read:
	// the view model never holds another period's figures as this one's. On screen, though, the
	// page emptied for a moment on every change (you, 2026-10-16). So the figures last shown stay
	// up while the next ones are read, dimmed to say they are on their way out, and the page then
	// changes in place: rows ease in, out and into position, the bars to their new lengths.
	var lastSummary by remember { mutableStateOf(read.summary) }
	LaunchedEffect(read.summary) { read.summary?.let { lastSummary = it } }
	var lastUnowned by remember { mutableStateOf(read.unownedKind to read.unowned) }
	LaunchedEffect(read.unownedKind, read.unowned) {
		if (read.unowned.isNotEmpty()) lastUnowned = read.unownedKind to read.unowned
	}
	val summaryWaiting = read.summary == null && read.summaryLoading && lastSummary != null
	val unownedWaiting = read.unowned.isEmpty() && read.unownedLoading &&
		lastUnowned.first == read.unownedKind && lastUnowned.second.isNotEmpty()
	val state = read.copy(
		summary = if (summaryWaiting) lastSummary else read.summary,
		unowned = if (unownedWaiting) lastUnowned.second else read.unowned
	)
	val dimSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
	val summaryAlpha by animateFloatAsState(if (summaryWaiting) STALE_ALPHA else 1f, dimSpec)
	val unownedAlpha by animateFloatAsState(if (unownedWaiting) STALE_ALPHA else 1f, dimSpec)
	val statusLine by viewModel.statusLine.collectAsStateWithLifecycle()
	val syncLine by viewModel.syncLine.collectAsStateWithLifecycle()
	val syncing by viewModel.syncing.collectAsStateWithLifecycle()
	val backStack = LocalNavStack.current
	val open: (ListeningTarget) -> Unit = { target -> openTarget(backStack, target) }

	// Poll the status only while an import runs and only while this screen is started: the
	// block is cancelled when another screen covers it or the app goes to the background, and
	// the effect ends for good when the phase stops being "importing".
	val importing = state.phase is ListeningPhase.Importing
	val lifecycleOwner = LocalLifecycleOwner.current
	LaunchedEffect(lifecycleOwner, importing) {
		if (!importing) return@LaunchedEffect
		lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
			while (true) {
				delay(STATUS_POLL_MS)
				viewModel.pollStatus()
			}
		}
	}

	// A History row's menu: the app's own sheet for the song the play was — found in the album
	// it is filed under, and only when exactly one song fits (`historyMenu`) — else the album's
	// or the artist's. The song sheet carries the actions that need nothing but the song.
	val historyMenu by viewModel.historyMenu.collectAsStateWithLifecycle()
	val player = koinInject<MediaPlayerViewModel>()
	val radioManager = koinInject<RadioManager>()
	when (val menu = historyMenu) {
		is HistoryMenuUi.Song -> SongSheet(
			onDismissRequest = { viewModel.dismissHistoryMenu() },
			song = menu.song,
			onPlayNext = { player.playNextSingle(menu.song) },
			onAddToQueue = { player.addToQueueSingle(menu.song) },
			onStartRadio = { radioManager.startRadio(menu.song) },
			onTrackInfo = { backStack.add(Screen.SongDetailSheet(menu.song.id)) },
			onViewAlbum = menu.song.albumId?.let { albumId ->
				{ backStack.add(Screen.CollectionDetail(albumId, "library")) }
			}
		)
		is HistoryMenuUi.Album -> CollectionSheet(
			onDismissRequest = { viewModel.dismissHistoryMenu() },
			collection = menu.album,
			onPlayNext = { player.playNext(menu.album) },
			onAddToQueue = { player.addToQueue(menu.album) }
		)
		is HistoryMenuUi.Artist -> ArtistSheet(
			onDismissRequest = { viewModel.dismissHistoryMenu() },
			artist = menu.artist
		)
		null -> Unit
	}

	PullToRefreshBox(
		modifier = modifier.padding(top = innerPadding.calculateTopPadding()),
		finished = !state.loading,
		onRefresh = { viewModel.refresh() },
		key = state.loading
	) {
		LazyColumn(
			modifier = Modifier.fillMaxSize(),
			contentPadding = innerPadding.withoutTop() +
				PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)
		) {
			when (val phase = state.phase) {
				ListeningPhase.Loading -> item(key = "s:loading") {
					Box(
						Modifier.fillMaxWidth().padding(vertical = 64.dp),
						contentAlignment = Alignment.Center
					) { ContainedLoadingIndicator() }
				}

				else -> {
					item(key = "s:sync") {
						ListeningSyncRow(phase, statusLine, syncLine, syncing, onSync = { viewModel.syncNow() })
					}
					item(key = "s:banner") { PhaseBanner(phase, hasSummary = state.summary != null) }
					// The period picker belongs to what it changes: one period for the page,
					// its control on every tab but History — and on Activity below the heat
					// map, which it does not change (`listeningTabShowsPeriod`).
					val periods: @Composable () -> Unit = {
						PageChips(
							options = ListeningPeriod.entries,
							selected = state.period,
							label = { stringResource(listeningPeriodLabel(it)) },
							onSelect = { viewModel.setPeriod(it) },
							contentPadding = PaddingValues(bottom = 8.dp)
						)
					}
					when (tab) {
						ListeningTab.OVERVIEW -> {
							item(key = "s:periods") { periods() }
							overviewItems(state, starredSongs, open, onRetry = { viewModel.refresh() }, dim = { summaryAlpha })
						}
						ListeningTab.ACTIVITY ->
							activityItems(state, periods, onRetry = { viewModel.refresh() }, dim = { summaryAlpha })
						// The two live lists need lb-bot. With it unreachable they say so in
						// a line rather than show failed under a banner that already says why.
						ListeningTab.HISTORY ->
							if (phase == ListeningPhase.Unreachable) {
								item(key = "s:recent-off") { Caption(stringResource(Res.string.listening_needs_lbbot)) }
							} else {
								historyItems(
									state,
									open,
									onMenu = { viewModel.openHistoryMenu(it) },
									onMore = { viewModel.loadMoreRecent() },
									onRetry = { viewModel.retryRecent() }
								)
							}
						ListeningTab.NOT_IN_LIBRARY -> {
							item(key = "s:periods") { periods() }
							if (phase == ListeningPhase.Unreachable) {
								item(key = "s:unowned-off") { Caption(stringResource(Res.string.listening_needs_lbbot)) }
							} else {
								notInLibraryItems(
									state,
									open,
									onKind = { viewModel.setUnownedKind(it) },
									onRetry = { viewModel.retryUnowned() },
									dim = { unownedAlpha }
								)
							}
						}
					}
				}
			}
		}
	}
}

/**
 * The history's four tabs, under Stats' title as upstream's search categories sit under Search's,
 * and only where there is a history to divide (`listeningTabsShown`).
 */
@Composable
fun ListeningTabs(
	phase: ListeningPhase,
	selected: ListeningTab,
	onSelect: (ListeningTab) -> Unit
) {
	if (!listeningTabsShown(phase)) return
	PageChips(
		options = ListeningTab.entries,
		selected = selected,
		label = { stringResource(tabLabel(it)) },
		onSelect = onSelect,
		contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp)
	)
}

/** Where a row leads. [ListeningTarget.None] rows are not clickable and never reach this. */
private fun openTarget(backStack: MutableList<NavKey>, target: ListeningTarget) {
	when (target) {
		is ListeningTarget.LibraryAlbum -> backStack.add(Screen.CollectionDetail(target.albumId, ""))
		is ListeningTarget.ExternalAlbum -> backStack.add(
			Screen.ExternalAlbum(
				rgid = target.rgid,
				artistMbid = target.artistMbid,
				artistName = target.artist,
				title = target.title,
				artistId = target.artistId
			)
		)
		is ListeningTarget.LibraryArtist -> backStack.add(Screen.ArtistDetail(target.artistId))
		is ListeningTarget.ExternalArtist ->
			backStack.add(Screen.ExternalArtist(target.mbid, target.name))
		ListeningTarget.None -> Unit
	}
}

/** `onClick` for a row, or null — a row with nowhere to go is plain text, not a dead control. */
private fun clickFor(target: ListeningTarget, open: (ListeningTarget) -> Unit): (() -> Unit)? =
	if (target == ListeningTarget.None) null else ({ open(target) })

// ----- the mirror's state ------------------------------------------------------------------------

@Composable
private fun PhaseBanner(phase: ListeningPhase, hasSummary: Boolean) {
	val lines: List<String> = when (phase) {
		is ListeningPhase.Importing -> listOf(
			// "N of M" only when ListenBrainz has said M; both numbers are the status's own.
			if (phase.total != null) {
				stringResource(Res.string.listening_importing, phase.count, phase.total)
			} else {
				stringResource(Res.string.listening_importing_unknown, phase.count)
			},
			stringResource(Res.string.listening_importing_note)
		)
		is ListeningPhase.SyncFailed -> listOf(
			if (phase.error.isNotBlank()) {
				stringResource(Res.string.listening_sync_failed, phase.error)
			} else {
				stringResource(Res.string.listening_sync_failed_plain)
			},
			stringResource(Res.string.listening_sync_failed_note)
		)
		ListeningPhase.Unreachable -> listOf(
			stringResource(
				if (hasSummary) Res.string.listening_unreachable_cached
				else Res.string.listening_unreachable
			)
		)
		else -> emptyList()
	}
	if (lines.isEmpty()) return
	// The Downloads page's "waiting for Wi-Fi" banner: a fact about the page, above it.
	Surface(
		color = MaterialTheme.colorScheme.secondaryContainer,
		contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
		shape = MaterialTheme.shapes.large,
		modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
	) {
		Row(
			modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			Column(Modifier.weight(1f)) {
				lines.forEachIndexed { index, line ->
					Text(
						line,
						style = if (index == 0) MaterialTheme.typography.bodyMedium
						else MaterialTheme.typography.bodySmall
					)
				}
			}
			if (phase is ListeningPhase.Importing) {
				CircularProgressIndicator(
					Modifier.padding(start = 12.dp).size(18.dp),
					strokeWidth = 2.dp,
					color = MaterialTheme.colorScheme.onSecondaryContainer
				)
			}
		}
	}
}

/**
 * The history's own status and its "Sync now" — here since I-026, out of the settings. The plain
 * line is drawn only while the mirror is simply ready (`listeningStatusLineShown`): every other
 * state is the banner's, and the status is said once. The button is absent where a sync cannot be
 * asked for (`listeningSyncOffered`), and what pressing it came to is the line under the status.
 */
@Composable
private fun ListeningSyncRow(
	phase: ListeningPhase,
	statusLine: String,
	syncLine: String,
	syncing: Boolean,
	onSync: () -> Unit
) {
	val offered = listeningSyncOffered(phase)
	val showStatus = listeningStatusLineShown(phase) && statusLine.isNotBlank()
	if (!offered && !showStatus && syncLine.isBlank()) return
	Row(
		modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
		horizontalArrangement = Arrangement.spacedBy(12.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		Column(Modifier.weight(1f)) {
			if (showStatus) Caption(statusLine)
			if (syncLine.isNotBlank()) Caption(syncLine)
		}
		if (offered) {
			OutlinedButton(enabled = !syncing, onClick = onSync) {
				Text(
					stringResource(
						if (syncing) Res.string.action_listening_syncing else Res.string.action_listening_sync_now
					)
				)
			}
		}
	}
}

/** A period as its chip names it (also the home's Stats card, Q-103). */
internal fun listeningPeriodLabel(period: ListeningPeriod): StringResource = when (period) {
	ListeningPeriod.WEEK -> Res.string.listening_period_7d
	ListeningPeriod.MONTH -> Res.string.listening_period_30d
	ListeningPeriod.QUARTER -> Res.string.listening_period_90d
	ListeningPeriod.YEAR -> Res.string.listening_period_365d
	ListeningPeriod.ALL -> Res.string.listening_period_all
}

// ----- the tabs -----------------------------------------------------------------------------------

private fun tabLabel(tab: ListeningTab): StringResource = when (tab) {
	ListeningTab.OVERVIEW -> Res.string.listening_tab_overview
	ListeningTab.ACTIVITY -> Res.string.listening_tab_activity
	ListeningTab.HISTORY -> Res.string.listening_tab_history
	ListeningTab.NOT_IN_LIBRARY -> Res.string.listening_tab_unowned
}

/** With no summary to lay out: the failure with its Retry, or the read still out. True when drawn. */
private fun androidx.compose.foundation.lazy.LazyListScope.summaryMissing(
	state: ListeningUi,
	onRetry: () -> Unit
): Boolean {
	if (state.summary != null) return false
	item(key = "s:summary-empty") {
		when {
			// Nothing cached and nothing came back. Said as a failure, with a way to try
			// again — an empty history is a summary of zeros and looks nothing like this.
			state.summaryFailed -> FailedLine(
				stringResource(Res.string.listening_summary_failed),
				onRetry
			)
			state.summaryLoading -> Box(
				Modifier.fillMaxWidth().padding(vertical = 32.dp),
				contentAlignment = Alignment.Center
			) { ContainedLoadingIndicator() }
		}
	}
	return true
}

// ----- Overview: the numbers, the rankings, the genres -----------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.overviewItems(
	state: ListeningUi,
	starredSongs: Int,
	open: (ListeningTarget) -> Unit,
	onRetry: () -> Unit,
	dim: () -> Float
) {
	if (summaryMissing(state, onRetry)) return
	val summary = state.summary ?: return

	item(key = "s:tiles") { PeriodBound(dim) { StatTiles(summary, starredSongs) } }
	if (summary.totals.plays == 0) {
		item(key = "s:no-plays") { PeriodBound(dim) { Caption(stringResource(Res.string.listening_no_plays)) } }
	}

	// Top artists as Stats ranks them (`TopArtistItem`): the plays over the name and a bar to the
	// top artist's scale, in the colours of the artist's picture where the library has one. The
	// albums and tracks are its carousels, as Stats draws its top albums and songs.
	if (summary.topArtists.isNotEmpty()) {
		item(key = "s:top-artists") { PeriodBound(dim) { SectionTitle(stringResource(Res.string.listening_top_artists)) } }
		val top = summary.topArtists.maxOf { it.plays }
		itemsIndexed(summary.topArtists, key = { index, _ -> "s:top-artist:$index" }) { _, artist ->
			val preferenceManager = koinInject<PreferenceManager>()
			val art = libraryArtistArt(artist.ndArtistId, state.artistArt)
			RankingItem(
				name = artist.name,
				overline = pluralStringResource(Res.plurals.listening_plays, artist.plays, artist.plays),
				ratio = genreBarFraction(artist.plays, top),
				barCoverArtId = (art as? LbArt.Library)?.coverArtId,
				onClick = clickFor(
					artistTarget(artist.ndArtistId, artist.mbid, artist.name, state.knownArtistIds),
					open
				),
				modifier = Modifier.animateItem().graphicsLayer { alpha = dim() }.fullBleed(),
				leading = {
					LbArtImage(
						art,
						Modifier.size(56.dp),
						contentDescription = artist.name,
						shape = preferenceManager.artistImageShape.decreasedShape
					)
				}
			)
		}
	}
	if (summary.topAlbums.isNotEmpty()) {
		item(key = "s:top-albums") {
			PeriodBound(dim) { ArtCarousel(
				title = stringResource(Res.string.listening_top_albums),
				items = summary.topAlbums.toImmutableList(),
				modifier = Modifier.fullBleed()
			) { album ->
				RankCard(
					art = albumCardArt(album.ndAlbumId, album.rgid, state.albumArt),
					title = album.title,
					// lb-bot's `owned`, and only that: "the library holds it" — by its
					// index, or by Navidrome's own album list. Not Fresh's narrower
					// `releaseOwned`, which is the index alone.
					subtitle = listOfNotNull(
						album.artist.ifBlank { null },
						if (album.owned) null else stringResource(Res.string.label_not_in_library)
					).joinToString(" • "),
					plays = album.plays,
					// An owned album with no page here (lb-bot does not know its id yet,
					// or this device has not synced it) is not a link to the acquire page.
					onClick = clickFor(
						albumTarget(
							album.ndAlbumId, album.rgid, album.artistMbid, album.artist,
							album.title, state.knownAlbumIds, owned = album.owned
						),
						open
					)
				)
			} }
		}
	}
	if (summary.topTracks.isNotEmpty()) {
		item(key = "s:top-tracks") {
			PeriodBound(dim) { ArtCarousel(
				title = stringResource(Res.string.listening_top_tracks),
				items = summary.topTracks.toImmutableList(),
				modifier = Modifier.fullBleed()
			) { track ->
				RankCard(
					// A track is drawn with, and opens, the album it is on; there is no
					// page for a recording.
					art = albumCardArt(track.ndAlbumId, track.rgid, state.albumArt),
					title = track.title,
					subtitle = track.artist,
					plays = track.plays,
					onClick = clickFor(
						albumTarget(
							track.ndAlbumId, track.rgid, "", track.artist, "", state.knownAlbumIds
						),
						open
					)
				)
			} }
		}
	}
	if (summary.genres.isNotEmpty()) {
		item(key = "s:genres") {
			PeriodBound(dim) { Column(Modifier.padding(bottom = 16.dp)) {
				SectionTitle(stringResource(Res.string.listening_genres))
				// Genres come from the library's tags, so only plays of albums the library
				// holds can be counted. Both numbers are the summary's own.
				Caption(
					stringResource(Res.string.listening_genres_from, summary.genrePlays, summary.totals.plays),
					Modifier.padding(bottom = 8.dp)
				)
				// A ranking read DOWN the screen — rank, genre, its bar, plays — not a sideways
				// row: a genre has no picture to make a card of, and the bars only compare when
				// they share a left edge.
				val top = summary.genres.maxOfOrNull { it.plays } ?: 0
				summary.genres.forEachIndexed { index, genre ->
					GenreRow(index + 1, genre.genre, genre.plays, genreBarFraction(genre.plays, top))
				}
			} }
		}
	}
}

/**
 * One genre of the ranking, drawn as Stats draws a top artist (`RankingItem`): the plays over the
 * name, and a bar to the busiest genre's scale. Its rank stands where the artist's picture would,
 * a genre having none.
 */
@Composable
private fun GenreRow(rank: Int, name: String, plays: Int, fraction: Float) {
	RankingItem(
		name = name,
		overline = pluralStringResource(Res.plurals.listening_plays, plays, plays),
		ratio = fraction,
		barCoverArtId = null,
		onClick = null,
		modifier = Modifier.fullBleed(),
		leading = {
			Text(
				rank.toString(),
				style = MaterialTheme.typography.titleMedium,
				fontFamily = defaultFont(round = 100f),
				textAlign = TextAlign.Center,
				modifier = Modifier.width(32.dp)
			)
		}
	)
}

// ----- Activity: the heat map, then the period's own charts ---------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.activityItems(
	state: ListeningUi,
	periods: @Composable () -> Unit,
	onRetry: () -> Unit,
	dim: () -> Float
) {
	val summary = state.summary
	if (state.heatmap.isNotEmpty()) {
		// First, and ABOVE the picker: lb-bot sends the same days with every period's summary,
		// so the picker does not change it — and its caption says which days those are. Drawn
		// from its own state, not from the summary: a period change clears the summary, and
		// the map must stay where it is with the picker under it (`keptHeatmap`).
		item(key = "s:heatmap") {
			val grid = remember(state.heatmap) { heatmapGrid(state.heatmap) }
			Column {
				SectionTitle(stringResource(Res.string.listening_heatmap_title))
				// The range actually drawn, and that it is always this one: lb-bot sends the
				// last sixteen weeks of days whatever the period is.
				heatmapSpan(grid)?.let { span ->
					Caption(
						pluralStringResource(
							Res.plurals.listening_heatmap_caption, span.weeks,
							span.first, span.last, span.weeks
						),
						Modifier.padding(bottom = 8.dp)
					)
				}
				Heatmap(grid)
			}
		}
	}
	item(key = "s:periods") { periods() }
	if (summaryMissing(state, onRetry)) return
	if (summary == null) return
	item(key = "s:daily") {
		PeriodBound(dim) { Column {
			SectionTitle(stringResource(Res.string.listening_daily_title))
			val series = remember(summary.daily) { daySeries(summary.daily) }
			// lb-bot charts at most the newest ninety days of a period: a year is not drawn
			// as ninety bars without saying so.
			if (listeningDailyCapped(state.period)) {
				Caption(
					stringResource(Res.string.listening_daily_capped, LISTENING_DAILY_MAX_DAYS),
					Modifier.padding(bottom = 8.dp)
				)
			}
			if (series.isEmpty()) Caption(stringResource(Res.string.listening_no_plays), Modifier.padding(bottom = 16.dp))
			else DayChart(series)
		} }
	}
	item(key = "s:hours") {
		PeriodBound(dim) { Column {
			SectionTitle(stringResource(Res.string.listening_hours_title))
			HoursChart(remember(summary.hours) { hourBars(summary.hours) })
		} }
	}
}

/**
 * The numbers as Stats draws its own (`StatSummaryCard`), and in its order: the listening time
 * across the page, then the plays beside upstream's starred songs, then the history's own figures
 * two to a row. Everything but the starred songs is lb-bot's, over the period picked.
 */
@Composable
private fun StatTiles(summary: LbListeningSummary, starredSongs: Int) {
	val totals = summary.totals
	val duration = listeningDuration(totals.seconds)
	val time = when {
		duration.days > 0 -> stringResource(Res.string.listening_time_dh, duration.days, duration.hours)
		duration.hours > 0 -> stringResource(Res.string.listening_time_hm, duration.hours, duration.minutes)
		else -> stringResource(Res.string.listening_time_m, duration.minutes)
	}
	// The time sums only the listens that carried a duration. When that is not all of them, a
	// line under the cards says how many it is: otherwise the figure reads as the whole period's.
	val coverage = listeningTimeCoverage(totals)?.let {
		stringResource(Res.string.listening_time_from, it.known, it.plays)
	}
	val scheme = MaterialTheme.colorScheme
	Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
		// Full width, as the grid cell Stats puts it in makes it.
		StatSummaryCard(
			modifier = Modifier.fillMaxWidth(),
			color = scheme.primary,
			contentColor = scheme.onPrimary,
			horizontal = true,
			label = stringResource(Res.string.listening_stat_time),
			value = time,
			icon = {
				Surface(
					color = scheme.onPrimary,
					contentColor = scheme.primary,
					shape = MaterialShapes.Cookie9Sided.toShape()
				) {
					Icon(
						imageVector = Icons.Filled.Headphones,
						contentDescription = null,
						modifier = Modifier.padding(16.dp).size(32.dp)
					)
				}
			}
		)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			StatTile(
				label = stringResource(Res.string.listening_stat_plays), value = totals.plays.toString(),
				icon = Icons.Outlined.Note, shape = MaterialShapes.Clover4Leaf,
				color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer
			)
			StatTile(
				label = stringResource(Res.string.info_starred_songs).toLowerCase(LocalLocale.current),
				value = starredSongs.toString(),
				icon = Icons.Filled.Star, shape = MaterialShapes.VerySunny,
				color = scheme.tertiaryContainer, contentColor = scheme.onTertiaryContainer
			)
		}
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			StatTile(
				label = stringResource(Res.string.listening_stat_artists), value = totals.artists.toString(),
				icon = Icons.Outlined.Artist, shape = MaterialShapes.Cookie6Sided,
				color = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer
			)
			StatTile(
				label = stringResource(Res.string.listening_stat_albums), value = totals.albums.toString(),
				icon = Icons.Outlined.Album, shape = MaterialShapes.Clover8Leaf,
				color = scheme.tertiaryContainer, contentColor = scheme.onTertiaryContainer
			)
		}
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			StatTile(
				label = stringResource(Res.string.listening_stat_streak),
				value = pluralStringResource(Res.plurals.listening_days, summary.streak.current, summary.streak.current),
				icon = Icons.Outlined.History, shape = MaterialShapes.Sunny,
				color = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer
			)
			StatTile(
				label = stringResource(Res.string.listening_stat_longest_streak),
				value = pluralStringResource(Res.plurals.listening_days, summary.streak.longest, summary.streak.longest),
				icon = Icons.Outlined.Sparkle, shape = MaterialShapes.Flower,
				color = scheme.tertiaryContainer, contentColor = scheme.onTertiaryContainer
			)
		}
		if (coverage != null) Caption(coverage)
	}
}

@Composable
private fun RowScope.StatTile(
	label: String,
	value: String,
	icon: ImageVector,
	shape: RoundedPolygon,
	color: Color,
	contentColor: Color
) {
	StatSummaryCard(
		modifier = Modifier.weight(1f),
		color = color,
		contentColor = contentColor,
		label = label,
		value = value,
		icon = {
			Surface(color = contentColor, contentColor = color, shape = shape.toShape()) {
				Icon(icon, contentDescription = null, modifier = Modifier.padding(12.dp))
			}
		}
	)
}

/**
 * Whole weeks of days, Monday at the top, oldest column first, ending with the current week.
 * Drawn, not laid out: over a hundred cells as composables would be as many nodes for a picture.
 */
@Composable
private fun Heatmap(grid: ListeningHeatmap) {
	val on = MaterialTheme.colorScheme.primary
	val off = MaterialTheme.colorScheme.surfaceContainerHighest
	val columns = grid.weeks.size
	if (columns == 0) return
	val gap = 2.dp
	BoxWithConstraints(Modifier.fillMaxWidth()) {
		// What the width allows, and never more than the cap (`heatmapCellSize`): with one or
		// two weeks the squares would otherwise be as wide as the screen. The grid then sits
		// at the start of the row rather than stretched across it.
		val cell = with(LocalDensity.current) {
			heatmapCellSize(maxWidth.toPx(), columns, gap.toPx(), HEATMAP_CELL_MAX.toPx()).toDp()
		}
		Canvas(Modifier.size(width = cell * columns + gap * (columns - 1), height = cell * 7 + gap * 6)) {
			val gapPx = gap.toPx()
			val cellPx = cell.toPx()
			val corner = CornerRadius(cellPx * 0.2f)
			grid.weeks.forEachIndexed { x, week ->
				week.forEachIndexed { y, day ->
					// A null cell is a day of the current week not yet reached (or, in a history
					// with no Monday in it, a weekday before it began): drawn as a faint
					// placeholder, so the grid is a clean rectangle and every row ends together.
					drawRoundRect(
						color = when {
							day == null -> off.copy(alpha = 0.35f)
							day.level == 0 -> off
							else -> on.copy(alpha = 0.25f * day.level)
						},
						topLeft = Offset(x * (cellPx + gapPx), y * (cellPx + gapPx)),
						size = Size(cellPx, cellPx),
						cornerRadius = corner
					)
				}
			}
		}
	}
	val days = grid.weeks.flatten().filterNotNull()
	Row(
		Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
		horizontalArrangement = Arrangement.SpaceBetween
	) {
		Caption(days.firstOrNull()?.date.orEmpty())
		if (grid.max > 0) Caption(stringResource(Res.string.listening_heatmap_scale, grid.max))
		Caption(days.lastOrNull()?.date.orEmpty())
	}
}

/** Bars to one scale: the tallest is the busiest slot, a slot with no plays keeps a hairline. */
@Composable
private fun Bars(bars: List<Int>) {
	val on = MaterialTheme.colorScheme.primary
	val off = MaterialTheme.colorScheme.surfaceContainerHighest
	val max = bars.maxOrNull() ?: 0
	Canvas(Modifier.fillMaxWidth().height(96.dp)) {
		if (bars.isEmpty()) return@Canvas
		val gap = if (bars.size > 60) 1.dp.toPx() else 2.dp.toPx()
		val width = ((size.width - gap * (bars.size - 1)) / bars.size).coerceAtLeast(1f)
		val floor = 2.dp.toPx()
		bars.forEachIndexed { slot, plays ->
			val height = if (max > 0 && plays > 0) (size.height * plays / max).coerceAtLeast(floor) else floor
			drawRoundRect(
				color = if (plays > 0) on else off,
				topLeft = Offset(slot * (width + gap), size.height - height),
				size = Size(width, height),
				cornerRadius = CornerRadius(width * 0.25f)
			)
		}
	}
}

/** Plays per local hour, 00 to 23. The tallest bar is the busiest hour; the rest are to scale. */
@Composable
private fun HoursChart(bars: List<Int>) {
	Bars(bars)
	Row(
		Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
		horizontalArrangement = Arrangement.SpaceBetween
	) {
		listOf("00", "06", "12", "18", "23").forEach { Caption(it) }
	}
}

/**
 * Plays per calendar day, oldest first. **Its axis is dates** — the first day, the last and a
 * few between (`dayAxisLabels`) — never the hours chart's.
 */
@Composable
private fun DayChart(series: List<LbDayCount>) {
	Bars(remember(series) { series.map { it.count } })
	Row(
		Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
		horizontalArrangement = Arrangement.SpaceBetween
	) {
		remember(series) { dayAxisLabels(series) }.forEach { (_, label) -> Caption(label) }
	}
}

// ----- most played, not in the library ----------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.notInLibraryItems(
	state: ListeningUi,
	open: (ListeningTarget) -> Unit,
	onKind: (String) -> Unit,
	onRetry: () -> Unit,
	dim: () -> Float
) {
	item(key = "s:unowned") {
		Column {
			SectionTitle(stringResource(Res.string.listening_unowned_title))
			// What lb-bot lists here is albums and artists; it has no per-song list.
			PageChips(
				options = listOf(LISTENING_KIND_ALBUM, LISTENING_KIND_ARTIST),
				selected = state.unownedKind,
				label = {
					stringResource(
						if (it == LISTENING_KIND_ARTIST) Res.string.listening_unowned_artists
						else Res.string.listening_unowned_albums
					)
				},
				onSelect = onKind,
				contentPadding = PaddingValues(bottom = 8.dp)
			)
			val failure = state.unownedFailure
			val artists = state.unownedKind == LISTENING_KIND_ARTIST
			when {
				// Cover cards, in one horizontally scrollable row. They open the external album
				// or artist page, which offers the reviewed acquire flow; a card downloads nothing.
				state.unowned.isNotEmpty() -> LazyRow(
					modifier = Modifier.graphicsLayer { alpha = dim() }.fullBleed()
						.padding(top = 8.dp, bottom = 16.dp),
					horizontalArrangement = Arrangement.spacedBy(12.dp),
					contentPadding = PaddingValues(horizontal = 16.dp)
				) {
					items(state.unowned) { row ->
						RankCard(
							art = unownedCardArt(artists, row, state.artistArt),
							// An artist row has no title; the artist is then the card.
							title = row.title.ifBlank { row.artist },
							subtitle = row.artist.takeIf { row.title.isNotBlank() },
							plays = row.plays,
							isArtist = artists,
							onClick = clickFor(unownedTarget(row, state.knownArtistIds), open)
						)
					}
				}
				// lb-bot's own sentence when it wrote one (a 503 while Navidrome's lists are
				// unavailable, a 400); an lb-bot older than the route is said as that, quietly
				// and without a Retry that could only get the same answer.
				failure == ListeningListFailure.Older ->
					Caption(stringResource(Res.string.listening_older), Modifier.padding(bottom = 24.dp))
				failure is ListeningListFailure.Said -> FailedLine(failure.text, onRetry)
				failure != null -> FailedLine(stringResource(Res.string.listening_list_failed), onRetry)
				state.unownedLoading -> SmallSpinner()
				else -> Caption(stringResource(Res.string.listening_unowned_empty), Modifier.padding(bottom = 24.dp))
			}
		}
	}
}

// ----- History: recent plays, as the queue shows songs ---------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.historyItems(
	state: ListeningUi,
	open: (ListeningTarget) -> Unit,
	onMenu: (LbRecentPlay) -> Unit,
	onMore: () -> Unit,
	onRetry: () -> Unit
) {
	item(key = "s:recent-title") { SectionTitle(stringResource(Res.string.listening_recent_title)) }
	// The key carries the index: the same recording scrobbled twice in a second is two identical
	// rows, and a duplicate key in a lazy list is a crash.
	itemsIndexed(state.recent, key = { index, row -> recentPlayKey(index, row) }) { index, row ->
		// A long press opens the app's own menu — the song's when it can be told apart, else
		// the album's or the artist's — and is offered only where the library holds one of them.
		val menu = heldInLibrary(row.ndAlbumId, state.albumArt) || heldInLibrary(row.ndArtistId, state.artistArt)
		HistoryRow(
			row,
			art = albumCardArt(row.ndAlbumId, row.rgid, state.albumArt),
			shapes = segmentedShapes(index = index, count = state.recent.size),
			onClick = clickFor(recentPlayTarget(row, state.knownAlbumIds, state.knownArtistIds), open),
			onMenu = if (menu) ({ onMenu(row) }) else null,
			modifier = Modifier.padding(bottom = SegmentedListItemDefaults.SegmentedGap)
		)
	}
	item(key = "s:recent-foot") {
		when {
			state.recentLoading -> SmallSpinner()
			state.recentFailed -> FailedLine(stringResource(Res.string.listening_list_failed), onRetry)
			state.recent.isEmpty() -> Caption(stringResource(Res.string.listening_recent_empty))
			// Offered only while lb-bot says there is another page.
			state.recentNextBefore != null -> TextButton(onClick = onMore) {
				Text(stringResource(Res.string.listening_recent_more))
			}
		}
	}
}

/**
 * One play, as the queue draws a song: a segmented row with the album's cover, the title, the
 * artist and the album under it, and when it was played. The cover is the library's own where
 * Room holds the album, the Archive's front where it does not, the placeholder where nothing
 * names one.
 *
 * A tap does something or nothing (`historyRowGesture`): a row with only a menu opens it on a tap
 * as on a long press, and a row with neither is drawn inert, in its usual colours.
 */
@Composable
private fun HistoryRow(
	row: LbRecentPlay,
	art: LbArt,
	shapes: ListItemShapes,
	onClick: (() -> Unit)?,
	onMenu: (() -> Unit)?,
	modifier: Modifier = Modifier
) {
	val zone = remember { TimeZone.currentSystemDefault() }
	val preferenceManager = koinInject<PreferenceManager>()
	val gesture = historyRowGesture(hasTarget = onClick != null, hasMenu = onMenu != null)
	val container = SongRowDefaults.containerColor(false)
	val supporting = SongRowDefaults.supportingContentColor(false)
	SegmentedListItem(
		onClick = when (gesture) {
			HistoryRowGesture.MENU_ONLY -> onMenu ?: {}
			else -> onClick ?: {}
		},
		onLongClick = if (gesture == HistoryRowGesture.TAP_AND_MENU) onMenu else null,
		enabled = gesture != HistoryRowGesture.NONE,
		shapes = shapes,
		modifier = modifier,
		colors = ListItemDefaults.colors(
			containerColor = container,
			supportingContentColor = supporting,
			disabledContainerColor = container,
			disabledContentColor = MaterialTheme.colorScheme.onSurface,
			disabledLeadingContentColor = MaterialTheme.colorScheme.onSurface,
			disabledSupportingContentColor = supporting
		),
		verticalAlignment = Alignment.CenterVertically,
		contentPadding = SongRowDefaults.ContentPadding,
		leadingContent = {
			LbArtImage(
				art,
				Modifier.size(50.dp),
				contentDescription = row.release.ifBlank { row.track },
				shape = preferenceManager.coverArtShape.decreasedShape
			)
		},
		content = { Text(row.track, maxLines = 1, overflow = TextOverflow.Ellipsis) },
		supportingContent = {
			Column {
				Text(
					listOf(row.artist, row.release).filter { it.isNotBlank() }.joinToString(" • "),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
				Text(
					listOf(formatListenedAt(row.listenedAt, zone), row.client)
						.filter { it.isNotBlank() }.joinToString(" • "),
					style = MaterialTheme.typography.labelSmall,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
			}
		}
	)
}

// ----- small pieces ---------------------------------------------------------------------------

/**
 * One ranked thing as upstream's carousel card (`ArtCarouselItem`): its picture (an album's cover,
 * an artist's image, from lb-bot's art where the library has none), its name on two lines, a
 * second line, and its plays. Where there is nowhere to go the card does nothing on a tap.
 */
@Composable
private fun RankCard(
	art: LbArt,
	title: String,
	subtitle: String?,
	plays: Int,
	isArtist: Boolean = false,
	onClick: (() -> Unit)?
) {
	val preferenceManager = koinInject<PreferenceManager>()
	ArtCarouselItem(
		coverArtId = null,
		title = title,
		subtitle = subtitle?.ifBlank { null },
		playCount = plays,
		contentDescription = title,
		isArtist = isArtist,
		cover = { source ->
			LbArtImage(
				art,
				Modifier.fillMaxWidth(),
				contentDescription = title,
				shape = if (isArtist) preferenceManager.artistImageShape.shape else null,
				interactionSource = source
			)
		},
		onClick = onClick ?: {}
	)
}

/**
 * Lets a row of cards run to the screen's edges inside the page's 16dp gutter, as upstream's
 * carousels do on its own pages: the row is measured 32dp wider and drawn 16dp to the left.
 */
private fun Modifier.fullBleed(): Modifier = layout { measurable, constraints ->
	val gutter = 16.dp.roundToPx()
	val placeable = measurable.measure(
		constraints.copy(
			minWidth = constraints.minWidth + gutter * 2,
			maxWidth = constraints.maxWidth + gutter * 2
		)
	)
	layout(constraints.maxWidth, placeable.height) { placeable.place(-gutter, 0) }
}

/**
 * An item whose content is a period's figures: eased in, out and into place when the period
 * changes, and dimmed while the next period's are read ([STALE_ALPHA]).
 */
@Composable
private fun androidx.compose.foundation.lazy.LazyItemScope.PeriodBound(
	dim: () -> Float,
	content: @Composable () -> Unit
) {
	Box(Modifier.animateItem().graphicsLayer { alpha = dim() }) { content() }
}

/** A section's title, as upstream's Statistics page titles its top artists. */
@Composable
private fun SectionTitle(text: String) {
	Text(
		text,
		style = MaterialTheme.typography.titleMediumEmphasized,
		fontWeight = FontWeight(600),
		modifier = Modifier
			.heightIn(min = 32.dp)
			.padding(top = 12.dp, bottom = 8.dp)
			.semantics { heading() }
	)
}

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) {
	Text(
		text,
		style = MaterialTheme.typography.bodySmall,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = modifier
	)
}

@Composable
private fun SmallSpinner() {
	Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
		ContainedLoadingIndicator(Modifier.size(48.dp))
	}
}

/**
 * A read that failed, and the way to try it again, in `ErrorBox`'s colours and with its retry
 * button. Not `ErrorBox` itself: that says "An error occurred", and these say what failed, in
 * lb-bot's own words where it gave some. Never an empty state's wording.
 */
@Composable
private fun FailedLine(text: String, onRetry: () -> Unit) {
	Surface(
		color = MaterialTheme.colorScheme.errorContainer,
		contentColor = MaterialTheme.colorScheme.onErrorContainer,
		shape = MaterialTheme.shapes.small,
		modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
	) {
		Row(
			modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
			IconButton(onClick = onRetry) {
				Icon(Icons.Outlined.Refresh, contentDescription = stringResource(Res.string.action_retry))
			}
		}
	}
}
