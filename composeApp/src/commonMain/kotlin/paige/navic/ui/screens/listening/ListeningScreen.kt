package paige.navic.ui.screens.listening

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import navic.composeapp.generated.resources.label_not_in_library
import navic.composeapp.generated.resources.listening_days
import navic.composeapp.generated.resources.listening_genres
import navic.composeapp.generated.resources.listening_genres_from
import navic.composeapp.generated.resources.listening_heatmap_scale
import navic.composeapp.generated.resources.listening_heatmap_title
import navic.composeapp.generated.resources.listening_hours_title
import navic.composeapp.generated.resources.listening_importing
import navic.composeapp.generated.resources.listening_importing_note
import navic.composeapp.generated.resources.listening_importing_unknown
import navic.composeapp.generated.resources.listening_list_failed
import navic.composeapp.generated.resources.listening_no_plays
import navic.composeapp.generated.resources.listening_off
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
import navic.composeapp.generated.resources.title_listening
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.di.LocalNavStack
import paige.navic.domain.manager.LISTENING_KIND_ALBUM
import paige.navic.domain.manager.LISTENING_KIND_ARTIST
import paige.navic.domain.manager.LbListeningSummary
import paige.navic.domain.manager.LbRecentPlay
import paige.navic.domain.manager.LbUnownedRow
import paige.navic.domain.manager.ListeningHeatmap
import paige.navic.domain.manager.ListeningListFailure
import paige.navic.domain.manager.ListeningPeriod
import paige.navic.domain.manager.ListeningPhase
import paige.navic.domain.manager.ListeningTarget
import paige.navic.domain.manager.albumTarget
import paige.navic.domain.manager.artistTarget
import paige.navic.domain.manager.formatListenedAt
import paige.navic.domain.manager.heatmapGrid
import paige.navic.domain.manager.hourBars
import paige.navic.domain.manager.listeningDuration
import paige.navic.domain.manager.listeningTimeCoverage
import paige.navic.domain.manager.recentPlayKey
import paige.navic.domain.manager.recentPlayTarget
import paige.navic.domain.manager.unownedTarget
import paige.navic.icons.Icons
import paige.navic.icons.filled.Headphones
import paige.navic.ui.components.common.ContentUnavailable
import paige.navic.ui.components.common.Form
import paige.navic.ui.components.common.FormRow
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.components.layouts.PullToRefreshBox
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.listening.viewmodels.ListeningUi
import paige.navic.ui.screens.listening.viewmodels.ListeningViewModel

/**
 * How often the status is read while an import runs. It is the progress readout, nothing else is
 * polled, and it stops the moment the screen is not visible or the import ends.
 */
private const val STATUS_POLL_MS = 5_000L

/**
 * My Listening (I-006): the user's ListenBrainz history, by the numbers.
 *
 * lb-bot mirrors the listens and computes every figure here; this screen only lays them out, and
 * says what each was measured over where that is less than everything (the listening time, the
 * genres). What the page is at all follows the mirror's state:
 *
 * - **off** — lb-bot has no ListenBrainz user: one explanation, nothing else;
 * - **importing** — the first backfill runs: "N of M" from the status, polled, over whatever the
 *   summary already holds;
 * - **ready** — the page;
 * - **error** — the last sync failed: lb-bot's own text, over the plays already stored;
 * - lb-bot **unreachable** — the summary this device loaded last, said to be that; or a failure
 *   line with a Retry when there is none;
 * - an lb-bot **older** than the routes — one quiet sentence. Not an error.
 *
 * Rows link out and never download: what the library holds opens its library page, what it does
 * not opens the external album or artist page, where the reviewed acquire flow already lives.
 */
@Composable
fun ListeningScreen() {
	val viewModel = koinViewModel<ListeningViewModel>()
	val state by viewModel.state.collectAsStateWithLifecycle()
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

	Scaffold(
		topBar = { NestedTopBar({ Text(stringResource(Res.string.title_listening)) }) },
		contentWindowInsets = WindowInsets.statusBars
	) { innerPadding ->
		PullToRefreshBox(
			modifier = Modifier.padding(top = innerPadding.calculateTopPadding()),
			finished = !state.loading,
			onRefresh = { viewModel.refresh() },
			key = state.loading
		) {
			LazyColumn(
				modifier = Modifier.fillMaxSize(),
				contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 48.dp)
			) {
				when (val phase = state.phase) {
					ListeningPhase.Loading -> item(key = "s:loading") {
						Box(
							Modifier.fillMaxWidth().padding(vertical = 64.dp),
							contentAlignment = Alignment.Center
						) { CircularProgressIndicator() }
					}

					ListeningPhase.Off -> item(key = "s:off") {
						ContentUnavailable(
							modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
							icon = Icons.Filled.Headphones,
							label = stringResource(Res.string.listening_off)
						)
					}

					// An lb-bot that predates the routes. A fact about the server, said once
					// and quietly; there is nothing to retry and nothing went wrong.
					ListeningPhase.Older -> item(key = "s:older") {
						ContentUnavailable(
							modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
							icon = Icons.Filled.Headphones,
							label = stringResource(Res.string.listening_older)
						)
					}

					else -> {
						item(key = "s:banner") { PhaseBanner(phase, hasSummary = state.summary != null) }
						item(key = "s:periods") {
							PeriodChips(state.period, onSelect = { viewModel.setPeriod(it) })
						}
						summaryItems(state, open, onRetry = { viewModel.refresh() })
						// The two live lists need lb-bot. With it unreachable they are left
						// out rather than shown failed under a banner that already says why.
						if (phase != ListeningPhase.Unreachable) {
							unownedItems(
								state,
								open,
								onKind = { viewModel.setUnownedKind(it) },
								onRetry = { viewModel.retryUnowned() }
							)
							recentItems(
								state,
								open,
								onMore = { viewModel.loadMoreRecent() },
								onRetry = { viewModel.retryRecent() }
							)
						}
					}
				}
			}
		}
	}
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
	Form(bottomPadding = 12.dp) {
		FormRow {
			Column(Modifier.weight(1f)) {
				lines.forEachIndexed { index, line ->
					Text(
						line,
						style = if (index == 0) MaterialTheme.typography.bodyMedium
						else MaterialTheme.typography.bodySmall,
						color = if (index == 0) MaterialTheme.colorScheme.onSurface
						else MaterialTheme.colorScheme.onSurfaceVariant
					)
				}
			}
			if (phase is ListeningPhase.Importing) {
				CircularProgressIndicator(Modifier.padding(start = 12.dp).size(18.dp), strokeWidth = 2.dp)
			}
		}
	}
}

private fun periodLabel(period: ListeningPeriod): StringResource = when (period) {
	ListeningPeriod.WEEK -> Res.string.listening_period_7d
	ListeningPeriod.MONTH -> Res.string.listening_period_30d
	ListeningPeriod.QUARTER -> Res.string.listening_period_90d
	ListeningPeriod.YEAR -> Res.string.listening_period_365d
	ListeningPeriod.ALL -> Res.string.listening_period_all
}

@Composable
private fun PeriodChips(selected: ListeningPeriod, onSelect: (ListeningPeriod) -> Unit) {
	Row(
		Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
		horizontalArrangement = Arrangement.spacedBy(8.dp)
	) {
		ListeningPeriod.entries.forEach { period ->
			FilterChip(
				selected = period == selected,
				onClick = { onSelect(period) },
				label = { Text(stringResource(periodLabel(period))) }
			)
		}
	}
}

// ----- the summary --------------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.summaryItems(
	state: ListeningUi,
	open: (ListeningTarget) -> Unit,
	onRetry: () -> Unit
) {
	val summary = state.summary
	if (summary == null) {
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
				) { CircularProgressIndicator() }
			}
		}
		return
	}

	item(key = "s:tiles") { StatTiles(summary) }
	if (summary.totals.plays == 0) {
		item(key = "s:no-plays") { Caption(stringResource(Res.string.listening_no_plays)) }
	}
	item(key = "s:heatmap") {
		Column {
			SectionTitle(stringResource(Res.string.listening_heatmap_title))
			Heatmap(remember(summary.heatmap) { heatmapGrid(summary.heatmap) })
		}
	}
	item(key = "s:hours") {
		Column {
			SectionTitle(stringResource(Res.string.listening_hours_title))
			HoursChart(remember(summary.hours) { hourBars(summary.hours) })
		}
	}

	if (summary.topArtists.isNotEmpty()) {
		item(key = "s:top-artists") {
			Column {
				SectionTitle(stringResource(Res.string.listening_top_artists))
				Form {
					summary.topArtists.forEachIndexed { index, artist ->
						RankedRow(
							rank = index + 1,
							title = artist.name,
							subtitle = null,
							plays = artist.plays,
							onClick = clickFor(
								artistTarget(artist.ndArtistId, artist.mbid, artist.name, state.knownArtistIds),
								open
							)
						)
					}
				}
			}
		}
	}
	if (summary.topAlbums.isNotEmpty()) {
		item(key = "s:top-albums") {
			Column {
				SectionTitle(stringResource(Res.string.listening_top_albums))
				Form {
					summary.topAlbums.forEachIndexed { index, album ->
						RankedRow(
							rank = index + 1,
							title = album.title,
							subtitle = album.artist,
							plays = album.plays,
							// lb-bot's `owned`, and only that: "the library holds it" — by its
							// index, or by Navidrome's own album list. Not Fresh's narrower
							// `releaseOwned`, which is the index alone.
							note = if (album.owned) null else stringResource(Res.string.label_not_in_library),
							// An owned album with no page here (lb-bot does not know its id yet,
							// or this device has not synced it) is plain text, never a link to
							// the acquire page.
							onClick = clickFor(
								albumTarget(
									album.ndAlbumId, album.rgid, album.artistMbid, album.artist,
									album.title, state.knownAlbumIds, owned = album.owned
								),
								open
							)
						)
					}
				}
			}
		}
	}
	if (summary.topTracks.isNotEmpty()) {
		item(key = "s:top-tracks") {
			Column {
				SectionTitle(stringResource(Res.string.listening_top_tracks))
				Form {
					summary.topTracks.forEachIndexed { index, track ->
						RankedRow(
							rank = index + 1,
							title = track.title,
							subtitle = track.artist,
							plays = track.plays,
							// A track opens the album it is on; there is no page for a recording.
							onClick = clickFor(
								albumTarget(
									track.ndAlbumId, track.rgid, "", track.artist, "", state.knownAlbumIds
								),
								open
							)
						)
					}
				}
			}
		}
	}
	if (summary.genres.isNotEmpty()) {
		item(key = "s:genres") {
			Column {
				SectionTitle(stringResource(Res.string.listening_genres))
				// Genres come from the library's tags, so only plays of albums the library
				// holds can be counted. Both numbers are the summary's own.
				Caption(
					stringResource(Res.string.listening_genres_from, summary.genrePlays, summary.totals.plays)
				)
				Form {
					summary.genres.forEach { genre ->
						FormRow {
							Text(
								genre.genre,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
								modifier = Modifier.weight(1f)
							)
							Plays(genre.plays)
						}
					}
				}
			}
		}
	}
}

@Composable
private fun StatTiles(summary: LbListeningSummary) {
	val totals = summary.totals
	val duration = listeningDuration(totals.seconds)
	val time = when {
		duration.days > 0 -> stringResource(Res.string.listening_time_dh, duration.days, duration.hours)
		duration.hours > 0 -> stringResource(Res.string.listening_time_hm, duration.hours, duration.minutes)
		else -> stringResource(Res.string.listening_time_m, duration.minutes)
	}
	// The time sums only the listens that carried a duration. When that is not all of them,
	// the tile says how many it is — otherwise the figure reads as the whole period's.
	val coverage = listeningTimeCoverage(totals)?.let {
		stringResource(Res.string.listening_time_from, it.known, it.plays)
	}
	Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			StatTile(stringResource(Res.string.listening_stat_plays), totals.plays.toString(), Modifier.weight(1f))
			StatTile(stringResource(Res.string.listening_stat_time), time, Modifier.weight(1f), note = coverage)
		}
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			StatTile(stringResource(Res.string.listening_stat_artists), totals.artists.toString(), Modifier.weight(1f))
			StatTile(stringResource(Res.string.listening_stat_albums), totals.albums.toString(), Modifier.weight(1f))
		}
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			StatTile(
				stringResource(Res.string.listening_stat_streak),
				pluralStringResource(Res.plurals.listening_days, summary.streak.current, summary.streak.current),
				Modifier.weight(1f)
			)
			StatTile(
				stringResource(Res.string.listening_stat_longest_streak),
				pluralStringResource(Res.plurals.listening_days, summary.streak.longest, summary.streak.longest),
				Modifier.weight(1f)
			)
		}
	}
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, note: String? = null) {
	FormRow(modifier = modifier, rounding = 14.dp) {
		Column {
			Text(
				label,
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
			Text(
				value,
				style = MaterialTheme.typography.headlineSmall,
				fontWeight = FontWeight.SemiBold,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
			if (note != null) {
				Text(
					note,
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}
		}
	}
}

/**
 * Sixteen weeks of days, oldest column first, the newest day bottom-right. Drawn, not laid out:
 * 112 cells as composables would be 112 nodes for a picture.
 */
@Composable
private fun Heatmap(grid: ListeningHeatmap) {
	val on = MaterialTheme.colorScheme.primary
	val off = MaterialTheme.colorScheme.surfaceContainerHighest
	val columns = grid.weeks.size.coerceAtLeast(1)
	Canvas(Modifier.fillMaxWidth().aspectRatio(columns / 7f)) {
		val gap = 2.dp.toPx()
		val cell = (size.width - gap * (columns - 1)) / columns
		val corner = CornerRadius(cell * 0.2f)
		grid.weeks.forEachIndexed { x, week ->
			week.forEachIndexed { y, day ->
				// A null cell is padding on the old side of a history shorter than the grid:
				// no day stands there, so nothing is drawn — not even the empty-day square.
				if (day == null) return@forEachIndexed
				drawRoundRect(
					color = if (day.level == 0) off else on.copy(alpha = 0.25f * day.level),
					topLeft = Offset(x * (cell + gap), y * (cell + gap)),
					size = Size(cell, cell),
					cornerRadius = corner
				)
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

/** Plays per local hour, 00 to 23. The tallest bar is the busiest hour; the rest are to scale. */
@Composable
private fun HoursChart(bars: List<Int>) {
	val on = MaterialTheme.colorScheme.primary
	val off = MaterialTheme.colorScheme.surfaceContainerHighest
	val max = bars.maxOrNull() ?: 0
	Canvas(Modifier.fillMaxWidth().height(96.dp)) {
		val gap = 2.dp.toPx()
		val width = (size.width - gap * (bars.size - 1)) / bars.size
		val floor = 2.dp.toPx()
		bars.forEachIndexed { hour, plays ->
			// An hour with no plays keeps a hairline, so the axis reads as 24 slots.
			val height = if (max > 0 && plays > 0) (size.height * plays / max).coerceAtLeast(floor) else floor
			drawRoundRect(
				color = if (plays > 0) on else off,
				topLeft = Offset(hour * (width + gap), size.height - height),
				size = Size(width, height),
				cornerRadius = CornerRadius(width * 0.25f)
			)
		}
	}
	Row(
		Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
		horizontalArrangement = Arrangement.SpaceBetween
	) {
		listOf("00", "06", "12", "18", "23").forEach { Caption(it) }
	}
}

// ----- most played, not in the library ----------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.unownedItems(
	state: ListeningUi,
	open: (ListeningTarget) -> Unit,
	onKind: (String) -> Unit,
	onRetry: () -> Unit
) {
	item(key = "s:unowned") {
		Column {
			SectionTitle(stringResource(Res.string.listening_unowned_title))
			Row(
				Modifier.padding(bottom = 8.dp),
				horizontalArrangement = Arrangement.spacedBy(8.dp)
			) {
				FilterChip(
					selected = state.unownedKind == LISTENING_KIND_ALBUM,
					onClick = { onKind(LISTENING_KIND_ALBUM) },
					label = { Text(stringResource(Res.string.listening_unowned_albums)) }
				)
				FilterChip(
					selected = state.unownedKind == LISTENING_KIND_ARTIST,
					onClick = { onKind(LISTENING_KIND_ARTIST) },
					label = { Text(stringResource(Res.string.listening_unowned_artists)) }
				)
			}
			val failure = state.unownedFailure
			when {
				state.unowned.isNotEmpty() -> Form {
					state.unowned.forEach { row -> UnownedRow(row, clickFor(unownedTarget(row, state.knownArtistIds), open)) }
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

/** Opens the external album or artist page. It offers no download of its own. */
@Composable
private fun UnownedRow(row: LbUnownedRow, onClick: (() -> Unit)?) {
	FormRow(onClick = onClick) {
		Column(Modifier.weight(1f)) {
			// An artist row has no title; the artist is then the row.
			Text(
				row.title.ifBlank { row.artist },
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
			if (row.title.isNotBlank() && row.artist.isNotBlank()) {
				Text(
					row.artist,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
			}
		}
		Plays(row.plays)
	}
}

// ----- recent plays -------------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.recentItems(
	state: ListeningUi,
	open: (ListeningTarget) -> Unit,
	onMore: () -> Unit,
	onRetry: () -> Unit
) {
	item(key = "s:recent-title") { SectionTitle(stringResource(Res.string.listening_recent_title)) }
	// The key carries the index: the same recording scrobbled twice in a second is two identical
	// rows, and a duplicate key in a lazy list is a crash.
	itemsIndexed(state.recent, key = { index, row -> recentPlayKey(index, row) }) { _, row ->
		RecentRow(
			row,
			clickFor(recentPlayTarget(row, state.knownAlbumIds, state.knownArtistIds), open)
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

@Composable
private fun RecentRow(row: LbRecentPlay, onClick: (() -> Unit)?) {
	val zone = remember { TimeZone.currentSystemDefault() }
	FormRow(modifier = Modifier.padding(bottom = 3.dp), onClick = onClick) {
		Column(Modifier.weight(1f)) {
			Text(row.track, maxLines = 1, overflow = TextOverflow.Ellipsis)
			Text(
				listOf(row.artist, row.release).filter { it.isNotBlank() }.joinToString(" • "),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
			Text(
				listOf(formatListenedAt(row.listenedAt, zone), row.client)
					.filter { it.isNotBlank() }.joinToString(" • "),
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
		}
	}
}

// ----- small pieces ---------------------------------------------------------------------------

@Composable
private fun RankedRow(
	rank: Int,
	title: String,
	subtitle: String?,
	plays: Int,
	onClick: (() -> Unit)?,
	note: String? = null
) {
	FormRow(onClick = onClick) {
		Text(
			rank.toString(),
			style = MaterialTheme.typography.labelLarge,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.width(28.dp)
		)
		Column(Modifier.weight(1f)) {
			Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
			val under = listOfNotNull(subtitle?.ifBlank { null }, note).joinToString(" • ")
			if (under.isNotBlank()) {
				Text(
					under,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
			}
		}
		Plays(plays)
	}
}

@Composable
private fun Plays(plays: Int) {
	Text(
		pluralStringResource(Res.plurals.listening_plays, plays, plays),
		style = MaterialTheme.typography.labelMedium,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = Modifier.padding(start = 12.dp)
	)
}

@Composable
private fun SectionTitle(text: String) {
	Text(
		text,
		style = MaterialTheme.typography.titleMedium,
		fontWeight = FontWeight.SemiBold,
		color = MaterialTheme.colorScheme.primary,
		modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
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
		CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
	}
}

/** A read that failed, and the way to try it again. Never an empty state's wording. */
@Composable
private fun FailedLine(text: String, onRetry: () -> Unit) {
	Column(
		Modifier.fillMaxWidth().padding(vertical = 16.dp),
		horizontalAlignment = Alignment.CenterHorizontally
	) {
		Text(
			text,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant
		)
		Button(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) {
			Text(stringResource(Res.string.action_retry))
		}
	}
}
