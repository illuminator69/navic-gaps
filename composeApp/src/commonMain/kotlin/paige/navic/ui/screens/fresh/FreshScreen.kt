package paige.navic.ui.screens.fresh

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.nestedscroll.nestedScroll
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import navic.composeapp.generated.resources.title_fresh_window
import navic.composeapp.generated.resources.title_sort_by
import paige.navic.di.LocalPlatformContext
import paige.navic.di.isLandscape
import paige.navic.icons.outlined.Sparkle
import paige.navic.icons.outlined.Sort
import paige.navic.ui.components.common.GroupHeader
import paige.navic.ui.components.common.PageChips
import paige.navic.ui.components.layouts.ArtGrid
import paige.navic.ui.components.layouts.ArtGridItem
import paige.navic.ui.components.layouts.TopBarButton
import paige.navic.ui.components.layouts.artGridPlaceholder
import paige.navic.ui.components.sheets.SortSheet
import paige.navic.ui.screens.fresh.viewmodels.FreshUi
import paige.navic.ui.util.withoutTop
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import paige.navic.ui.navigation.PersistentViewModelStoreOwner
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import navic.composeapp.generated.resources.fresh_empty_played
import navic.composeapp.generated.resources.fresh_empty_type
import navic.composeapp.generated.resources.fresh_empty_yours
import navic.composeapp.generated.resources.fresh_failed
import navic.composeapp.generated.resources.fresh_scope_all
import navic.composeapp.generated.resources.fresh_scope_played
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
import paige.navic.di.LocalBottomBarScrollManager
import paige.navic.di.LocalNavStack
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
	// A root tab keeps its ViewModel across tab switches, which clear the back stack — the
	// lifetime it had before the ViewModel-store decorator was restored (CLAUDE.md §2 rule 14).
	// Its `init` loads over the network, so without this every visit to the tab re-fetched.
	// Nested (a home-page button, Discover's "See all") it dies with its entry, like
	// upstream's list screens.
	val viewModel = koinViewModel<FreshViewModel>(
		viewModelStoreOwner = if (nested) {
			LocalViewModelStoreOwner.current!!
		} else {
			koinInject<PersistentViewModelStoreOwner>()
		}
	)
	val preferenceManager = koinInject<PreferenceManager>()
	val platformContext = LocalPlatformContext.current
	val backStack = LocalNavStack.current
	val state by viewModel.state.collectAsStateWithLifecycle()
	val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
	// Every visit, not only the ViewModel's first: a root tab keeps it for the process. A Room
	// read, plus one request only when the cached feed has gone stale (FreshViewModel.revalidate).
	LaunchedEffect(Unit) { viewModel.revalidate() }

	// The order and the window are upstream's sort sheet, as every library list's are; the
	// scope and the type stay chips, being what the page is OF rather than how it is ordered.
	val actions: @Composable RowScope.() -> Unit = {
		FreshSortButton(
			nested = nested,
			state = state,
			onSort = { viewModel.setSort(it) },
			onDays = { viewModel.setDays(it) }
		)
	}

	Scaffold(
		topBar = {
			Column {
				val title = @Composable { Text(stringResource(Res.string.title_fresh)) }
				if (!nested) RootTopBar(title, scrollBehavior, actions)
				else NestedTopBar(title, actions = actions)
				FreshScopeChips(state = state, onScope = { viewModel.setScope(it) })
			}
		},
		bottomBar = {
			val scrollManager = LocalBottomBarScrollManager.current
			val preferVisible =
				preferenceManager.bottomBarVisibilityMode == BottomBarVisibilityMode.AllScreens
			if (!nested || (!platformContext.isLandscape() && preferVisible)) {
				RootBottomBar(scrolled = scrollManager.isTriggered)
			}
		}
	) { innerPadding ->
		PullToRefreshBox(
			modifier = Modifier
				.padding(top = innerPadding.calculateTopPadding()),
			finished = !state.loading,
			onRefresh = { viewModel.refresh() },
			// `loading` in the key: a refresh that revalidates to the very feed already on
			// screen leaves `all` equal, and the indicator would never be told to stop.
			key = state.all to state.loading
		) {
			// Upstream's grid: its columns follow the user's grid size on a phone and the cover
			// size beyond, so a tablet shows as many as the album list does rather than two.
			ArtGrid(
				modifier = if (!nested) {
					Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
				} else Modifier,
				contentPadding = innerPadding.withoutTop()
			) {
				item(span = { GridItemSpan(maxLineSpan) }, key = "types") {
					PageChips(
						options = FreshType.entries,
						selected = state.type,
						label = { type -> typeLabel(type) + " (${state.typeCounts[type] ?: 0})" },
						onSelect = { viewModel.setType(it) },
						contentPadding = PaddingValues(0.dp)
					)
				}

				if (state.groups.isEmpty() && state.loading) {
					artGridPlaceholder()
					return@ArtGrid
				}

				if (state.groups.isEmpty()) {
					item(span = { GridItemSpan(maxLineSpan) }) {
						Column(
							modifier = Modifier.padding(vertical = 48.dp),
							horizontalAlignment = Alignment.CenterHorizontally
						) {
							ContentUnavailable(
								modifier = Modifier.fillMaxWidth(),
								icon = Icons.Outlined.Sparkle,
								label = stringResource(
									when {
										state.failed -> Res.string.fresh_failed
										state.type != FreshType.ALL -> Res.string.fresh_empty_type
										state.scope == FreshScope.YOURS -> Res.string.fresh_empty_yours
										state.scope == FreshScope.PLAYED -> Res.string.fresh_empty_played
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
									onClick = { viewModel.refresh() },
									modifier = Modifier.padding(top = 12.dp)
								) { Text(stringResource(Res.string.action_retry)) }
							}
						}
					}
					return@ArtGrid
				}

				state.groups.forEach { group ->
					group.bucket?.let { bucket ->
						item(span = { GridItemSpan(maxLineSpan) }, key = "h$bucket") {
							GroupHeader(
								title = bucketLabel(bucket),
								count = group.items.size,
								modifier = Modifier.animateItem()
							)
						}
					}
					items(
						group.items,
						key = { it.releaseGroupMbid.ifBlank { it.releaseMbid + it.releaseName } }
					) { release ->
						FreshTile(
							release = release,
							onClick = { open(backStack, release) },
							onArtistClick = openArtist(backStack, release),
							modifier = Modifier.animateItem()
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

/**
 * Whose releases: your artists, everyone's, or the artists you play (only when some row has
 * plays: a feed whose rows all say 0 is a listens mirror that is off or still importing as
 * readily as "none of them released anything", and a chip reading "(0)" would claim the second).
 */
@Composable
private fun FreshScopeChips(state: FreshUi, onScope: (FreshScope) -> Unit) {
	val scopes = buildList {
		add(FreshScope.YOURS)
		add(FreshScope.ALL)
		if (state.playedOffered) add(FreshScope.PLAYED)
	}
	PageChips(
		options = scopes,
		selected = state.scope,
		label = { scope ->
			when (scope) {
				FreshScope.YOURS ->
					stringResource(Res.string.fresh_scope_yours) + " (${state.ownedArtistCount})"
				FreshScope.ALL -> stringResource(Res.string.fresh_scope_all) + " (${state.all.size})"
				FreshScope.PLAYED ->
					stringResource(Res.string.fresh_scope_played) + " (${state.playedCount})"
			}
		},
		onSelect = onScope,
		contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp)
	)
}

/**
 * Upstream's sort button and sheet: the order (newest first, or by artist) and the window.
 * "Artists you play" has its own order, most played first, so there the sheet offers the window
 * alone: two sort rows would be a control that does nothing.
 */
@Composable
private fun FreshSortButton(
	nested: Boolean,
	state: FreshUi,
	onSort: (FreshSort) -> Unit,
	onDays: (Int) -> Unit
) {
	var expanded by remember { mutableStateOf(false) }
	val icon = @Composable {
		Icon(Icons.Outlined.Sort, contentDescription = stringResource(Res.string.title_sort_by))
	}
	if (!nested) IconButton(onClick = { expanded = true }) { icon() }
	else TopBarButton(onClick = { expanded = true }) { icon() }
	if (expanded) {
		val entries = remember(state.scope) {
			if (state.scope == FreshScope.PLAYED) persistentListOf()
			else FreshSort.entries.toImmutableList()
		}
		SortSheet(
			entries = entries,
			label = {
				stringResource(
					when (it) {
						FreshSort.DATE -> Res.string.fresh_sort_date
						FreshSort.ARTIST -> Res.string.fresh_sort_artist
					}
				)
			},
			selectedSorting = state.sort,
			onSetSorting = onSort,
			extraContent = {
				Text(
					text = stringResource(Res.string.title_fresh_window),
					style = MaterialTheme.typography.titleMedium,
					modifier = Modifier.padding(top = 8.dp)
				)
				SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
					WINDOWS.forEachIndexed { index, days ->
						SegmentedButton(
							shape = SegmentedButtonDefaults.itemShape(index = index, count = WINDOWS.size),
							onClick = { onDays(days) },
							selected = state.days == days,
							label = { Text(stringResource(Res.string.fresh_window_days, days)) }
						)
					}
				}
			},
			onDismissRequest = { expanded = false }
		)
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
private fun bucketLabel(bucket: Int): String = stringResource(
	when (bucket) {
		-1 -> Res.string.fresh_bucket_upcoming
		0 -> Res.string.fresh_bucket_this_week
		1 -> Res.string.fresh_bucket_last_week
		2 -> Res.string.fresh_bucket_two_weeks
		3 -> Res.string.fresh_bucket_three_weeks
		else -> Res.string.fresh_bucket_earlier
	}
)

/**
 * One release, as upstream's grid card (`ArtGridItem`) with its cover from the Cover Art Archive:
 * a release nobody owns has no Navidrome art, and plenty of release-groups have no front cover at
 * all, which `RemoteCoverArt` falls back from rather than showing a broken image. The artist name
 * is its own link wherever there is somewhere for it to go, as Feishin's tile has always had it.
 */
@Composable
private fun FreshTile(
	release: LbFreshRelease,
	onClick: () -> Unit,
	onArtistClick: (() -> Unit)? = null,
	modifier: Modifier = Modifier
) {
	val cover = release.coverUrl.ifBlank { LbBotManager.caaCoverUrl(release.releaseGroupMbid) }
	val upcoming = FreshViewModel.daysSince(release.releaseDate)?.let { it < 0 } == true
	// Only the exact release being on disk earns "in library": an owned artist with a brand-new
	// album is still a download, and saying otherwise would mislabel most of this page.
	val caption = when {
		release.releaseOwned -> stringResource(Res.string.fresh_chip_in_library)
		upcoming -> stringResource(Res.string.fresh_chip_upcoming)
		else -> release.releaseDate
	}
	ArtGridItem(
		modifier = modifier,
		onClick = onClick,
		coverArtId = null,
		title = release.releaseName,
		subtitle = release.artist,
		id = release.releaseGroupMbid.ifBlank { release.releaseMbid },
		tab = "fresh",
		cover = { source ->
			RemoteCoverArt(
				url = cover,
				contentDescription = release.releaseName,
				modifier = Modifier.fillMaxWidth(),
				interactionSource = source
			)
		},
		onSubtitleClick = onArtistClick,
		caption = caption
	)
}
