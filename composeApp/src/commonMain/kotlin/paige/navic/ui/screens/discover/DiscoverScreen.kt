package paige.navic.ui.screens.discover

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.style.TextOverflow
import navic.composeapp.generated.resources.discover_from_library
import navic.composeapp.generated.resources.discover_intro
import navic.composeapp.generated.resources.discover_reason_chart
import navic.composeapp.generated.resources.discover_reason_fresh
import navic.composeapp.generated.resources.discover_reason_picked
import navic.composeapp.generated.resources.discover_reason_similar
import navic.composeapp.generated.resources.label_not_in_library_short
import paige.navic.ui.components.common.CoverArt
import paige.navic.ui.components.common.GroupHeader
import paige.navic.ui.screens.search.components.SearchPill
import paige.navic.ui.util.COVER_FADE_MS
import paige.navic.ui.util.arrive
import paige.navic.ui.util.rememberArrivalGate
import paige.navic.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import paige.navic.di.LocalPlatformContext
import paige.navic.di.isLandscape
import paige.navic.icons.filled.Headphones
import paige.navic.icons.outlined.ChevronForward
import paige.navic.icons.outlined.Explore
import paige.navic.shared.MediaPlayerViewModel
import paige.navic.ui.components.common.ContentUnavailable
import paige.navic.ui.components.common.PageChips
import paige.navic.ui.components.common.SegmentedListItem
import paige.navic.ui.components.common.SegmentedListItemDefaults
import paige.navic.ui.components.sheets.CollectionSheet
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import paige.navic.ui.navigation.PersistentViewModelStoreOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.mood_mix_name
import navic.composeapp.generated.resources.because_discover_fresh
import navic.composeapp.generated.resources.because_listening
import navic.composeapp.generated.resources.title_statistics
import navic.composeapp.generated.resources.because_discover_charts
import navic.composeapp.generated.resources.browse_unresolved
import navic.composeapp.generated.resources.because_discover_editorial
import navic.composeapp.generated.resources.because_discover_mixes
import navic.composeapp.generated.resources.because_discover_listenbrainz
import navic.composeapp.generated.resources.because_discover_mood
import navic.composeapp.generated.resources.mood_prompt_1
import navic.composeapp.generated.resources.mood_prompt_2
import navic.composeapp.generated.resources.mood_prompt_3
import navic.composeapp.generated.resources.mood_prompt_4
import navic.composeapp.generated.resources.mood_prompt_5
import navic.composeapp.generated.resources.mood_prompt_6
import navic.composeapp.generated.resources.because_discover_rediscovery
import navic.composeapp.generated.resources.because_discover_similar_artists
import navic.composeapp.generated.resources.because_discover_similar_artists_generic
import navic.composeapp.generated.resources.info_discover_empty
import navic.composeapp.generated.resources.label_in_library
import navic.composeapp.generated.resources.label_not_in_library
import navic.composeapp.generated.resources.title_discover
import navic.composeapp.generated.resources.title_discover_fresh
import navic.composeapp.generated.resources.title_discover_charts
import navic.composeapp.generated.resources.title_discover_editorial
import navic.composeapp.generated.resources.title_discover_mixes
import navic.composeapp.generated.resources.mix_unknown_kind
import navic.composeapp.generated.resources.title_discover_listenbrainz
import navic.composeapp.generated.resources.title_discover_mood
import navic.composeapp.generated.resources.title_discover_rediscovery
import navic.composeapp.generated.resources.title_discover_similar_artists
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.di.LocalBottomBarScrollManager
import paige.navic.di.LocalNavStack
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbBrowseAlbum
import paige.navic.domain.manager.RadioManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.models.Mix
import paige.navic.domain.models.MixKind
import paige.navic.domain.models.DomainSong
import paige.navic.domain.models.settings.BottomBarVisibilityMode
import paige.navic.ui.components.common.AcquireButton
import paige.navic.ui.components.common.RemoteCoverArt
import paige.navic.ui.components.sheets.MoodSearchSheet
import paige.navic.domain.manager.RediscoveryPlaylists
import paige.navic.ui.components.layouts.ArtCarouselItem
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.components.layouts.PullToRefreshBox
import paige.navic.ui.components.layouts.RootBottomBar
import paige.navic.ui.components.layouts.RootTopBar
import paige.navic.ui.components.layouts.horizontalSection
import paige.navic.ui.core.UiState
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.discover.viewmodels.BrowseTarget
import paige.navic.ui.screens.discover.viewmodels.DiscoverViewModel
import paige.navic.ui.screens.mixes.MixArtwork
import paige.navic.ui.screens.mixes.mixKindLabel

/**
 * Discover: one place to go, instead of six places to know about.
 *
 * Before this, the discovery features were all here and none of them was
 * somewhere you went — Fresh was a tab, "fans also like" did not exist, the
 * rediscovery set sat unlabelled in the playlist list, and mood search was a
 * sheet at the bottom of Search. Each had its own empty state, so lb-bot down,
 * AudioMuse cold and Navidrome fine all looked different.
 *
 * The rows come from [DISCOVER_ROWS], whose ids are duplicated in Feishin on
 * purpose; see that file. Every row is a [horizontalSection] carrying a reason
 * line, and `horizontalSection` hides itself when its data is empty — so a row
 * with nothing to say renders nothing, rather than a heading over a gap.
 *
 * **Theming:** this is a root tab, so `App.kt` wraps it in `Washed`. It must not
 * re-enter `NavicTheme` and must never extract per-row cover colours — one
 * shared ambient, seeded from the now-playing cover like every other browsing
 * surface. Mixed rows with per-tile palettes read as noise, which is the same
 * rule the queue follows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiscoverScreen(nested: Boolean = false) {
	// A root tab keeps its ViewModel across tab switches, which clear the back stack — the
	// lifetime it had before the ViewModel-store decorator was restored (CLAUDE.md §2 rule 14).
	// Its `init` loads over the network, so without this every visit to the tab re-fetched.
	// Nested (a home-page button, Discover's "See all") it dies with its entry, like
	// upstream's list screens.
	val viewModel = koinViewModel<DiscoverViewModel>(
		viewModelStoreOwner = if (nested) {
			LocalViewModelStoreOwner.current!!
		} else {
			koinInject<PersistentViewModelStoreOwner>()
		}
	)
	val preferenceManager = koinInject<PreferenceManager>()
	val platformContext = LocalPlatformContext.current
	val radioManager = koinInject<RadioManager>()
	val backStack = LocalNavStack.current
	val state by viewModel.state.collectAsStateWithLifecycle()
	// The listening history (I-006; Stats' since I-039) is a link here, not a row of the
	// catalogue: it has no tiles of its own to show, and DISCOVER_ROWS is mirrored in Feishin,
	// which reaches it from its sidebar. Offered only where the hub advertises the listening routes.
	val lbBot = koinInject<LbBotManager>()
	val listeningAdvertised by lbBot.listeningSupported.collectAsStateWithLifecycle()
	val showListening = listeningAdvertised && lbBot.isConfigured
	val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
	// Every visit, not only the ViewModel's first: a root tab keeps it for the process. Room
	// reads, plus a request only for a row whose cached answer has gone stale.
	LaunchedEffect(Unit) { viewModel.revalidate() }
	val scope = rememberCoroutineScope()
	val snackbarHostState = remember { SnackbarHostState() }

	// `stringResource` is composable and a LazyGridScope builder lambda is not, so
	// every reason line is resolved up here rather than at the row that uses it.
	val becauseListenBrainz = stringResource(Res.string.because_discover_listenbrainz)
	val becauseRediscovery = stringResource(Res.string.because_discover_rediscovery)
	val becauseMood = stringResource(Res.string.because_discover_mood)
	val becauseMixes = stringResource(Res.string.because_discover_mixes)
	val titleMood = stringResource(Res.string.title_discover_mood)
	val titleListening = stringResource(Res.string.title_statistics)
	val becauseListening = stringResource(Res.string.because_listening)
	// The six prompts are the row's whole point: it is not a list of results but a
	// statement of what the search can be asked. Mirrors Feishin's `mood-row.tsx`
	// verbatim — same six, so the two clients teach the feature the same way.
	val moodPrompts = listOf(
		stringResource(Res.string.mood_prompt_1),
		stringResource(Res.string.mood_prompt_2),
		stringResource(Res.string.mood_prompt_3),
		stringResource(Res.string.mood_prompt_4),
		stringResource(Res.string.mood_prompt_5),
		stringResource(Res.string.mood_prompt_6)
	)
	val browseUnresolved = stringResource(Res.string.browse_unresolved)
	val unknownKind = stringResource(Res.string.mix_unknown_kind)

	// A browse tile may carry no release-group id at all — lb-bot resolves Deezer
	// rows against the local index by name and leaves it blank when that index has
	// never seen the record, deferring the one MusicBrainz search to the tap. Hoisted
	// out of the grid because a LazyGridScope builder lambda is not composable.
	val openBrowseAlbum: (LbBrowseAlbum) -> Unit = { album ->
		scope.launch {
			// The whole decision lives in the viewmodel, including "is this
			// actually in my library" — a question this screen used to answer from
			// lb-bot's badge alone, which is blank for any artist lb-bot has not
			// scanned however much of them the user owns.
			when (val target = viewModel.resolveBrowseAlbum(album)) {
				is BrowseTarget.Library ->
					backStack.add(Screen.CollectionDetail(target.albumId, "discover"))

				is BrowseTarget.External -> backStack.add(
					Screen.ExternalAlbum(
						rgid = target.rgid,
						artistName = album.artist,
						title = album.title
					)
				)

				// Declines rather than opening a page keyed on a blank id, which
				// loads forever — or, worse, one keyed on whatever MusicBrainz
				// ranked first, which loads a confidently wrong album. Says so,
				// because a tile that silently ignores a tap is indistinguishable
				// from a broken one.
				null -> snackbarHostState.showSnackbar(browseUnresolved)
			}
		}
	}

	val stack = remember(state) { discoverStack(state) }
	val gate = rememberArrivalGate()
	val openStackEntry: (DiscoverTarget) -> Unit = { target ->
		when (target) {
			is DiscoverTarget.Release -> {
				val release = target.release
				backStack.add(
					Screen.ExternalAlbum(
						rgid = release.releaseGroupMbid,
						artistMbid = release.artistMbids.firstOrNull() ?: "",
						artistName = release.artist,
						title = release.releaseName,
						artistId = if (release.artistOwned) release.artistId else ""
					)
				)
			}
			is DiscoverTarget.Artist -> backStack.add(Screen.ExternalArtist(target.mbid, target.name))
			is DiscoverTarget.Browse -> openBrowseAlbum(target.album)
		}
	}

	// A mix opens its page (P5 round 2), which builds it and plays from there. A recipe from a
	// newer client is refused rather than approximated: regenerating the wrong kind would play
	// something plausible and wrong, which is harder to notice than nothing happening.
	val playMix: (Mix) -> Unit = { mix ->
		if (MixKind.fromWire(mix.kind) == null) scope.launch { snackbarHostState.showSnackbar(unknownKind) }
		else backStack.add(Screen.MixDetail(mix.id))
	}
	// A chip runs the search here rather than navigating to Search and making the
	// user retype the prompt — the sheet is the same one `SearchScreen` opens, and
	// `fetchMoodSearchSongs` is the same call, so nothing about the search itself is
	// duplicated. Hoisted because a LazyGridScope builder lambda is not composable.
	var showMoodSheet by remember { mutableStateOf(false) }
	var moodQuery by remember { mutableStateOf("") }
	var moodLoading by remember { mutableStateOf(false) }
	var moodSongs by remember { mutableStateOf<List<DomainSong>>(emptyList()) }
	val openMood: (String) -> Unit = { prompt ->
		moodQuery = prompt
		moodSongs = emptyList()
		moodLoading = true
		showMoodSheet = true
		scope.launch {
			moodSongs = radioManager.fetchMoodSearchSongs(prompt)
			moodLoading = false
		}
	}

	val emptyText = stringResource(Res.string.info_discover_empty)

	Scaffold(
		topBar = {
			val title = @Composable { Text(stringResource(Res.string.title_discover)) }
			if (!nested) RootTopBar(title, scrollBehavior) else NestedTopBar(title)
		},
		snackbarHost = { SnackbarHost(snackbarHostState) },
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
			modifier = Modifier.padding(top = innerPadding.calculateTopPadding()),
			finished = !state.loading,
			onRefresh = { viewModel.refresh() },
			// `loading` in the key: a refresh whose rows all revalidate to what is already on
			// screen changes nothing else, and the indicator would never be told to stop.
			key = state.supported to state.loading
		) {
			LazyVerticalGrid(
				columns = GridCells.Fixed(2),
				contentPadding = PaddingValues(
					bottom = innerPadding.calculateBottomPadding() + 24.dp
				),
				verticalArrangement = Arrangement.spacedBy(4.dp)
			) {
				// Every row hides itself when it has nothing to say, so "no rows
				// at all" is a real state and needs a sentence rather than a
				// blank page. It is what a server with no lb-bot, no AudioMuse
				// and no rediscovery set looks like — a configuration answer,
				// not a fault.
				//
				// NOTE this list is by hand and the compiler will not flag an
				// omission: a new row left out of it prints the "nothing to
				// discover" paragraph directly above its own populated content.
				val anyContent = stack.isNotEmpty() || state.mixes.isNotEmpty() ||
					state.fresh.isNotEmpty() ||
					state.similarArtists.isNotEmpty() ||
					state.charts.isNotEmpty() ||
					state.editorial.isNotEmpty() ||
					state.listenBrainz.isNotEmpty() ||
					state.rediscovery.isNotEmpty() ||
					DiscoverRowId.MOOD in state.supported ||
					showListening
				if (!state.loading && !anyContent) {
					item(span = { GridItemSpan(maxLineSpan) }) {
						ContentUnavailable(
							modifier = Modifier
								.fillMaxWidth()
								.padding(horizontal = 16.dp, vertical = 64.dp),
							icon = Icons.Outlined.Explore,
							label = emptyText
						)
					}
				}

				if (showListening) {
					item(span = { GridItemSpan(maxLineSpan) }) {
						SegmentedListItem(
							modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
							onClick = { backStack.add(Screen.Statistics(nested = true)) },
							shapes = SegmentedListItemDefaults.segmentedShapes(index = 0, count = 1),
							leadingContent = { Icon(Icons.Filled.Headphones, contentDescription = null) },
							trailingContent = {
								Icon(Icons.Outlined.ChevronForward, contentDescription = null)
							},
							supportingContent = { Text(becauseListening) }
						) {
							Text(titleListening)
						}
					}
				}

				// P3: music the library does not hold, each row with the reason it is here.
				if (stack.isNotEmpty()) {
					item(span = { GridItemSpan(maxLineSpan) }, key = "stack-intro") {
						Text(
							stringResource(Res.string.discover_intro),
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)
						)
					}
					stack.forEachIndexed { index, entry ->
						item(span = { GridItemSpan(maxLineSpan) }, key = entry.key) {
							DiscoverStackRow(
								entry = entry,
								index = index,
								count = stack.size,
								onClick = { openStackEntry(entry.target) },
								modifier = Modifier.animateItem().arrive(gate, entry.key, index)
							)
						}
					}
				}
				val libraryRowsShown = DISCOVER_ROWS.any { row ->
					// A carousel still loading shimmers, so its heading is already due.
					row.id in state.supported && when (row.id) {
						DiscoverRowId.MIXES -> state.mixes.isNotEmpty() || state.loading
						DiscoverRowId.LISTENBRAINZ -> state.listenBrainz.isNotEmpty() || state.loading
						DiscoverRowId.REDISCOVERY -> state.rediscovery.isNotEmpty() || state.loading
						DiscoverRowId.MOOD -> true
						else -> false
					}
				}
				if (libraryRowsShown) {
					item(span = { GridItemSpan(maxLineSpan) }, key = "from-library") {
						GroupHeader(
							title = stringResource(Res.string.discover_from_library),
							modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
						)
					}
				}

				DISCOVER_ROWS.forEach { row ->
					if (row.id !in state.supported) return@forEach
					when (row.id) {
						DiscoverRowId.MIXES -> horizontalSection(
							seeAll = true,
							title = Res.string.title_discover_mixes,
							destination = Screen.MixList(nested = true),
							state = rowState(state.mixes, state.loading),
							key = { it.id },
							because = becauseMixes
						) { mix ->
							// Tapping REGENERATES rather than restoring: a mix stores
							// a recipe, and the second play being a different
							// tracklist is the entire distinction from a saved queue.
							DiscoverMixTile(mix = mix, onClick = { playMix(mix) })
						}

						// P3: Fresh, similar artists, charts and editors' picks are the stack above,
						// each row with its reason; the catalogue's ids do not change (Feishin's twin).
						DiscoverRowId.FRESH, DiscoverRowId.SIMILAR_ARTISTS,
						DiscoverRowId.CHARTS, DiscoverRowId.EDITORIAL -> Unit

						DiscoverRowId.LISTENBRAINZ -> horizontalSection(
							seeAll = false,
							title = Res.string.title_discover_listenbrainz,
							destination = null,
							state = rowState(state.listenBrainz, state.loading),
							key = { it.playlistId },
							because = becauseListenBrainz
						) { playlist ->
							ArtCarouselItem(
								coverArtId = playlist.coverArtId,
								// "Daily Jams", not "ListenBrainz Daily Jams":
								// the row header already says where they come
								// from, and repeating it costs the tile its title.
								title = (playlist.name ?: "")
									.removePrefix(LISTENBRAINZ_PLAYLIST_PREFIX),
								subtitle = playlist.comment.orEmpty(),
								contentDescription = null,
								onSelect = { viewModel.openPlaylistSheet(playlist.playlistId) },
								onClick = {
									backStack.add(
										Screen.CollectionDetail(playlist.playlistId, "discover")
									)
								}
							)
						}

						DiscoverRowId.REDISCOVERY -> horizontalSection(
							seeAll = false,
							title = Res.string.title_discover_rediscovery,
							destination = null,
							state = rowState(state.rediscovery, state.loading),
							key = { it.playlistId },
							because = becauseRediscovery
						) { playlist ->
							ArtCarouselItem(
								coverArtId = playlist.coverArtId,
								title = (playlist.name ?: "")
									.removePrefix(RediscoveryPlaylists.PREFIX),
								// The definition's own one-line description,
								// written into the Navidrome playlist comment
								// when it was created and never surfaced
								// anywhere until now. A ready-made reason line,
								// per playlist.
								subtitle = playlist.comment.orEmpty(),
								contentDescription = null,
								onSelect = { viewModel.openPlaylistSheet(playlist.playlistId) },
								onClick = {
									backStack.add(
										Screen.CollectionDetail(playlist.playlistId, "discover")
									)
								}
							)
						}

						DiscoverRowId.MOOD -> item(span = { GridItemSpan(maxLineSpan) }) {
							// Not a list of results but an entry point: the CLAP
							// search is reachable only from the bottom of the
							// Search screen today, which means only people who
							// already know it exists ever find it.
							Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
								Text(
									titleMood,
									style = MaterialTheme.typography.titleMediumEmphasized,
									fontWeight = FontWeight(600),
									modifier = Modifier
										.heightIn(min = 32.dp)
										.padding(top = 12.dp)
										.semantics { heading() }
								)
								Text(
									becauseMood,
									style = MaterialTheme.typography.bodyMedium,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
									modifier = Modifier
										.padding(top = 2.dp)
										.clickable { backStack.add(Screen.Search()) }
								)
								// The one row here that is not a carousel: there is
								// nothing to page through and nothing to illustrate, so
								// the prompts wrap instead.
								FlowRow(
									modifier = Modifier.padding(top = 8.dp),
									horizontalArrangement = Arrangement.spacedBy(8.dp)
								) {
									moodPrompts.forEach { prompt ->
										SuggestionChip(
											onClick = { openMood(prompt) },
											label = { Text(prompt) }
										)
									}
								}
							}
						}
					}
				}
			}
		}
	}

	// A playlist tile's long press: the playlist list's own sheet, from Room.
	val sheetPlaylist by viewModel.sheetPlaylist.collectAsStateWithLifecycle()
	val player = koinInject<MediaPlayerViewModel>()
	sheetPlaylist?.let { playlist ->
		CollectionSheet(
			onDismissRequest = { viewModel.dismissPlaylistSheet() },
			collection = playlist,
			onPlayNext = { player.playNext(playlist) },
			onAddToQueue = { player.addToQueue(playlist) }
		)
	}

	// Same sheet SearchScreen opens, same play/enqueue actions — a chip is a second
	// door into one feature, not a second implementation of it.
	if (showMoodSheet) {
		// Saved to history as a search's results, under the query (I-040), not as "Mood Flow".
		val moodMixName = stringResource(Res.string.mood_mix_name, moodQuery)
		MoodSearchSheet(
			query = moodQuery,
			songs = moodSongs,
			loading = moodLoading,
			onPlay = { radioManager.playMoodMix(moodSongs, moodMixName) },
			onAddToQueue = { radioManager.enqueueMoodMix(moodSongs) },
			onDismissRequest = { showMoodSheet = false }
		)
	}
}

/**
 * A mix tile: a name, what the recipe is, and nothing that looks like a tracklist.
 *
 * Deliberately not showing a track count or a "last played" line. Both would read
 * as properties of a stored *result*, and the whole point is that a mix has no
 * tracklist until it is played. Upstream's carousel card, with the mix's face
 * (`MixArtwork`, which the Mixed for You list draws through too) as its cover.
 */
@Composable
private fun DiscoverMixTile(mix: Mix, onClick: () -> Unit) {
	ArtCarouselItem(
		coverArtId = null,
		title = mix.name,
		subtitle = mixKindLabel(mix),
		contentDescription = mix.name,
		cover = { source ->
			MixArtwork(mix = mix, modifier = Modifier.fillMaxWidth(), interactionSource = source)
		},
		onClick = onClick
	)
}

/**
 * One row of the stack (P3): the art, the title and artist, the reason in the body colour, and the
 * "Not in library" pill, which every row here is. A tap opens what the row is, never a download.
 */
@Composable
private fun DiscoverStackRow(
	entry: DiscoverEntry,
	index: Int,
	count: Int,
	onClick: () -> Unit,
	modifier: Modifier = Modifier
) {
	val preferenceManager = koinInject<PreferenceManager>()
	val reason = when (val r = entry.reason) {
		DiscoverReason.Fresh -> stringResource(Res.string.discover_reason_fresh)
		is DiscoverReason.Similar -> stringResource(Res.string.discover_reason_similar, r.seed)
		DiscoverReason.Chart -> stringResource(Res.string.discover_reason_chart)
		DiscoverReason.Picked -> stringResource(Res.string.discover_reason_picked)
	}
	SegmentedListItem(
		modifier = modifier.padding(horizontal = 16.dp, vertical = 1.5.dp),
		onClick = onClick,
		shapes = SegmentedListItemDefaults.segmentedShapes(index = index, count = count),
		leadingContent = {
			if (entry.isArtist) {
				CoverArt(
					coverArtId = entry.art,
					modifier = Modifier.size(50.dp),
					isArtist = true,
					shape = CircleShape,
					crossfadeMs = COVER_FADE_MS,
					placeholderName = entry.title
				)
			} else {
				RemoteCoverArt(
					url = entry.art,
					modifier = Modifier.size(50.dp),
					shape = preferenceManager.coverArtShape.decreasedShape,
					crossfadeMs = COVER_FADE_MS,
					placeholderName = entry.title
				)
			}
		},
		supportingContent = {
			Column {
				if (entry.subtitle.isNotBlank()) {
					Text(entry.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
				}
				Text(
					reason,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
			}
		},
		trailingContent = { SearchPill(stringResource(Res.string.label_not_in_library_short)) }
	) {
		Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
	}
}

/** A row's state: loading while the page loads and the row has nothing yet, so it shimmers. */
private fun <T> rowState(items: List<T>, loading: Boolean): UiState<List<T>> =
	if (items.isEmpty() && loading) UiState.Loading() else UiState.Success(items)
