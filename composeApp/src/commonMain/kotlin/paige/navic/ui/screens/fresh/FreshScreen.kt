package paige.navic.ui.screens.fresh

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.fresh_bucket_earlier
import navic.composeapp.generated.resources.fresh_bucket_last_week
import navic.composeapp.generated.resources.fresh_bucket_this_week
import navic.composeapp.generated.resources.fresh_bucket_three_weeks
import navic.composeapp.generated.resources.fresh_bucket_two_weeks
import navic.composeapp.generated.resources.fresh_bucket_upcoming
import navic.composeapp.generated.resources.fresh_chip_in_library
import navic.composeapp.generated.resources.fresh_chip_upcoming
import navic.composeapp.generated.resources.fresh_empty_all
import navic.composeapp.generated.resources.fresh_empty_type
import navic.composeapp.generated.resources.fresh_empty_yours
import navic.composeapp.generated.resources.fresh_failed
import navic.composeapp.generated.resources.fresh_scope_all
import navic.composeapp.generated.resources.fresh_scope_yours
import navic.composeapp.generated.resources.action_retry
import navic.composeapp.generated.resources.fresh_sort_artist
import navic.composeapp.generated.resources.fresh_sort_date
import navic.composeapp.generated.resources.fresh_window_days
import navic.composeapp.generated.resources.fresh_truncated
import navic.composeapp.generated.resources.title_fresh
import navic.composeapp.generated.resources.title_type_album
import navic.composeapp.generated.resources.title_type_ep
import navic.composeapp.generated.resources.title_type_other
import navic.composeapp.generated.resources.title_type_single
import navic.composeapp.generated.resources.option_all
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.LocalBottomBarScrollManager
import paige.navic.LocalNavStack
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbFreshRelease
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.models.settings.BottomBarVisibilityMode
import paige.navic.ui.components.common.ContentUnavailable
import paige.navic.ui.components.common.RemoteCoverArt
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.components.layouts.PullToRefreshBox
import paige.navic.ui.components.layouts.RootBottomBar
import paige.navic.ui.components.layouts.RootTopBar
import androidx.navigation3.runtime.NavKey
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Album
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.fresh.viewmodels.FreshScope
import paige.navic.ui.screens.fresh.viewmodels.FreshSort
import paige.navic.ui.screens.fresh.viewmodels.FreshType
import paige.navic.ui.screens.fresh.viewmodels.FreshViewModel

private val WINDOWS = listOf(7, 30, 90)

/**
 * New releases from ListenBrainz, whether or not the library has the artist.
 *
 * Every row is explorable: tapping one opens the album — the real Navidrome album
 * when the library holds it, else the external album page with the tracklist and
 * the source picker. Nothing here dead-ends on a page that doesn't list the album
 * you arrived from, which is what carrying the release-group through the
 * navigation (and lb-bot's single-release index refresh behind it) is for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreshScreen(nested: Boolean = false) {
	val viewModel = koinViewModel<FreshViewModel>()
	val preferenceManager = koinInject<PreferenceManager>()
	val backStack = LocalNavStack.current
	val state by viewModel.state.collectAsStateWithLifecycle()
	val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

	Scaffold(
		topBar = {
			val title = @Composable { Text(stringResource(Res.string.title_fresh)) }
			if (!nested) RootTopBar(title, scrollBehavior) else NestedTopBar(title)
		},
		bottomBar = {
			val scrollManager = LocalBottomBarScrollManager.current
			if (!nested ||
				preferenceManager.bottomBarVisibilityMode == BottomBarVisibilityMode.AllScreens
			) {
				RootBottomBar(scrolled = scrollManager.isTriggered)
			}
		}
	) { innerPadding ->
		PullToRefreshBox(
			modifier = Modifier
				.padding(top = innerPadding.calculateTopPadding()),
			finished = !state.loading,
			onRefresh = { viewModel.load() },
			key = state.all
		) {
			LazyVerticalGrid(
				columns = GridCells.Fixed(2),
				contentPadding = androidx.compose.foundation.layout.PaddingValues(
					start = 12.dp,
					end = 12.dp,
					bottom = innerPadding.calculateBottomPadding() + 24.dp
				),
				horizontalArrangement = Arrangement.spacedBy(12.dp),
				verticalArrangement = Arrangement.spacedBy(12.dp)
			) {
				item(span = { GridItemSpan(maxLineSpan) }) {
					FreshFilters(
						state = state,
						onScope = { viewModel.setScope(it) },
						onDays = { viewModel.setDays(it) },
						onSort = { viewModel.setSort(it) },
						onType = { viewModel.setType(it) }
					)
				}

				if (state.groups.isEmpty() && !state.loading) {
					item(span = { GridItemSpan(maxLineSpan) }) {
						Column(horizontalAlignment = Alignment.CenterHorizontally) {
							ContentUnavailable(
								icon = Icons.Outlined.Album,
								label = stringResource(
									when {
										state.failed -> Res.string.fresh_failed
										state.type != FreshType.ALL -> Res.string.fresh_empty_type
										state.scope == FreshScope.YOURS -> Res.string.fresh_empty_yours
										else -> Res.string.fresh_empty_all
									}
								)
							)
							// The feed is read once, from the screen's first frame.
							// Nothing retries on its own — a feed that updates hourly
							// does not deserve a poll — so this button is the only
							// thing between one unlucky frame (no network yet, a busy
							// hub) and a tab that stays empty for the session.
							if (state.failed) {
								Button(
									onClick = { viewModel.load() },
									modifier = Modifier.padding(top = 12.dp)
								) { Text(stringResource(Res.string.action_retry)) }
							}
						}
					}
					return@LazyVerticalGrid
				}

				state.groups.forEach { group ->
					group.bucket?.let { bucket ->
						item(span = { GridItemSpan(maxLineSpan) }, key = "h$bucket") {
							BucketHeader(bucket, group.items.size)
						}
					}
					items(
						group.items,
						key = { it.releaseGroupMbid.ifBlank { it.releaseMbid + it.releaseName } }
					) { release ->
						FreshTile(
							release = release,
							onClick = { open(backStack, release) },
							onArtistClick = openArtist(backStack, release)
						)
					}
				}

				// Said plainly rather than left as a silent shortfall: the feed is
				// capped before it crosses the hub, which cannot carry the whole
				// site-wide window. Every artist in the library survives that cut,
				// so this only ever means "there is more by artists you don't have".
				if (state.truncated) {
					item(span = { GridItemSpan(maxLineSpan) }) {
						Text(
							stringResource(
								Res.string.fresh_truncated,
								state.all.size, state.total
							),
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
						)
					}
				}
			}
		}
	}
}

/**
 * Where a tapped row goes.
 *
 * An owned release goes to the real album page. Everything else goes to the
 * external album page keyed on the release-group — including a release by an
 * artist the library *does* have, because owning the artist says nothing about
 * owning this album, and that is the whole set this screen exists for.
 */
private fun open(backStack: MutableList<NavKey>, release: LbFreshRelease) {
	// An album the library holds opens the library album. This used to send every
	// row to the external page regardless, relying on that page's redirect — which
	// reads `navidromeAlbumIds` off the index, and an album lb-bot filled itself
	// has none, so a tile badged "in library" opened a *download* page.
	if (release.releaseAlbumId.isNotBlank()) {
		backStack.add(Screen.CollectionDetail(release.releaseAlbumId, ""))
		return
	}
	val rgid = release.releaseGroupMbid
	if (rgid.isBlank()) return
	backStack.add(
		Screen.ExternalAlbum(
			rgid = rgid,
			artistMbid = release.artistMbids.firstOrNull().orEmpty(),
			artistName = release.artist,
			title = release.releaseName,
			artistId = if (release.artistOwned) release.artistId else ""
		)
	)
}

/**
 * Where a tapped artist name goes, or null for no tap target at all.
 *
 * Owned → the real artist page. Unowned but MusicBrainz-identified → the external
 * one, which renders their discography from lb-bot. Neither → nothing, never a
 * destination that cannot load. The same three-way rule Feishin's tile uses.
 */
private fun openArtist(backStack: MutableList<NavKey>, release: LbFreshRelease): (() -> Unit)? {
	if (release.artistOwned && release.artistId.isNotBlank()) {
		return { backStack.add(Screen.ArtistDetail(release.artistId)) }
	}
	val mbid = release.artistMbids.firstOrNull().orEmpty()
	if (mbid.isNotBlank()) {
		return { backStack.add(Screen.ExternalArtist(mbid, release.artist)) }
	}
	return null
}

@Composable
private fun FreshFilters(
	state: paige.navic.ui.screens.fresh.viewmodels.FreshUi,
	onScope: (FreshScope) -> Unit,
	onDays: (Int) -> Unit,
	onSort: (FreshSort) -> Unit,
	onType: (FreshType) -> Unit
) {
	Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
		Row(
			Modifier.horizontalScroll(rememberScrollState()),
			horizontalArrangement = Arrangement.spacedBy(8.dp)
		) {
			FilterChip(
				selected = state.scope == FreshScope.YOURS,
				onClick = { onScope(FreshScope.YOURS) },
				label = {
					Text(
						stringResource(Res.string.fresh_scope_yours) +
							" (${state.ownedArtistCount})"
					)
				}
			)
			FilterChip(
				selected = state.scope == FreshScope.ALL,
				onClick = { onScope(FreshScope.ALL) },
				label = { Text(stringResource(Res.string.fresh_scope_all) + " (${state.all.size})") }
			)
			WINDOWS.forEach { days ->
				FilterChip(
					selected = state.days == days,
					onClick = { onDays(days) },
					label = { Text(stringResource(Res.string.fresh_window_days, days)) }
				)
			}
			FilterChip(
				selected = state.sort == FreshSort.DATE,
				onClick = { onSort(FreshSort.DATE) },
				label = { Text(stringResource(Res.string.fresh_sort_date)) }
			)
			FilterChip(
				selected = state.sort == FreshSort.ARTIST,
				onClick = { onSort(FreshSort.ARTIST) },
				label = { Text(stringResource(Res.string.fresh_sort_artist)) }
			)
		}
		Row(
			Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
			horizontalArrangement = Arrangement.spacedBy(8.dp)
		) {
			FreshType.entries.forEach { type ->
				FilterChip(
					selected = state.type == type,
					onClick = { onType(type) },
					label = {
						Text(typeLabel(type) + " (${state.typeCounts[type] ?: 0})")
					}
				)
			}
		}
	}
}

@Composable
private fun typeLabel(type: FreshType): String = stringResource(
	when (type) {
		FreshType.ALL -> Res.string.option_all
		FreshType.ALBUM -> Res.string.title_type_album
		FreshType.EP -> Res.string.title_type_ep
		FreshType.SINGLE -> Res.string.title_type_single
		FreshType.OTHER -> Res.string.title_type_other
	}
)

@Composable
private fun BucketHeader(bucket: Int, count: Int) {
	Row(
		Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(10.dp)
	) {
		Text(
			stringResource(
				when (bucket) {
					-1 -> Res.string.fresh_bucket_upcoming
					0 -> Res.string.fresh_bucket_this_week
					1 -> Res.string.fresh_bucket_last_week
					2 -> Res.string.fresh_bucket_two_weeks
					3 -> Res.string.fresh_bucket_three_weeks
					else -> Res.string.fresh_bucket_earlier
				}
			),
			style = MaterialTheme.typography.labelLarge,
			color = MaterialTheme.colorScheme.onSurfaceVariant
		)
		HorizontalDivider(Modifier.weight(1f))
		Text(
			count.toString(),
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant
		)
	}
}

@Composable
private fun FreshTile(
	release: LbFreshRelease,
	onClick: () -> Unit,
	onArtistClick: (() -> Unit)? = null
) {
	// Straight from the Cover Art Archive. A release nobody owns has no Navidrome
	// art, and plenty of release-groups have no front cover at all — RemoteCoverArt
	// falls back rather than showing a broken image.
	val cover = release.coverUrl.ifBlank { LbBotManager.caaCoverUrl(release.releaseGroupMbid) }
	val upcoming = FreshViewModel.daysSince(release.releaseDate)?.let { it < 0 } == true

	Column(Modifier.fillMaxWidth()) {
		RemoteCoverArt(
			url = cover,
			contentDescription = release.releaseName,
			onClick = onClick,
			modifier = Modifier.fillMaxWidth()
		)
		Text(
			release.releaseName,
			style = MaterialTheme.typography.bodyMedium,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.padding(top = 6.dp)
		)
		// The artist name is a tap target wherever there is somewhere for it to
		// go — Feishin's tile has always linked it, and this one had no artist
		// affordance at all, so reaching an artist from Fresh meant opening an
		// album first.
		Text(
			release.artist,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = if (onArtistClick != null) {
				Modifier.clickable(onClick = onArtistClick)
			} else {
				Modifier
			}
		)
		// Only the exact release being on disk earns "in library" — an owned artist
		// with a brand-new album is still a download, and saying otherwise here
		// would mislabel the majority of the rows this page exists to show.
		val badge = when {
			release.releaseOwned -> stringResource(Res.string.fresh_chip_in_library)
			upcoming -> stringResource(Res.string.fresh_chip_upcoming)
			else -> release.releaseDate
		}
		if (badge.isNotBlank()) {
			Text(
				badge,
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1
			)
		}
	}
}
