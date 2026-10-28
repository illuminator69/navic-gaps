package paige.navic.ui.screens.mixes

import paige.navic.ui.components.common.rowPlaceholders
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import com.kyant.capsule.ContinuousCapsule
import com.kyant.capsule.ContinuousRoundedRectangle
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_delete
import navic.composeapp.generated.resources.action_more
import navic.composeapp.generated.resources.action_play
import navic.composeapp.generated.resources.action_rename
import navic.composeapp.generated.resources.action_save_as_playlist
import navic.composeapp.generated.resources.action_shuffle
import navic.composeapp.generated.resources.mix_page_building
import navic.composeapp.generated.resources.mix_page_continues
import navic.composeapp.generated.resources.mix_page_empty
import navic.composeapp.generated.resources.mix_page_fresh
import navic.composeapp.generated.resources.mix_unknown_kind
import navic.composeapp.generated.resources.radio_mood_needs_audiomuse
import navic.composeapp.generated.resources.queue_your_mix
import navic.composeapp.generated.resources.title_delete_mix
import navic.composeapp.generated.resources.title_rename_mix
import navic.composeapp.generated.resources.action_cancel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import paige.navic.data.database.entities.DownloadStatus
import paige.navic.di.ForceSystemBars
import paige.navic.di.LocalBottomBarScrollManager
import paige.navic.di.LocalNavStack
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.RadioManager
import paige.navic.domain.models.DomainSong
import paige.navic.domain.models.Mix
import paige.navic.domain.models.YOUR_MIX_ID
import paige.navic.domain.models.isCustomRadio
import paige.navic.domain.models.settings.BottomBarVisibilityMode
import paige.navic.domain.models.settings.ThemeMode
import paige.navic.icons.Icons
import paige.navic.icons.filled.Play
import paige.navic.icons.outlined.Badge
import paige.navic.icons.outlined.Delete
import paige.navic.icons.outlined.Equalizer
import paige.navic.icons.outlined.MoreVert
import paige.navic.icons.outlined.PlaylistAdd
import paige.navic.icons.outlined.Shuffle
import paige.navic.shared.MediaPlayerViewModel
import paige.navic.ui.components.common.ContentUnavailable
import paige.navic.ui.components.common.CoverAmbientBackground
import paige.navic.ui.components.common.SegmentedListButton
import paige.navic.ui.components.common.SegmentedListButtonDefaults
import paige.navic.ui.components.common.blur.LocalExpressiveBlur
import paige.navic.ui.components.common.blur.expressiveBlurEffect
import paige.navic.ui.components.dialogs.FormDialog
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.components.layouts.PullToRefreshBox
import paige.navic.ui.components.layouts.RootBottomBar
import paige.navic.ui.components.layouts.TopBarButton
import paige.navic.ui.components.sheets.SongSheet
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.collection.components.CollectionDetailScreenSongRow
import paige.navic.ui.screens.mixes.viewmodels.MixDetailViewModel
import paige.navic.ui.screens.mixes.viewmodels.MixPageState
import paige.navic.ui.screens.playlist.dialogs.PlaylistCreateDialog
import paige.navic.ui.screens.playlist.dialogs.PlaylistUpdateDialog
import paige.navic.ui.theme.NavicTheme
import paige.navic.ui.theme.defaultFont
import paige.navic.ui.util.AmbientColorHolder
import paige.navic.ui.util.coverAmbientGradient
import paige.navic.ui.util.onAmbientColor
import paige.navic.ui.util.rememberCoverColorScheme

/**
 * A mix's page (P5 round 2, the owner's note): a mix is opened, not started. It reads as a
 * playlist's page does (the face over its own wash, the name, what kind of mix it is, Shuffle and
 * Play, the songs as the playlist's rows) and stays a mix: its line says so, its songs are built
 * afresh on each opening (YouTube Music's mixes do the same), and "Save as playlist" keeps the set
 * on screen as a Navidrome playlist. A tap on a song plays the mix from there; a mix that plays
 * as a station carries on after the last song shown.
 *
 * Leaves itself when the mix is deleted, here or on another device.
 */
@Composable
fun MixDetailScreen(mixId: String) {
	val viewModel = koinViewModel<MixDetailViewModel>(key = "mix-$mixId", parameters = { parametersOf(mixId) })
	val preferenceManager = koinInject<PreferenceManager>()
	val player = koinInject<MediaPlayerViewModel>()
	val radioManager = koinInject<RadioManager>()
	val backStack = LocalNavStack.current

	val stored by viewModel.mix.collectAsStateWithLifecycle()
	val state by viewModel.state.collectAsStateWithLifecycle()
	val connected by viewModel.connected.collectAsStateWithLifecycle()
	val selected by viewModel.selected.collectAsStateWithLifecycle()
	val selectedLiked by viewModel.selectedLiked.collectAsStateWithLifecycle()
	val liked by viewModel.liked.collectAsStateWithLifecycle()
	val allDownloads by viewModel.allDownloads.collectAsStateWithLifecycle()
	val playerState by player.steadyState.collectAsStateWithLifecycle()

	val yourMixName = stringResource(Res.string.queue_your_mix)
	LaunchedEffect(Unit) { viewModel.open(yourMixName) }
	val mix = stored?.let { if (it.id == YOUR_MIX_ID) it.copy(name = yourMixName) else it }

	// Gone from the hub after it was here: deleted (Undo, the menu, another device). Leave.
	var seen by rememberSaveable { mutableStateOf(false) }
	LaunchedEffect(stored) {
		if (stored != null) seen = true
		else if (seen) {
			val index = backStack.indexOfLast { (it as? Screen.MixDetail)?.mixId == mixId }
			if (index > 0) backStack.removeAt(index)
		}
	}

	val songs = (state as? MixPageState.Built)?.build?.songs.orEmpty()
	val listState = rememberLazyListState()
	val titleAlpha by remember {
		derivedStateOf {
			if (listState.firstVisibleItemIndex >= 1) return@derivedStateOf 1f
			val height = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }?.size?.toFloat() ?: 0f
			if (height <= 0f) 0f
			else ((listState.firstVisibleItemScrollOffset - height * 0.4f) / (height * 0.6f)).coerceIn(0f, 1f)
		}
	}

	// The playlist page's theming (CollectionDetailScreen): the mix's stamped cover washes it; a
	// mix with none keeps the app's surface.
	val appIsDark = when (preferenceManager.themeMode) {
		ThemeMode.System -> isSystemInDarkTheme()
		ThemeMode.Dark -> true
		ThemeMode.Light -> false
	}
	val ambientHolder = koinInject<AmbientColorHolder>()
	val initialSeed = remember { ambientHolder.last }
	val cover = mix?.coverArtId?.ifBlank { null }
	val coverColors = rememberCoverColorScheme(cover, isDark = appIsDark, initialSeed = initialSeed)
	LaunchedEffect(coverColors.seed) { ambientHolder.last = coverColors.seed }
	val (washTop, washBottom) = coverAmbientGradient(coverColors.seed, coverColors.isDark)
	val themed = coverColors.themed && cover != null
	val stableTop = if (themed) washTop else MaterialTheme.colorScheme.surface
	val onAmbient = onAmbientColor(stableTop, coverColors.scheme)
	ForceSystemBars(coverColors.isDark)

	var menuOpen by remember { mutableStateOf(false) }
	var renaming by remember { mutableStateOf(false) }
	var deleting by remember { mutableStateOf(false) }
	var saving by remember { mutableStateOf(false) }
	var addingToPlaylist by remember { mutableStateOf<DomainSong?>(null) }
	val stable = mix != null && mix.id != YOUR_MIX_ID

	NavicTheme(coverColors.scheme, contentColor = onAmbient) {
		Scaffold(
			containerColor = stableTop,
			topBar = {
				NestedTopBar(
					modifier = Modifier.expressiveBlurEffect(LocalExpressiveBlur.current),
					colors = TopAppBarDefaults.topAppBarColors(
						containerColor = MaterialTheme.colorScheme.surface.copy(alpha = titleAlpha * 0.7f)
					),
					title = {
						Text(
							mix?.name.orEmpty(),
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
							modifier = Modifier.alpha(titleAlpha)
						)
					},
					actions = {
						if (mix != null) Box {
							TopBarButton(onClick = { menuOpen = true }) {
								Icon(Icons.Outlined.MoreVert, stringResource(Res.string.action_more))
							}
							DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
								DropdownMenuItem(
									text = { Text(stringResource(Res.string.action_save_as_playlist)) },
									leadingIcon = { Icon(Icons.Outlined.PlaylistAdd, null) },
									enabled = songs.isNotEmpty(),
									onClick = {
										menuOpen = false
										saving = true
									}
								)
								// Your mix is not stored: nothing to rename or delete.
								if (stable) {
									DropdownMenuItem(
										text = { Text(stringResource(Res.string.action_rename)) },
										leadingIcon = { Icon(Icons.Outlined.Badge, null) },
										enabled = connected,
										onClick = {
											menuOpen = false
											renaming = true
										}
									)
									DropdownMenuItem(
										text = { Text(stringResource(Res.string.action_delete)) },
										leadingIcon = { Icon(Icons.Outlined.Delete, null) },
										enabled = connected,
										onClick = {
											menuOpen = false
											deleting = true
										}
									)
								}
							}
						}
					}
				)
			},
			bottomBar = {
				val scrollManager = LocalBottomBarScrollManager.current
				if (preferenceManager.bottomBarVisibilityMode == BottomBarVisibilityMode.AllScreens) {
					RootBottomBar(scrolled = scrollManager.isTriggered, scrimColor = if (themed) washBottom else null)
				}
			}
		) { contentPadding ->
			Box(Modifier.fillMaxSize()) {
				if (themed) CoverAmbientBackground(coverArtId = cover, seed = coverColors.seed, isDark = coverColors.isDark)
				PullToRefreshBox(
					modifier = Modifier.fillMaxSize(),
					finished = state != MixPageState.Building,
					onRefresh = { viewModel.rebuild() },
					key = state
				) {
					LazyColumn(
						modifier = Modifier.fillMaxSize(),
						state = listState,
						horizontalAlignment = Alignment.CenterHorizontally,
						contentPadding = contentPadding
					) {
						if (mix == null) return@LazyColumn
						item(key = "heading") { MixHeading(mix, titleAlpha = 1f - titleAlpha) }
						item(key = "buttons") {
							MixButtons(
								enabled = songs.isNotEmpty(),
								onShuffle = { viewModel.play(shuffle = true) },
								onPlay = { viewModel.play() },
								onSave = { saving = true }
							)
						}
						when (val shown = state) {
							// Waiting rows, as the mood mix page waits (audit L1-05), under the line that says why.
							MixPageState.Building -> {
								item(key = "building") {
									Text(
										stringResource(Res.string.mix_page_building),
										style = MaterialTheme.typography.bodyMedium,
										color = LocalContentColor.current.copy(alpha = 0.7f),
										modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
									)
								}
								rowPlaceholders("mix", height = 64.dp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
							}
							MixPageState.Empty -> item(key = "empty") {
								ContentUnavailable(
									modifier = Modifier.padding(vertical = 32.dp),
									icon = Icons.Outlined.Equalizer,
									label = stringResource(Res.string.mix_page_empty)
								)
							}
							MixPageState.NeedsAudioMuse -> item(key = "needs-audiomuse") {
								ContentUnavailable(
									modifier = Modifier.padding(vertical = 32.dp),
									icon = Icons.Outlined.Equalizer,
									label = stringResource(Res.string.radio_mood_needs_audiomuse)
								)
							}
							MixPageState.UnknownKind -> item(key = "unknown") {
								ContentUnavailable(
									modifier = Modifier.padding(vertical = 32.dp),
									icon = Icons.Outlined.Equalizer,
									label = stringResource(Res.string.mix_unknown_kind)
								)
							}
							is MixPageState.Built -> {
								itemsIndexed(songs, key = { index, song -> "${song.id}-$index" }) { index, song ->
									val download = allDownloads.find { it.songId == song.id }
									CollectionDetailScreenSongRow(
										song = song,
										index = index,
										count = songs.size,
										isPlaylist = true,
										onClick = {
											if (playerState.currentSong?.id == song.id) player.togglePlay()
											else viewModel.play(index)
										},
										onLongClick = { viewModel.select(song) },
										onPlayNext = { player.playNextSingle(song) },
										onAddToQueue = { player.addToQueueSingle(song) },
										isStarred = liked[song.id] ?: (song.starredAt != null),
										download = download
									)
								}
								item(key = "footer") {
									Text(
										buildString {
											append(stringResource(Res.string.mix_page_fresh))
											if (shown.build.continues) {
												append(" ")
												append(stringResource(Res.string.mix_page_continues))
											}
										},
										style = MaterialTheme.typography.bodySmall,
										color = LocalContentColor.current.copy(alpha = 0.7f),
										textAlign = TextAlign.Center,
										modifier = Modifier.padding(horizontal = 31.dp, vertical = 16.dp)
									)
								}
							}
						}
					}
				}
			}
		}
	}

	selected?.let { song ->
		SongSheet(
			onDismissRequest = { viewModel.clearSelection() },
			song = song,
			starred = selectedLiked,
			onSetStarred = { viewModel.setLiked(it) },
			onStartRadio = { radioManager.startRadio(song) },
			onPlayNext = { player.playNextSingle(song) },
			onAddToQueue = { player.addToQueueSingle(song) },
			onTrackInfo = dropUnlessResumed { backStack.add(Screen.SongDetailScreen(song.id, song.coverArtId)) },
			onViewAlbum = song.albumId?.let { albumId ->
				dropUnlessResumed { backStack.add(Screen.CollectionDetail(albumId, "mix")) }
			},
			onAddToPlaylist = { addingToPlaylist = song },
			downloadStatus = allDownloads.find { it.songId == song.id }?.status ?: DownloadStatus.NOT_DOWNLOADED,
			onDownload = { viewModel.download(song) },
			onCancelDownload = { viewModel.cancelDownload(song) },
			onDeleteDownload = { viewModel.deleteDownload(song) }
		)
	}

	addingToPlaylist?.let { song ->
		PlaylistUpdateDialog(songs = persistentListOf(song), onDismissRequest = { addingToPlaylist = null })
	}

	if (saving && songs.isNotEmpty()) {
		// The set on screen, as a playlist named after the mix; the new playlist opens once made.
		PlaylistCreateDialog(
			onDismissRequest = { saving = false },
			onRefresh = {},
			songs = songs.toImmutableList(),
			initialName = mix?.name.orEmpty()
		)
	}

	if (renaming && mix != null) {
		MixNameDialog(
			title = stringResource(Res.string.title_rename_mix),
			initial = mix.name,
			onConfirm = { name ->
				viewModel.rename(name)
				renaming = false
			},
			onDismissRequest = { renaming = false }
		)
	}

	if (deleting && mix != null) {
		FormDialog(
			onDismissRequest = { deleting = false },
			icon = { Icon(Icons.Outlined.Delete, null) },
			title = { Text(stringResource(Res.string.title_delete_mix)) },
			buttons = {
				SegmentedListButton(
					modifier = Modifier.fillMaxWidth(),
					onClick = {
						deleting = false
						// The page leaves when the hub's list no longer holds it.
						viewModel.delete()
					},
					shapes = SegmentedListButtonDefaults.shapes(index = 0, count = 2),
					colors = SegmentedListButtonDefaults.errorColors()
				) { Text(stringResource(Res.string.action_delete)) }
				SegmentedListButton(
					modifier = Modifier.fillMaxWidth(),
					onClick = { deleting = false },
					shapes = SegmentedListButtonDefaults.shapes(index = 1, count = 2)
				) { Text(stringResource(Res.string.action_cancel)) }
			},
			content = { Text(mix.name) }
		)
	}
}

/** The playlist page's heading for a mix: its face as a floating card, its name, what it is. */
@Composable
private fun MixHeading(mix: Mix, titleAlpha: Float) {
	val cardShape = ContinuousRoundedRectangle(18.dp)
	Box(
		modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 6.dp),
		contentAlignment = Alignment.Center
	) {
		val shadowAlpha = if (LocalContentColor.current.luminance() < 0.5f) 0.25f else 0.5f
		MixArtwork(
			mix = mix,
			modifier = Modifier
				.size(212.dp)
				.dropShadow(cardShape) {
					radius = 30.dp.toPx()
					color = Color.Black
					alpha = shadowAlpha * titleAlpha
				}
				.alpha(titleAlpha)
		)
	}
	Column(
		modifier = Modifier.padding(horizontal = 31.dp).padding(top = 10.dp, bottom = 8.dp).alpha(titleAlpha),
		horizontalAlignment = Alignment.CenterHorizontally
	) {
		Text(
			mix.name,
			style = MaterialTheme.typography.headlineSmall,
			textAlign = TextAlign.Center,
			maxLines = if (isCustomRadio(mix)) 3 else 2,
			overflow = TextOverflow.Ellipsis
		)
		Text(
			mixKindLabel(mix),
			color = LocalContentColor.current.copy(alpha = 0.7f),
			style = MaterialTheme.typography.bodySmall,
			textAlign = TextAlign.Center,
			fontFamily = defaultFont(grade = 100, round = 100f)
		)
	}
}

/** Shuffle, Play and Save as playlist, as the playlist page's Shuffle, Play and download sit. */
@Composable
private fun MixButtons(
	enabled: Boolean,
	onShuffle: () -> Unit,
	onPlay: () -> Unit,
	onSave: () -> Unit
) {
	Row(
		modifier = Modifier.padding(horizontal = 31.dp, vertical = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(10.dp, alignment = Alignment.CenterHorizontally)
	) {
		val shape = ContinuousCapsule
		val height = 44.dp
		val glass = ButtonDefaults.outlinedButtonColors(
			containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f)
		)
		OutlinedButton(
			modifier = Modifier.size(width = 52.dp, height = height),
			onClick = onShuffle,
			shape = shape,
			colors = glass,
			contentPadding = PaddingValues(0.dp),
			enabled = enabled
		) {
			Icon(Icons.Outlined.Shuffle, stringResource(Res.string.action_shuffle), Modifier.size(24.dp))
		}
		Button(
			modifier = Modifier.weight(1f).height(height),
			onClick = onPlay,
			shape = shape,
			enabled = enabled
		) {
			Icon(Icons.Filled.Play, null, modifier = Modifier.size(25.dp).padding(end = 3.dp))
			Text(
				stringResource(Res.string.action_play),
				maxLines = 1,
				autoSize = TextAutoSize.StepBased(minFontSize = 1.sp, maxFontSize = 15.sp),
				fontWeight = FontWeight.SemiBold,
				fontFamily = defaultFont(round = 100f)
			)
		}
		OutlinedButton(
			modifier = Modifier.size(width = 52.dp, height = height),
			onClick = onSave,
			shape = shape,
			colors = glass,
			contentPadding = PaddingValues(0.dp),
			enabled = enabled
		) {
			Icon(Icons.Outlined.PlaylistAdd, stringResource(Res.string.action_save_as_playlist), Modifier.size(24.dp))
		}
	}
}
