package paige.navic.ui.screens.artist

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.launch
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_see_all
import navic.composeapp.generated.resources.count_albums
import navic.composeapp.generated.resources.info_bulk_download_warning
import navic.composeapp.generated.resources.notice_deleted_download
import navic.composeapp.generated.resources.notice_download_started
import navic.composeapp.generated.resources.option_sort_frequent
import navic.composeapp.generated.resources.title_albums
import navic.composeapp.generated.resources.title_appears_on
import navic.composeapp.generated.resources.title_bulk_download
import navic.composeapp.generated.resources.title_similar_artists
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import paige.navic.data.database.entities.DownloadStatus
import paige.navic.di.LocalBottomBarScrollManager
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalPlatformContext
import paige.navic.di.isLandscape
import paige.navic.domain.manager.DownloadManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.models.settings.BottomBarVisibilityMode
import paige.navic.domain.models.settings.ThemeMode
import paige.navic.domain.manager.SnackBarManager
import paige.navic.shared.MediaPlayerViewModel
import paige.navic.ui.components.common.CoverAmbientBackground
import paige.navic.ui.components.common.ErrorBox
import paige.navic.ui.components.common.SongRow
import paige.navic.ui.components.dialogs.BulkDownloadDialog
import paige.navic.ui.components.layouts.ArtCarousel
import paige.navic.ui.components.layouts.ArtCarouselItem
import paige.navic.ui.components.layouts.RootBottomBar
import paige.navic.ui.theme.NavicTheme
import paige.navic.ui.util.AmbientColorHolder
import paige.navic.di.ForceSystemBars
import paige.navic.ui.util.coverAmbientGradient
import paige.navic.ui.util.onAmbientColor
import paige.navic.ui.util.rememberCoverColorScheme
import paige.navic.ui.components.sheets.CollectionSheet
import paige.navic.ui.components.sheets.GapFillSheet
import paige.navic.ui.components.sheets.MissingAlbumSheet
import paige.navic.ui.screens.artist.components.DiscographyShelf
import paige.navic.ui.core.UiState
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.artist.components.ArtistActionButtons
import paige.navic.ui.components.sheets.AboutSheet
import paige.navic.ui.util.teaserText
import paige.navic.ui.screens.artist.components.ARTIST_TEASER_LIMIT
import paige.navic.ui.screens.artist.components.ArtistDetailScreenHeading
import paige.navic.ui.screens.artist.components.ArtistDetailScreenTopBar
import paige.navic.ui.screens.artist.viewmodels.ArtistDetailViewModel
import paige.navic.ui.screens.playlist.dialogs.PlaylistUpdateDialog
import paige.navic.ui.screens.share.dialogs.ShareDialog
import kotlin.time.Duration
import paige.navic.domain.models.displayName

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ArtistDetailScreen(
	artistId: String
) {
	val preferenceManager = koinInject<PreferenceManager>()

	val viewModel = koinViewModel<ArtistDetailViewModel>(
		key = artistId,
		parameters = { parametersOf(artistId) }
	)
	val platformContext = LocalPlatformContext.current
	val player = koinInject<MediaPlayerViewModel>()
	val playerState by player.steadyState.collectAsStateWithLifecycle()

	val selection by viewModel.selectedSong.collectAsStateWithLifecycle()
	val selectedSongIsStarred by viewModel.selectedSongIsStarred.collectAsStateWithLifecycle()
	val selectedSongRating by viewModel.selectedSongRating.collectAsStateWithLifecycle()

	val selectedAlbum by viewModel.selectedAlbum.collectAsStateWithLifecycle()
	val selectedAlbumIsStarred by viewModel.selectedAlbumIsStarred.collectAsStateWithLifecycle()
	val selectedAlbumRating by viewModel.selectedAlbumRating.collectAsStateWithLifecycle()

	val downloadManager = koinInject<DownloadManager>()
	val density = LocalDensity.current
	val backStack = LocalNavStack.current
	val layoutDirection = LocalLayoutDirection.current
	val artistState by viewModel.artistState.collectAsStateWithLifecycle()
	// lb-bot's editorial About. Null while it is in flight and null forever when
	// lb-bot is absent — the header then reads exactly as it did before, which is
	// the §7 rule: the surface is invisible, never an error.
	val meta by viewModel.meta.collectAsStateWithLifecycle()
	val aboutOpen by viewModel.aboutOpen.collectAsStateWithLifecycle()
	val starred by viewModel.starred.collectAsState()
	val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
	val allDownloads by viewModel.allDownloads.collectAsStateWithLifecycle()
	val discography by viewModel.discography.collectAsStateWithLifecycle()
	val selectedRelease by viewModel.selectedRelease.collectAsStateWithLifecycle()
	val selectedReleaseReadOnly by viewModel.selectedReleaseReadOnly.collectAsStateWithLifecycle()
	val selectedGap by viewModel.selectedGap.collectAsStateWithLifecycle()
	val downloadStatus by viewModel.collectionDownloadStatus()
		.collectAsState(DownloadStatus.NOT_DOWNLOADED)

	val snackBarManager = koinInject<SnackBarManager>()

	val scope = rememberCoroutineScope()

	val spatialSpec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
	val effectSpec = MaterialTheme.motionScheme.slowEffectsSpec<Float>()

	val scrolled by remember {
		derivedStateOf {
			with(density) { viewModel.scrollState.value.toDp() } >= 200.dp
		}
	}

	val gridState = rememberLazyGridState()

	var showDownloadDialog by remember { mutableStateOf(false) }

	var shareId by remember { mutableStateOf<String?>(null) }
	var shareExpiry by remember { mutableStateOf<Duration?>(null) }

	var playlistDialogShown by rememberSaveable { mutableStateOf(false) }

	// Apple-Music-style art theming from the artist photo, following the app's
	// light/dark mode (see CollectionDetailScreen for the same pattern).
	val appIsDark = when (preferenceManager.themeMode) {
		ThemeMode.System -> isSystemInDarkTheme()
		ThemeMode.Dark -> true
		ThemeMode.Light -> false
	}
	// Carry the ambient colour across navigation (album→artist etc.): start at the
	// colour we came from, and publish ours for the next screen.
	val ambientHolder = koinInject<AmbientColorHolder>()
	val initialSeed = remember { ambientHolder.last }
	val coverColors = rememberCoverColorScheme(
		artistState.data?.artist?.coverArtId,
		isDark = appIsDark,
		initialSeed = initialSeed
	)
	LaunchedEffect(coverColors.seed) { ambientHolder.last = coverColors.seed }
	// STABLE (per-artist) ambient colours drive the theme, the containers and the text colour —
	// so the crossfade below never re-derives the scheme or flips LocalContentColor per frame.
	// Same split as CollectionDetailScreen.
	// See CollectionDetailScreen: off means the app's own surface, not an eased tint of it.
	val (washTop, ambientBottom) = coverAmbientGradient(coverColors.seed, coverColors.isDark)
	val ambientTop = if (coverColors.themed) washTop else MaterialTheme.colorScheme.surface
	val onAmbient = onAmbientColor(ambientTop, coverColors.scheme)
		// Status-bar icons follow the (cover-driven) page brightness.
		ForceSystemBars(coverColors.isDark)
	// The wash's own ease lives inside [CoverAmbientBackground], in the draw phase — reading it
	// here in composition would recompose the whole page, blurred backdrop and all, on every
	// frame of the 450ms ease. That was the stutter.
	NavicTheme(coverColors.scheme, contentColor = onAmbient) {
	Scaffold(
		containerColor = ambientTop,
		topBar = {
			ArtistDetailScreenTopBar(
				scrolled = scrolled,
				artistState = artistState,
				starred = starred,
				onSetStarred = { viewModel.starArtist(it) },
			)
		},
		bottomBar = {
			val scrollManager = LocalBottomBarScrollManager.current
			if (preferenceManager.bottomBarVisibilityMode == BottomBarVisibilityMode.AllScreens) {
				// Fade the scrim to THIS page's bottom colour, not `surface`: on a light
				// cover the scheme neutral is near-white and ended the page in a white band
				// under the nav buttons. Same rule as the browsing pages, which get it from
				// LocalCoverAmbientBottom.
				RootBottomBar(
					scrolled = scrollManager.isTriggered,
					scrimColor = if (coverColors.themed) ambientBottom else null
				)
			}
		}
	) { contentPadding ->
		AnimatedContent(
			targetState = artistState,
			// Plain crossfade (no scale). The scale-in used to fire when the async
			// Loading→Success swap landed ON TOP of the screen-enter animation, giving a
			// "double bounce" on first open (gone on later opens once the data is cached).
			transitionSpec = {
				fadeIn(animationSpec = effectSpec) togetherWith fadeOut(animationSpec = effectSpec)
			},
			modifier = Modifier.fillMaxSize()
		) { artistState ->
			when (artistState) {
				is UiState.Error -> Box(Modifier.fillMaxSize().padding(contentPadding)) {
					ErrorBox(artistState)
				}

				is UiState.Loading -> Box(Modifier.fillMaxSize()) {
					ContainedLoadingIndicator(Modifier.size(80.dp).align(Alignment.Center))
				}

				is UiState.Success -> {
					val state = artistState.data
					BulkDownloadDialog(
						title = stringResource(Res.string.title_bulk_download),
						message = stringResource(
							Res.string.info_bulk_download_warning,
							state.artist.name
						),
						showDialog = showDownloadDialog,
						onDismissRequest = { showDownloadDialog = false },
						onConfirm = {
							scope.launch {
								state.albums.forEach { album ->
									downloadManager.downloadCollection(album)
								}
								snackBarManager.notify(Res.string.notice_download_started)
							}
						}
					)
					Box(Modifier.fillMaxSize()) {
					// The artist photo under this page's gradient — the shared
					// [CoverAmbientBackground], which the album page and every browsing page use
					// too. Transparent artwork scrim, unlike the album page's default: a press
					// photo is busy enough without one.
					if (coverColors.themed) CoverAmbientBackground(
						coverArtId = state.artist.coverArtId,
						seed = coverColors.seed,
						isDark = coverColors.isDark,
						artworkScrim = SolidColor(Color.Transparent)
					)
					Column(
						modifier = Modifier
							.fillMaxSize()
							.verticalScroll(viewModel.scrollState),
						verticalArrangement = Arrangement.spacedBy(12.dp),
						horizontalAlignment = Alignment.CenterHorizontally
					) {
						// The header's text is NAVIDROME's, and it does not move once
						// painted. It used to prefer lb-bot's Wikidata one-liner,
						// which meant a ~220-character teaser collapsed to a single
						// sentence a second or more later — the block shrank and
						// every button below it jumped up. lb-bot fills this slot
						// only when Navidrome has nothing at all, which is a late
						// arrival into an EMPTY slot and moves no text anyone had
						// started reading. The one-liner now lives in the sheet.
						val headerBio = state.artist.biography?.ifBlank { null }
							?: meta?.summary?.ifBlank { null }
							?: meta?.wikidataDescription?.ifBlank { null }
						// Is there a sheet worth opening? Partly known at t=0 — a
						// truncated bio always has more of itself to show — and
						// partly only once meta lands, which can only ever ADD the
						// affordance where there was none. The "more" link's own
						// gate stays on the t=0 half so it cannot appear and then
						// vanish; see DetailHeading.
						val aboutHasMore = meta?.let {
							it.summary.isNotBlank() || it.wikidataDescription.isNotBlank() ||
								it.credits.isNotEmpty() || it.links.isNotEmpty() ||
								it.relations.members.isNotEmpty() ||
								it.relations.related.isNotEmpty()
						} == true
						val canOpenAbout = aboutHasMore ||
							(headerBio != null &&
								teaserText(headerBio, ARTIST_TEASER_LIMIT).endsWith("\u2026"))
						ArtistDetailScreenHeading(
							artistName = state.artist.name,
							coverArtId = state.artist.coverArtId,
							subtitle = headerBio,
							lastfm = state.artist.lastFmUrl,
							innerPadding = contentPadding,
							scrolled = scrolled,
							ambientColor = ambientTop,
							onAboutClick = if (canOpenAbout) {
								{ viewModel.openAbout() }
							} else {
								null
							}
						)
						if (aboutOpen) {
							AboutSheet(
								title = state.artist.name,
								meta = meta,
								onDismissRequest = { viewModel.dismissAbout() },
								// So a truncated teaser opens the REST OF ITSELF
								// when lb-bot has nothing, rather than an empty
								// sheet or a different text about the same artist.
								fallbackBio = state.artist.biography,
								coverArtId = state.artist.coverArtId
							)
						}
						ArtistActionButtons(
							onPlay = { viewModel.playArtistAlbums(player) },
							onDownload = {
								showDownloadDialog = true
							},
							onCancelDownload = {
								state.albums.forEach { album ->
									downloadManager.cancelCollectionDownload(album)
								}
							},
							onDeleteDownload = {
								state.albums.forEach { album ->
									downloadManager.deleteDownloadedCollection(album)
								}
								snackBarManager.notify(Res.string.notice_deleted_download)
							},
							downloadStatus = downloadStatus,
							playEnabled = state.albums.isNotEmpty(),
							modifier = Modifier.padding(top = 8.dp)
						)
						Column(
							modifier = Modifier
								.fillMaxWidth()
								.padding(
									start = contentPadding.calculateStartPadding(
										layoutDirection
									)
								)
								.padding(
									end = contentPadding.calculateEndPadding(
										layoutDirection
									)
								),
							verticalArrangement = Arrangement.spacedBy(12.dp),
							horizontalAlignment = Alignment.CenterHorizontally
						) {
							BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
							val gridRowWidth = maxWidth
							// BoxWithConstraints is a Box (stacks children) — wrap in a
							// Column so the header + grid stay vertical, not overlapping.
							Column(modifier = Modifier.fillMaxWidth()) {
							state.topSongs.takeIf { state.topSongs.isNotEmpty() }
								?.let { songs ->
									Row(
										modifier = Modifier
											.heightIn(min = 32.dp)
											.padding(top = 8.dp)
											.padding(horizontal = 16.dp)
											.fillMaxWidth(),
										verticalAlignment = Alignment.CenterVertically,
										horizontalArrangement = Arrangement.SpaceBetween
									) {
										Text(
											stringResource(Res.string.option_sort_frequent),
											style = MaterialTheme.typography.titleMediumEmphasized,
											fontWeight = FontWeight(600),
											color = MaterialTheme.colorScheme.primary
										)
										Text(
											stringResource(Res.string.action_see_all),
											style = MaterialTheme.typography.labelLarge,
											color = MaterialTheme.colorScheme.primary,
											modifier = Modifier.clickable(onClick = dropUnlessResumed {
												platformContext.clickSound()
												backStack.add(
													Screen.SongList(
														nested = true,
														artistId = state.artist.id,
														artistName = state.artist.name
													)
												)
											})
										)
									}
									// Shrink to the song count so artists with only 1–2 frequent
									// tracks don't leave a cut-off, half-empty 3-row grid.
									val rowCount = songs.size.coerceIn(1, 3)
									LazyHorizontalGrid(
										rows = GridCells.Fixed(rowCount),
										state = gridState,
										flingBehavior = rememberSnapFlingBehavior(lazyGridState = gridState),
										modifier = Modifier.fillMaxWidth().height((rowCount * 100).dp)
									) {
										itemsIndexed(songs) { index, song ->
											val download =
												allDownloads.find { it.songId == song.id }
											SongRow(
												width = gridRowWidth,
												song = song,
												selected = selection == song,
												onClick = {
													if (playerState.currentSong?.id != song.id) {
														player.playNow(songs, index)
													} else {
														player.togglePlay()
													}
												},
												onLongClick = {
													viewModel.selectSong(song)
												},
												onDismissRequest = { viewModel.clearSelection() },
												starredState = if (selection == song) selectedSongIsStarred else song.starredAt != null,
												onAddStar = { viewModel.starSelectedSong() },
												onRemoveStar = { viewModel.unstarSelectedSong() },
												download = download,
												onDownload = { viewModel.downloadSong(song) },
												onCancelDownload = { viewModel.cancelDownload(song.id) },
												onDeleteDownload = { viewModel.deleteDownload(song.id) },
												onPlayNext = { player.playNextSingle(song) },
												onAddToQueue = { player.addToQueueSingle(song) },
												onShare = { shareId = song.id },
												isOnline = isOnline,
												rating = selectedSongRating,
												onSetRating = { viewModel.rateSelectedSong(it) },
												// Cover accent (under NavicTheme), translucent so the
												// page gradient shows through (frosted) — instead of
												// the near-white default surface that clashed.
												containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
											)
										}
									}
								}
							}
							}
							// The legacy play-count carousel, shown only when the
							// discography shelf below is NOT rendering. Two album rows
							// on one page is just confusing, and the discography is the
							// better of the two — but without lb-bot the page has to
							// look exactly as it always has.
							val hasDiscography = discography.available && discography.indexed
							val carouselAlbums = remember(state.albums, hasDiscography) {
								if (hasDiscography) persistentListOf()
								else state.albums.sortedByDescending { album -> album.playCount }
									.toImmutableList()
							}
							ArtCarousel(
								stringResource(Res.string.title_albums),
								carouselAlbums
							) { album ->
								// (There was a `getCollectionDownloadStatus` collect here whose
								// result nothing read. Each visible tile was building a fresh flow
								// over the whole downloads table, re-filtering it on every
								// recomposition, and throwing the answer away.)
								ArtCarouselItem(
									coverArtId = album.coverArtId,
									title = album.displayName,
									contentDescription = null,
									onSelect = { viewModel.selectAlbum(album) },
									onClick = dropUnlessResumed {
										backStack.add(Screen.CollectionDetail(album.id, "artist"))
									}
								)
							}
							// NOTE: this must stay above the similar-artists guard below,
							// which is an early `return@Column` — anything after it is
							// skipped entirely for an artist with no similar artists.
							DiscographyShelf(
								ui = discography,
								canIndex = !state.artist.musicBrainzId.isNullOrBlank(),
								onIndex = { viewModel.indexArtist() },
								onOpenEntry = { entry ->
									when {
										// Tapping an album you own opens it — including
										// a partly-owned one. Filling its gaps is the
										// exception, and lives in the long-press sheet
										// below; making it the tap action put a chore in
										// front of the thing the user actually wanted.
										entry.album != null -> backStack.add(
											Screen.CollectionDetail(entry.album.id, "artist")
										)
										// lb-bot has placed the files and Navidrome hasn't
										// indexed them yet. Open the album if Room has
										// caught up, else say what is happening — a
										// tile you can see and can't touch reads as
										// broken, which is what this used to be.
										entry.pendingSync -> {
											val albumId = viewModel.openPendingSync(entry)
											if (albumId != null) {
												backStack.add(
													Screen.CollectionDetail(albumId, "artist")
												)
											}
										}
										// Nothing to open: only a release to fetch.
										entry.release != null ->
											viewModel.selectRelease(entry.release)
									}
								},
								onSelectEntry = { entry ->
									entry.album?.let { viewModel.selectAlbum(it) }
								}
							)
							// Guest / featured credits. Above the similar-artists guard for the same
							// reason the shelf is: that guard is an early return@Column.
							if (state.appearsOn.isNotEmpty()) {
								ArtCarousel(
									stringResource(Res.string.title_appears_on),
									state.appearsOn.toImmutableList()
								) { album ->
									ArtCarouselItem(
										coverArtId = album.coverArtId,
										title = album.displayName,
										subtitle = album.artistName,
										contentDescription = null,
										onSelect = { viewModel.selectAlbum(album) },
										onClick = dropUnlessResumed {
											backStack.add(Screen.CollectionDetail(album.id, "artist"))
										}
									)
								}
							}
							if (state.similarArtists.isEmpty()) return@Column
							ArtCarousel(
								stringResource(Res.string.title_similar_artists),
								state.similarArtists.toImmutableList()
							) { artist ->
								ArtCarouselItem(
									coverArtId = artist.coverArtId,
									isArtist = true,
									title = artist.name,
									subtitle = pluralStringResource(
										Res.plurals.count_albums,
										artist.albumCount,
										artist.albumCount
									),
									contentDescription = null,
									onClick = dropUnlessResumed {
										backStack.add(Screen.ArtistDetail(artist.id))
									}
								)
							}
						}
						Spacer(Modifier.height(contentPadding.calculateBottomPadding()))
					}
					}
				}
			}
		}
	}

	}

	ShareDialog(
		id = shareId,
		onIdClear = { shareId = null; viewModel.clearSelection() },
		expiry = shareExpiry,
		onExpiryChange = { shareExpiry = it }
	)

	// Hoisted out of the album carousel: long-press now comes from two places (the
	// legacy carousel and the discography shelf), and only one of them renders at a
	// time. Keeping the sheet inside either would tie it to whichever row happened
	// to be showing.
	selectedAlbum?.let { album ->
		// Remembered on the album: `getCollectionDownloadStatus` builds a new flow over the
		// app-wide downloads list each call, so an unremembered one re-subscribed and re-filtered
		// on every recomposition of this sheet.
		val albumDownloadFlow = remember(album.id) {
			downloadManager.getCollectionDownloadStatus(album.songs.map { it.id })
		}
		val albumDownloadStatus by albumDownloadFlow
			.collectAsState(initial = DownloadStatus.NOT_DOWNLOADED)
		// Filling gaps is offered only when lb-bot actually has a group for this
		// album — the sheet hides the row when these are null, like every other
		// optional action it takes.
		val gapEntry = discography.sections
			.asSequence()
			.flatMap { it.entries }
			.firstOrNull { it.album?.id == album.id && it.gapGroupId != null }
		CollectionSheet(
			onDismissRequest = { viewModel.clearAlbumSelection() },
			collection = album,
			starred = selectedAlbumIsStarred,
			onShare = { shareId = album.id },
			onPlayNext = { player.playNext(album) },
			onAddToQueue = { player.addToQueue(album) },
			onSetStarred = { viewModel.starAlbum(!selectedAlbumIsStarred) },
			onAddAllToPlaylist = { playlistDialogShown = true },
			downloadStatus = albumDownloadStatus,
			onDownloadAll = {
				scope.launch {
					downloadManager.downloadCollection(album)
					snackBarManager.notify(Res.string.notice_download_started)
				}
			},
			onCancelDownloadAll = {
				scope.launch {
					album.songs.forEach { downloadManager.cancelDownload(it.id) }
				}
			},
			onDeleteDownloadAll = {
				scope.launch { downloadManager.deleteDownloadedCollection(album) }
			},
			rating = selectedAlbumRating,
			onSetRating = { viewModel.rateSelectedAlbum(it) },
			missingTrackCount = gapEntry?.missingTracks,
			onFillGaps = gapEntry?.let { entry -> { viewModel.selectGap(entry) } }
		)
	}

	selectedRelease?.let { release ->
		MissingAlbumSheet(
			release = release,
			onDismissRequest = { viewModel.selectRelease(null) },
			readOnly = selectedReleaseReadOnly
		)
	}

	selectedGap?.let { entry ->
		val groupId = entry.gapGroupId
		if (groupId != null) {
			GapFillSheet(
				groupId = groupId,
				albumTitle = entry.title,
				coverArtId = entry.album?.coverArtId,
				onDismissRequest = { viewModel.selectGap(null) }
			)
		}
	}

	if (playlistDialogShown) {
		PlaylistUpdateDialog(
			songs = selectedAlbum?.songs.orEmpty().toPersistentList(),
			onDismissRequest = { playlistDialogShown = false }
		)
	}
}

