package paige.navic.ui.screens.collection

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.info_no_songs
import navic.composeapp.generated.resources.title_disc_number
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import paige.navic.di.LocalBottomBarScrollManager
import paige.navic.di.LocalPlatformContext
import paige.navic.data.database.entities.DownloadStatus
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.models.DomainAlbum
import paige.navic.domain.models.DomainPlaylist
import paige.navic.domain.models.DomainSongCollection
import paige.navic.domain.models.settings.BottomBarVisibilityMode
import paige.navic.domain.models.settings.ThemeMode
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Album
import paige.navic.icons.outlined.Note
import paige.navic.shared.MediaPlayerViewModel
import paige.navic.ui.components.common.CoverAmbientBackground
import paige.navic.ui.components.common.ContentUnavailable
import paige.navic.ui.components.layouts.PullToRefreshBox
import paige.navic.ui.components.layouts.RootBottomBar
import paige.navic.ui.core.UiState
import paige.navic.ui.screens.collection.components.CollectionDetailScreenFooterRow
import paige.navic.ui.screens.collection.components.CollectionDetailScreenHeadingRow
import paige.navic.ui.screens.collection.components.CollectionDetailScreenHeadingRowButtons
import paige.navic.ui.screens.collection.components.CollectionDetailScreenSongRow
import paige.navic.ui.screens.collection.components.CollectionDetailScreenSongRowDropdown
import paige.navic.ui.screens.collection.components.CollectionDetailScreenTopBar
import paige.navic.ui.components.sheets.AboutSheet
import paige.navic.ui.screens.collection.components.collectionDetailScreenAboutRow
import paige.navic.ui.screens.collection.components.collectionDetailScreenMoreByArtistRow
import paige.navic.ui.screens.collection.components.collectionDetailScreenSimilarAlbumsRow
import paige.navic.ui.screens.collection.viewmodels.CollectionDetailViewModel
import paige.navic.ui.screens.share.dialogs.ShareDialog
import paige.navic.ui.theme.NavicTheme
import paige.navic.di.ForceSystemBars
import paige.navic.di.isLandscape
import paige.navic.ui.util.AmbientColorHolder
import paige.navic.ui.util.coverAmbientGradient
import paige.navic.ui.util.onAmbientColor
import paige.navic.ui.util.rememberCoverColorScheme
import paige.navic.ui.util.withoutTop
import kotlin.time.Duration
import paige.navic.ui.components.snackbars.ErrorSnackBar

@Composable
fun CollectionDetailScreen(
	collectionId: String,
	tab: String
) {
	val preferenceManager = koinInject<PreferenceManager>()

	val viewModel = koinViewModel<CollectionDetailViewModel>(
		key = collectionId,
		parameters = { parametersOf(collectionId) }
	)

	val player = koinInject<MediaPlayerViewModel>()
	val playerState by player.steadyState.collectAsStateWithLifecycle()

	val collectionState by viewModel.collectionState.collectAsState()
	val collection = collectionState.data
	val selection by viewModel.selectedSong.collectAsState()
	val selectedAlbum by viewModel.selectedAlbum.collectAsState()
	val isOnline by viewModel.isOnline.collectAsState()
	val starred by viewModel.starred.collectAsState()

	var shareId by remember { mutableStateOf<String?>(null) }
	var shareExpiry by remember { mutableStateOf<Duration?>(null) }

	val albumInfoState by viewModel.albumInfoState.collectAsState()
	// lb-bot's editorial About and the similar-albums shelf. Both null until they
	// answer and null forever when lb-bot is absent, in which case neither renders
	// — §7: the surface is invisible, never an error.
	val meta by viewModel.meta.collectAsStateWithLifecycle()
	val similar by viewModel.similar.collectAsStateWithLifecycle()
	val aboutOpen by viewModel.aboutOpen.collectAsStateWithLifecycle()
	val selectedSongIsStarred by viewModel.selectedSongIsStarred.collectAsStateWithLifecycle()
	val selectedSongRating by viewModel.selectedSongRating.collectAsStateWithLifecycle()
	val selectedAlbumIsStarred by viewModel.selectedAlbumIsStarred.collectAsStateWithLifecycle()
	val selectedAlbumRating by viewModel.selectedAlbumRating.collectAsStateWithLifecycle()
	val otherAlbums by viewModel.otherAlbums.collectAsState()
	val allDownloads by viewModel.allDownloads.collectAsState()
	val downloadStatus by viewModel.collectionDownloadStatus()
		.collectAsState(DownloadStatus.NOT_DOWNLOADED)

	val rating by viewModel.rating.collectAsStateWithLifecycle()

	// The disc-sorted album and its per-disc grouping, computed once per collection.
	//
	// Both used to be built inside the LazyColumn's content lambda, which re-runs whenever this
	// screen recomposes (a download-progress tick, a selection, a starred flag). Worse, the
	// "does this album have more than one disc" check re-ran `groupBy` over every song a SECOND
	// time, once per group. None of it depends on anything but the collection.
	val sortedAlbum = remember(collection) {
		(collection as? DomainAlbum)?.let { album ->
			album.copy(
				songs = album.songs.sortedWith(
					compareBy({ it.discNumber }, { it.trackNumber })
				)
			)
		}
	}
	val discGroups = remember(sortedAlbum) {
		sortedAlbum?.songs?.groupBy { it.discNumber }?.entries?.toList().orEmpty()
	}
	val multipleDiscs = discGroups.size > 1

	val titleAlpha by remember {
		derivedStateOf {
			if (viewModel.listState.firstVisibleItemIndex >= 1) return@derivedStateOf 1f
			val height =
				viewModel.listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }?.size?.toFloat()
					?: 0f
			if (height > 0f) {
				val threshold = height * 0.4f
				((viewModel.listState.firstVisibleItemScrollOffset.toFloat() - threshold) / (height - threshold)).coerceIn(
					0f,
					1f
				)
			} else {
				0f
			}
		}
	}

	// Apple-Music-style art theming, following the app's light/dark mode: a light
	// cover gives a light page in light mode and a dark page in dark mode — instead
	// of forcing dark everywhere. (Once the ambient matches the theme, the global
	// status-bar handling is already correct, so no per-screen override is needed.)
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
		collection?.coverArtId,
		isDark = appIsDark,
		initialSeed = initialSeed
	)
	LaunchedEffect(coverColors.seed) { ambientHolder.last = coverColors.seed }
	// The seed is always valid (neutral surface until extraction completes), so
	// easing toward the real cover colour gives a smooth Apple-Music-style bleed-in
	// with no late colour pop — background + hero fade ease together off `animatedSeed`.
	// STABLE (per-song) ambient colours drive the theme + text colour, so the crossfade below
	// never re-derives the scheme or flips LocalContentColor every frame (recomposition storm).
	// With cover theming off there is no hero wash at all: the page is the app's own surface, and
	// the blurred backdrop below is skipped. `coverColors.seed` is the plain surface then, and
	// `coverAmbientGradient` would still ease it toward white/black — i.e. paint a tint nobody
	// asked for.
	val (washTop, washBottom) = coverAmbientGradient(coverColors.seed, coverColors.isDark)
	val stableTop = if (coverColors.themed) washTop else MaterialTheme.colorScheme.surface
	val onAmbient = onAmbientColor(stableTop, coverColors.scheme)
	// The BACKGROUND wash eases between songs; kept as a State and read in the draw phase
	// (drawWithCache, below), so per-frame updates never recompose the content tree.
	val animatedSeed = animateColorAsState(coverColors.seed, animationSpec = tween(450))
		// Status-bar icons follow the (cover-driven) page brightness.
		ForceSystemBars(coverColors.isDark)
	NavicTheme(coverColors.scheme, contentColor = onAmbient) {
	Scaffold(
		containerColor = stableTop,
		topBar = {
			CollectionDetailScreenTopBar(
				albumInfoState = albumInfoState,
				collection = collection,
				titleAlpha = titleAlpha,
				onSetShareId = { shareId = it },
				onDownloadAll = { viewModel.downloadAll() },
				onCancelDownloadAll = { viewModel.cancelDownloadAll() },
				onPlayNext = { if (collection != null) player.playNext(collection) },
				onAddToQueue = { if (collection != null) player.addToQueue(collection) },
				downloadStatus = downloadStatus,
				rating = if (collection !is DomainPlaylist) rating else null,
				onSetRating = if (collection !is DomainPlaylist) {
					{ viewModel.rateAlbum(it) }
				} else null,
				starred = if (collection !is DomainPlaylist) starred else null,
				onSetStarred = if (collection !is DomainPlaylist) {
					{ viewModel.starAlbum(it) }
				} else null,
				refreshCollection = { viewModel.refreshCollection(false) }
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
					scrimColor = if (coverColors.themed) washBottom else null
				)
			}
		}
	) { contentPadding ->
		Box(Modifier.fillMaxSize()) {
			// The blurred cover art under this page's own gradient — the shared
			// [CoverAmbientBackground], which the browsing pages use too.
			if (coverColors.themed) CoverAmbientBackground(
				coverArtId = collection?.coverArtId,
				seed = coverColors.seed,
				isDark = coverColors.isDark
			)
		PullToRefreshBox(
			modifier = Modifier.fillMaxSize(),
			finished = collectionState !is UiState.Loading,
			onRefresh = { viewModel.refreshCollection(true) },
			key = collectionState
		) {
			LazyColumn(
				modifier = Modifier.fillMaxSize(),
				horizontalAlignment = Alignment.CenterHorizontally,
				// Keep the top inset now that the cover is a floating card (not a full-bleed
				// image): the card must clear the status bar + top-bar buttons. The blurred-cover
				// wash still fills the whole screen behind, via BlendBackground above.
				contentPadding = contentPadding,
				state = viewModel.listState
			) {
				if (collection == null) return@LazyColumn

				item {
					CollectionDetailScreenHeadingRow(
						collection = collection,
						tab = tab,
						titleAlpha = 1f - titleAlpha
					)
				}

				item {
					CollectionDetailScreenHeadingRowButtons(
						collection = collection
					)
				}

				// The first album description this app has ever shown. What the
				// page carried before is the ID3 `comment` tag by way of
				// nothing at all: `getAlbumInfo2.notes` has been loaded on every
				// album page since forever and read by no one. It is the
				// fallback here, and lb-bot's Wikipedia text is the preferred
				// source because it comes with an attribution and an article.
				collectionDetailScreenAboutRow(
					meta = meta,
					fallbackNotes = (albumInfoState as? UiState.Success)?.data?.notes,
					onOpen = { viewModel.openAbout() }
				)

				if (sortedAlbum != null) {
					sortedAlbum.let { album ->
						discGroups.forEach { group ->
							if (group.key != null && multipleDiscs) {
								item {
									Row(
										modifier = Modifier
											.fillMaxWidth()
											.padding(horizontal = 16.dp)
											.padding(
												top = if (group.key == 1) 0.dp else 12.dp,
												bottom = 4.dp
											)
											.heightIn(min = 32.dp),
										verticalAlignment = Alignment.CenterVertically
									) {
										Icon(
											imageVector = Icons.Outlined.Album,
											contentDescription = null,
											tint = MaterialTheme.colorScheme.onSurfaceVariant,
											modifier = Modifier.size(20.dp)
										)

										Spacer(modifier = Modifier.width(8.dp))

										Text(
											text = stringResource(
												Res.string.title_disc_number,
												group.key as Int
											),
											style = MaterialTheme.typography.titleMediumEmphasized,
											fontWeight = FontWeight(600),
											color = MaterialTheme.colorScheme.onSurfaceVariant
										)
									}
								}
							}
							itemsIndexed(group.value) { index, song ->
								val download = allDownloads.find { it.songId == song.id }
								Box {
									CollectionDetailScreenSongRow(
										song = song,
										index = index,
										count = group.value.count(),
										isPlaylist = false,
										onClick = {
											if (playerState.currentSong?.id != song.id) {
												player.playCollection(album, song)
											} else {
												player.togglePlay()
											}
										},
										onLongClick = {
											viewModel.selectSong(song)
										},
										onPlayNext = {
											player.playNextSingle(song)
										},
										onAddToQueue = {
											player.addToQueueSingle(song)
										},
										isStarred = if (selection == song) selectedSongIsStarred else song.starredAt != null,
										download = download,
										isOffline = !isOnline
									)
									CollectionDetailScreenSongRowDropdown(
										expanded = selection == song,
										onDismissRequest = { viewModel.clearSelection() },
										onRemoveStar = { viewModel.unstarSelectedSong() },
										onAddStar = { viewModel.starSelectedSong() },
										onShare = { shareId = song.id },
										collection = collection,
										song = song,
										onRemoveFromPlaylist = { viewModel.removeFromPlaylist() },
										starred = selectedSongIsStarred,
										downloadStatus = download?.status,
										onDownload = { viewModel.downloadSong(song) },
										onCancelDownload = { viewModel.cancelDownload(song.id) },
										onDeleteDownload = { viewModel.deleteDownload(song.id) },
										onPlayNext = { player.playNextSingle(song) },
										onAddToQueue = { player.addToQueueSingle(song) },
										rating = selectedSongRating,
										onSetRating = { viewModel.rateSelectedSong(it) }
									)
								}
							}
						}
					}
				} else {
					itemsIndexed(collection.songs) { index, song ->
						val download = allDownloads.find { it.songId == song.id }
						Box {
							CollectionDetailScreenSongRow(
								song = song,
								index = index,
								count = collection.songs.count(),
								isPlaylist = true,
								onClick = {
									if (playerState.currentSong?.id != song.id) {
										player.playCollection(collection, song)
									} else {
										player.togglePlay()
									}
								},
								onLongClick = {
									viewModel.selectSong(song)
								},
								onPlayNext = {
									player.playNextSingle(song)
								},
								onAddToQueue = {
									player.addToQueueSingle(song)
								},
								isStarred = if (selection == song) selectedSongIsStarred else song.starredAt != null,
								download = download,
								isOffline = !isOnline
							)
							CollectionDetailScreenSongRowDropdown(
								expanded = selection == song,
								onDismissRequest = { viewModel.clearSelection() },
								onRemoveStar = { viewModel.unstarSelectedSong() },
								onAddStar = { viewModel.starSelectedSong() },
								onShare = { shareId = song.id },
								collection = collection,
								song = song,
								onRemoveFromPlaylist = { viewModel.removeFromPlaylist() },
								starred = selectedSongIsStarred,
								downloadStatus = download?.status,
								onDownload = { viewModel.downloadSong(song) },
								onCancelDownload = { viewModel.cancelDownload(song.id) },
								onDeleteDownload = { viewModel.deleteDownload(song.id) },
								onPlayNext = { player.playNextSingle(song) },
								onAddToQueue = { player.addToQueueSingle(song) },
								rating = selectedSongRating,
								onSetRating = { viewModel.rateSelectedSong(it) }
							)
						}
					}
				}

				if (collection.songs.isEmpty()) {
					item {
						ContentUnavailable(
							icon = Icons.Outlined.Note,
							label = stringResource(Res.string.info_no_songs)
						)
					}
				}

				item { CollectionDetailScreenFooterRow(collection) }

				// Deliberately above "More by this artist": that row is more of
				// the same record's artist, this one is the reason to look up.
				collectionDetailScreenSimilarAlbumsRow(similar)

				(collection as? DomainAlbum)?.artistName?.let { artistName ->
					collectionDetailScreenMoreByArtistRow(
						artistName = artistName,
						artistAlbums = otherAlbums,
						selectedAlbum = selectedAlbum,
						onSetShareId = { shareId = it },
						onPlayNext = if (selectedAlbum != null) {
							{ player.playNext(selectedAlbum as DomainSongCollection) }
						} else null,
						onAddToQueue = if (selectedAlbum != null) {
							{ player.addToQueue(selectedAlbum as DomainSongCollection) }
						} else null,
						selectedAlbumRating = selectedAlbumRating,
						selectedAlbumStarred = selectedAlbumIsStarred,
						onSetAlbumRating = { viewModel.rateSelectedAlbum(it) },
						onSetAlbumStarred = { viewModel.starSelectedAlbum(it) },
						onSelect = { viewModel.selectAlbum(it) },
						onDeselect = { viewModel.clearSelection() },
						tab = tab
					)
				}
			}
		}
		}
	}

	}

	if (aboutOpen) {
		AboutSheet(
			title = collection?.name ?: "",
			meta = meta,
			onDismissRequest = { viewModel.dismissAbout() },
			// So the teaser opens the rest of ITSELF when lb-bot has nothing —
			// the row now prefers `notes`, so that is what was on screen.
			fallbackBio = (albumInfoState as? UiState.Success)?.data?.notes,
			coverArtId = collection?.coverArtId
		)
	}

	ErrorSnackBar(
		error = (collectionState as? UiState.Error)?.error,
		onClearError = { viewModel.clearError() }
	)

	ShareDialog(
		id = shareId,
		onIdClear = { shareId = null; viewModel.clearSelection() },
		expiry = shareExpiry,
		onExpiryChange = { shareExpiry = it }
	)
}
