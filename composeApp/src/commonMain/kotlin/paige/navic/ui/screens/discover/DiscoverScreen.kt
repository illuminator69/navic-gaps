package paige.navic.ui.screens.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.because_discover_fresh
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
import navic.composeapp.generated.resources.mix_regenerate_failed
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
import paige.navic.ui.components.common.CoverArt
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
	val viewModel = koinViewModel<DiscoverViewModel>()
	val preferenceManager = koinInject<PreferenceManager>()
	val radioManager = koinInject<RadioManager>()
	val backStack = LocalNavStack.current
	val state by viewModel.state.collectAsStateWithLifecycle()
	val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
	val scope = rememberCoroutineScope()
	val snackbarHostState = remember { SnackbarHostState() }

	// `stringResource` is composable and a LazyGridScope builder lambda is not, so
	// every reason line is resolved up here rather than at the row that uses it.
	val becauseFresh = stringResource(Res.string.because_discover_fresh)
	val becauseSimilar = if (state.similarSeed.isNotBlank()) {
		stringResource(Res.string.because_discover_similar_artists, state.similarSeed)
	} else {
		stringResource(Res.string.because_discover_similar_artists_generic)
	}
	val becauseListenBrainz = stringResource(Res.string.because_discover_listenbrainz)
	val becauseRediscovery = stringResource(Res.string.because_discover_rediscovery)
	val becauseMood = stringResource(Res.string.because_discover_mood)
	val becauseMixes = stringResource(Res.string.because_discover_mixes)
	val becauseCharts = stringResource(Res.string.because_discover_charts)
	val becauseEditorial = stringResource(Res.string.because_discover_editorial)
	val titleMood = stringResource(Res.string.title_discover_mood)
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
	val regenerateFailed = stringResource(Res.string.mix_regenerate_failed)
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

	// Playing a mix is the one action on this screen that can visibly do nothing —
	// the engine may answer no tracks, and the tap is otherwise silent. Hoisted out
	// of the grid because a LazyGridScope builder lambda is not composable.
	val playMix: (Mix) -> Unit = { mix ->
		scope.launch {
			if (MixKind.fromWire(mix.kind) == null) {
				// A recipe from a newer client. Refused rather than approximated:
				// regenerating the wrong kind would play something plausible and
				// wrong, which is harder to notice than nothing happening.
				snackbarHostState.showSnackbar(unknownKind)
			} else if (!radioManager.regenerate(mix)) {
				snackbarHostState.showSnackbar(regenerateFailed)
			}
		}
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
	val inLibrary = stringResource(Res.string.label_in_library)
	val notInLibrary = stringResource(Res.string.label_not_in_library)

	Scaffold(
		topBar = {
			val title = @Composable { Text(stringResource(Res.string.title_discover)) }
			if (!nested) RootTopBar(title, scrollBehavior) else NestedTopBar(title)
		},
		snackbarHost = { SnackbarHost(snackbarHostState) },
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
			modifier = Modifier.padding(top = innerPadding.calculateTopPadding()),
			finished = !state.loading,
			onRefresh = { viewModel.load() },
			key = state.supported
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
				val anyContent = state.mixes.isNotEmpty() ||
					state.fresh.isNotEmpty() ||
					state.similarArtists.isNotEmpty() ||
					state.charts.isNotEmpty() ||
					state.editorial.isNotEmpty() ||
					state.listenBrainz.isNotEmpty() ||
					state.rediscovery.isNotEmpty() ||
					DiscoverRowId.MOOD in state.supported
				if (!state.loading && !anyContent) {
					item(span = { GridItemSpan(maxLineSpan) }) {
						Text(
							emptyText,
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.padding(16.dp)
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
							state = UiState.Success(state.mixes),
							key = { it.id },
							because = becauseMixes
						) { mix ->
							// Tapping REGENERATES rather than restoring: a mix stores
							// a recipe, and the second play being a different
							// tracklist is the entire distinction from a saved queue.
							DiscoverMixTile(
								mix = mix,
								onClick = { playMix(mix) },
								modifier = Modifier.width(150.dp)
							)
						}

						DiscoverRowId.FRESH -> horizontalSection(
							seeAll = true,
							title = Res.string.title_discover_fresh,
							destination = Screen.Fresh(nested = true),
							state = UiState.Success(state.fresh),
							key = { it.releaseGroupMbid.ifBlank { it.releaseName + it.artist } },
							because = becauseFresh
						) { release ->
							DiscoverAlbumTile(
								title = release.releaseName,
								subtitle = release.artist,
								coverUrl = release.coverUrl
									.ifBlank { LbBotManager.caaCoverUrl(release.releaseGroupMbid) },
								owned = release.releaseOwned,
								modifier = Modifier.animateItem().width(150.dp),
								// One tap to fetch it, but never blind: the
								// control reviews lb-bot's ranked sources first
								// and opens the picker whenever anything is left
								// to decide.
								action = if (release.releaseOwned) null else {
									{
										AcquireButton(
											rgid = release.releaseGroupMbid,
											artist = release.artist,
											album = release.releaseName,
											onReview = {
												backStack.add(
													Screen.ExternalAlbum(
														rgid = release.releaseGroupMbid,
														artistMbid = release.artistMbids
															.firstOrNull() ?: "",
														artistName = release.artist,
														title = release.releaseName,
														artistId = if (release.artistOwned) {
															release.artistId
														} else ""
													)
												)
											}
										)
									}
								},
								onClick = {
									// An owned release opens the library album
									// directly: `releaseAlbumId` exists so a row
									// badged "in library" need not route through
									// the virtual page and wait for a redirect
									// that cannot fire for an album lb-bot filled
									// itself.
									val albumId = release.releaseAlbumId
									if (release.releaseOwned && albumId.isNotBlank()) {
										backStack.add(Screen.CollectionDetail(albumId, "discover"))
									} else {
										backStack.add(
											Screen.ExternalAlbum(
												rgid = release.releaseGroupMbid,
												artistMbid = release.artistMbids.firstOrNull() ?: "",
												artistName = release.artist,
												title = release.releaseName,
												artistId = if (release.artistOwned) {
													release.artistId
												} else ""
											)
										)
									}
								}
							)
						}

						DiscoverRowId.SIMILAR_ARTISTS -> horizontalSection(
							seeAll = false,
							title = Res.string.title_discover_similar_artists,
							destination = null,
							state = UiState.Success(
								// Someone with neither a library id nor an MBID
								// has nowhere to go; that is a name, not a row.
								state.similarArtists.filter {
									it.artistId.isNotBlank() || it.mbid.isNotBlank()
								}
							),
							key = { it.mbid.ifBlank { it.name } },
							because = becauseSimilar
						) { artist ->
							// The app's ordinary artist tile, so this row looks
							// like every other carousel rather than like a list
							// of bare names. An owned artist carries Navidrome's
							// picture; an unowned one has none to carry and gets
							// the same placeholder an untagged library artist
							// does.
							ArtCarouselItem(
								coverArtId = artist.coverArtId,
								isArtist = true,
								title = artist.name,
								subtitle = if (artist.owned) inLibrary else notInLibrary,
								contentDescription = null,
								onClick = {
									// Owned goes to the real artist page; unowned
									// to the virtual `mb:` one, which renders
									// their discography from lb-bot and is where
									// a fill starts.
									if (artist.artistId.isNotBlank()) {
										backStack.add(Screen.ArtistDetail(artist.artistId))
									} else {
										backStack.add(
											Screen.ExternalArtist(artist.mbid, artist.name)
										)
									}
								}
							)
						}

						// Both Deezer rows render identically and differ only in
						// what they mean, so one tile builder serves both. They are
						// the first rows here that are NOT scoped to the user's
						// library at all, which is why each carries an acquire
						// control on every unowned tile rather than on some.
						DiscoverRowId.CHARTS -> {
							// Above the row, and scoping BOTH Deezer rows: they are
							// two views of the same browse source, and letting them
							// sit on different genres would be two chart rows with
							// no way to tell which was which.
							//
							// Nothing here for country. Deezer's open API has no
							// country parameter at all — the chart is geolocated by
							// lb-bot's own egress address — so genre is the only
							// axis that exists to offer.
							if (state.genres.isNotEmpty()) {
								item(span = { GridItemSpan(maxLineSpan) }) {
									FlowRow(
										modifier = Modifier.padding(
											horizontal = 16.dp,
											vertical = 4.dp
										),
										horizontalArrangement = Arrangement.spacedBy(8.dp)
									) {
										state.genres.forEach { genre ->
											FilterChip(
												selected = state.genre == genre.id,
												onClick = { viewModel.setDeezerGenre(genre.id) },
												label = { Text(genre.name) }
											)
										}
									}
								}
							}
							horizontalSection(
								seeAll = false,
								title = Res.string.title_discover_charts,
								destination = null,
								state = UiState.Success(state.charts),
								key = { it.rgid.ifBlank { it.title + it.artist } },
								because = becauseCharts
							) { album ->
								BrowseAlbumTile(album, openBrowseAlbum)
							}
						}

						DiscoverRowId.EDITORIAL -> horizontalSection(
							seeAll = false,
							title = Res.string.title_discover_editorial,
							destination = null,
							state = UiState.Success(state.editorial),
							key = { it.rgid.ifBlank { it.title + it.artist } },
							because = becauseEditorial
						) { album ->
							BrowseAlbumTile(album, openBrowseAlbum)
						}

						DiscoverRowId.LISTENBRAINZ -> horizontalSection(
							seeAll = false,
							title = Res.string.title_discover_listenbrainz,
							destination = null,
							state = UiState.Success(state.listenBrainz),
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
							state = UiState.Success(state.rediscovery),
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
							Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
								Text(
									titleMood,
									style = MaterialTheme.typography.titleMediumEmphasized
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

	// Same sheet SearchScreen opens, same play/enqueue actions — a chip is a second
	// door into one feature, not a second implementation of it.
	if (showMoodSheet) {
		MoodSearchSheet(
			query = moodQuery,
			songs = moodSongs,
			loading = moodLoading,
			onPlay = { radioManager.playMoodMix(moodSongs) },
			onAddToQueue = { radioManager.enqueueMoodMix(moodSongs) },
			onDismissRequest = { showMoodSheet = false }
		)
	}
}

/**
 * A browse-row release, with the acquire control every unowned tile needs.
 *
 * Its own composable rather than a lambda at each call site because the two Deezer
 * rows would otherwise hold two copies of the acquire wiring, and a fix applied to
 * one of them is exactly the drift this file's header warns about. Takes the back
 * stack explicitly: a `LazyGridScope` item lambda is composable, but the row
 * builder that calls it is not, so `LocalNavStack` has to be read above the grid.
 */
@Composable
private fun BrowseAlbumTile(album: LbBrowseAlbum, onOpen: (LbBrowseAlbum) -> Unit) {
	DiscoverAlbumTile(
		title = album.title,
		subtitle = album.artist,
		// lb-bot already picks between the Archive's front and Deezer's own cover,
		// so this is right for a resolved and an unresolved row alike; the CAA
		// fallback is only for the case where it sent neither.
		coverUrl = album.coverUrl.ifBlank { LbBotManager.caaCoverUrl(album.rgid) },
		owned = album.releaseOwned,
		modifier = Modifier.width(150.dp),
		// No acquire control on a row with no release-group id: the whole
		// acquisition path is release-group-shaped, and the one search that could
		// mint one belongs on the tap, not on every tile in the row. Tapping such a
		// tile still works — it resolves first.
		action = if (album.releaseOwned || album.rgid.isBlank()) null else {
			{
				AcquireButton(
					rgid = album.rgid,
					artist = album.artist,
					album = album.title,
					onReview = { onOpen(album) }
				)
			}
		},
		onClick = { onOpen(album) }
	)
}

/**
 * A mix tile: a name, what the recipe is, and nothing that looks like a tracklist.
 *
 * Deliberately not showing a track count or a "last played" line. Both would read
 * as properties of a stored *result*, and the whole point is that a mix has no
 * tracklist until it is played.
 */
@Composable
private fun DiscoverMixTile(mix: Mix, onClick: () -> Unit, modifier: Modifier = Modifier) {
	Column(modifier.fillMaxWidth()) {
		// The seed's cover when one was stamped, a kind-glyph tile otherwise — see
		// `MixArtwork`, which both this and the Mixed for You row draw through so a
		// fix cannot reach one and miss the other. It carries its own click target,
		// so there is no wrapper here: two overlapping ones was a tile where the top
		// half and the bottom half rippled differently.
		MixArtwork(
			mix = mix,
			onClick = onClick,
			modifier = Modifier.fillMaxWidth()
		)
		Text(
			mix.name,
			style = MaterialTheme.typography.bodyMedium,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.padding(top = 6.dp)
		)
		Text(
			mixKindLabel(mix),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis
		)
	}
}

/** A cover tile. Unowned art is dimmed, matching the missing-album tiles on the
 *  artist page — the same visual grammar for the same fact. */
@Composable
private fun DiscoverAlbumTile(
	title: String,
	subtitle: String,
	coverUrl: String,
	owned: Boolean,
	modifier: Modifier = Modifier,
	onClick: () -> Unit,
	action: @Composable (() -> Unit)? = null
) {
	Column(modifier.fillMaxWidth()) {
		Box {
			RemoteCoverArt(
				url = coverUrl,
				contentDescription = title,
				onClick = onClick,
				modifier = Modifier.fillMaxWidth()
			)
			// Over the artwork rather than under the captions: a row of tiles is
			// mostly artwork, and a control below the text pushes every tile in
			// the row taller for the sake of the unowned ones.
			action?.let {
				Box(Modifier.align(Alignment.TopEnd)) { it() }
			}
		}
		Text(
			title,
			style = MaterialTheme.typography.bodyMedium,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.padding(top = 6.dp)
		)
		Text(
			subtitle,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis
		)
		if (!owned) {
			Text(
				stringResource(Res.string.label_not_in_library),
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1
			)
		}
	}
}
