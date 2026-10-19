package paige.navic.ui.screens.nowPlaying.staged

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import com.kyant.capsule.ContinuousCapsule
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_lyrics
import navic.composeapp.generated.resources.action_navigate_back
import navic.composeapp.generated.resources.action_pause
import navic.composeapp.generated.resources.action_play
import navic.composeapp.generated.resources.action_save
import navic.composeapp.generated.resources.staged_nothing_up_next
import navic.composeapp.generated.resources.staged_open_queue
import navic.composeapp.generated.resources.staged_playing_from
import navic.composeapp.generated.resources.staged_playing_from_album
import navic.composeapp.generated.resources.staged_playing_from_playlist
import navic.composeapp.generated.resources.staged_remove_from_favorites
import navic.composeapp.generated.resources.staged_save_to_favorites
import navic.composeapp.generated.resources.staged_save_to_playlist
import navic.composeapp.generated.resources.staged_this_phone
import navic.composeapp.generated.resources.staged_up_next
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalSheetState
import paige.navic.domain.manager.HubManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.models.DomainSong
import paige.navic.domain.models.SavedQueueSource
import paige.navic.domain.models.creditText
import paige.navic.domain.models.settings.NowPlayingBackgroundStyle
import paige.navic.icons.Icons
import paige.navic.icons.filled.Favorite
import paige.navic.icons.filled.Pause
import paige.navic.icons.filled.Play
import paige.navic.icons.outlined.Devices
import paige.navic.icons.outlined.Favorite
import paige.navic.icons.outlined.KeyboardArrowDown
import paige.navic.icons.outlined.Lyrics
import paige.navic.icons.outlined.PlaylistAdd
import paige.navic.icons.outlined.Smartphone
import paige.navic.shared.MediaPlayerViewModel
import paige.navic.ui.components.common.BlendBackground
import paige.navic.ui.components.common.CoverArt
import paige.navic.ui.components.sheets.DevicePickerSheet
import paige.navic.ui.navigation.NowPlayingSheetController
import paige.navic.ui.navigation.Screen
import paige.navic.ui.navigation.rememberCoveredBySheet
import paige.navic.ui.screens.nowPlaying.components.controls.NowPlayingArtworkPager
import paige.navic.ui.screens.nowPlaying.components.controls.NowPlayingMoreButton
import paige.navic.ui.screens.nowPlaying.components.controls.NowPlayingProgressBar
import paige.navic.ui.screens.nowPlaying.components.rows.NowPlayingButtonsRow
import paige.navic.ui.screens.nowPlaying.components.rows.NowPlayingDurationsRow
import paige.navic.ui.screens.nowPlaying.components.rows.NowPlayingInfoRow
import paige.navic.ui.screens.nowPlaying.components.rows.NowPlayingTechnicalInfoRow
import paige.navic.ui.screens.nowPlaying.viewmodels.NowPlayingViewModel
import paige.navic.ui.screens.queue.QueueContent
import paige.navic.ui.screens.queue.QueueLayout
import paige.navic.ui.components.sheets.AddToPlaylistSheet
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/*
 * navi-connect P2: the player on a portrait phone, with the queue as a layer inside it
 * ([StagedQueueLayer]). One layout driven by one number: the player's pieces are placed once, at
 * rest, and moved to their compact places by the layer's position (`compaction`), so nothing is
 * cross-faded and any font size works (the compact block is measured, and its bottom is where the
 * half line goes). The cover scales about its top centre to 0.445 at half and 0.3, clear, at full,
 * where a mini header has taken the player's place. Landscape and wide windows keep the old player
 * and the queue sheet (`NowPlayingScreen`).
 */

/** Layer showing at peek above the navigation bar (the board's 110 dp with a 34 dp bar). */
private val PeekHeight = 76.dp

/** Gap between the compact controls and the half line. */
private val HalfGap = 26.dp

/** The least list a half place must leave showing above peek; less and there is no half. */
private val MinHalfList = 72.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StagedNowPlaying() {
	val preferenceManager = koinInject<PreferenceManager>()
	val player = koinInject<MediaPlayerViewModel>()
	val hubManager = koinInject<HubManager>()
	val backStack = LocalNavStack.current

	val currentScreen = backStack.lastOrNull()
	val isPlayerCurrent = currentScreen is Screen.NowPlaying
		|| currentScreen is Screen.Queue
		|| currentScreen is Screen.PlaybackSpeed
		|| currentScreen is Screen.SongDetailSheet
	val coveredBySheet = rememberCoveredBySheet()

	val playerState by player.steadyState.collectAsStateWithLifecycle()
	val playOrder by player.playOrder.collectAsState()
	val song = playerState.currentSong
	val viewModel = koinViewModel<NowPlayingViewModel> { parametersOf(player) }
	val songIsStarred by viewModel.songIsStarred.collectAsStateWithLifecycle()
	val songRating by viewModel.songRating.collectAsStateWithLifecycle()
	val playingFrom by viewModel.playingFrom.collectAsStateWithLifecycle()

	val isRemoteActive by hubManager.isRemoteActive.collectAsState()
	val devices by hubManager.devices.collectAsState()
	val activeDeviceId by hubManager.activeDeviceId.collectAsState()
	val remoteName = devices.firstOrNull { it.id == activeDeviceId }?.name
	var showDevicePicker by remember { mutableStateOf(false) }
	var savingToPlaylist by remember { mutableStateOf<DomainSong?>(null) }

	val sheetState = LocalSheetState.current
	val closeScope = rememberCoroutineScope()
	val animateToDismiss = dropUnlessResumed {
		closeScope.launch {
			sheetState.hide()
		}.invokeOnCompletion {
			if (!sheetState.isVisible) {
				backStack.remove(Screen.NowPlaying)
			}
		}
	}

	val density = LocalDensity.current
	val statusTop = WindowInsets.statusBars.getTop(density)
	val navBottom = WindowInsets.navigationBars.getBottom(density)
	// No "Casting to" line over the peek (you, 2026-10-19): the device pill says where it plays.
	val peekPx = with(density) { PeekHeight.toPx() } + navBottom
	val fullTopPx = with(density) { max(96.dp.toPx(), statusTop + 76.dp.toPx()) }
	val gapPx = with(density) { HalfGap.toPx() }
	val minHalfPx = with(density) { MinHalfList.toPx() }
	var containerPx by remember { mutableFloatStateOf(0f) }
	var compactBottomPx by remember { mutableFloatStateOf(Float.NaN) }
	val anchors by remember(peekPx, fullTopPx, gapPx, minHalfPx) {
		derivedStateOf {
			if (containerPx <= 0f || compactBottomPx.isNaN()) null
			else queueAnchors(containerPx, peekPx, fullTopPx, compactBottomPx, gapPx, minHalfPx)
		}
	}
	val queueState = rememberStagedQueueState { anchors }
	val compactionNow: () -> Compaction = {
		compaction(queueState.fraction, queueState.anchors?.halfFraction ?: NO_HALF_FRACTION)
	}
	// The mini header is clear up to here (miniHeaderAlpha), so composing it from here shows nothing new.
	val miniHeaderActive by remember { derivedStateOf { compactionNow().toFull > 0.5f } }

	val layerLayout = remember(backStack) {
		QueueLayout.Layer(
			detent = { queueState.targetDetent },
			// The player leaves with its hide animation, then the page is pushed (§7).
			onNavigateOut = { screen -> NowPlayingSheetController.requestHide { backStack.add(screen) } },
			hiddenBelowPx = {
				val a = queueState.anchors
				val top = queueState.offsetTop
				if (a == null || top.isNaN()) 0f else (top - a.fullTop).coerceAtLeast(0f)
			}
		)
	}

	Box(
		Modifier
			.fillMaxSize()
			.onSizeChanged { containerPx = it.height.toFloat() }
	) {
		if (preferenceManager.nowPlayingBackgroundStyle == NowPlayingBackgroundStyle.Dynamic) {
			BlendBackground(
				coverArtId = song?.coverArtId,
				// Held while the queue covers the player (SheetCover).
				isPaused = playerState.isPaused || coveredBySheet
			)
		}
		if (!isPlayerCurrent) return@Box

		val pop = rememberEntrancePop(200)
		StagedPlayerLayout(
			compaction = compactionNow,
			topInsetPx = statusTop,
			peekPx = peekPx,
			onCompactBottom = { if (it != compactBottomPx) compactBottomPx = it },
			header = {
				StagedHeader(
					playingFrom = playingFrom,
					onClose = animateToDismiss,
					songRating = songRating,
					onSetSongRating = { viewModel.rateSong(it) }
				)
			},
			cover = {
				NowPlayingArtworkPager(modifier = Modifier.fillMaxSize(), isLandscape = false)
			},
			info = {
				Box(pop.padding(horizontal = 8.dp)) {
					NowPlayingInfoRow(
						songIsStarred = songIsStarred,
						onSetSongIsStarred = { viewModel.starSong(it) },
						songRating = songRating,
						onSetSongRating = { viewModel.rateSong(it) },
						showMoreButton = false,
						showRemoteCue = false,
						// The star is the rating now (you, 2026-10-19); favourites are the Save pill's heart.
						trailing = {
							ExpandingRating(
								rating = songRating,
								onRate = { viewModel.rateSong(it) },
								songKey = song?.id,
								enabled = song != null
							)
						}
					)
				}
			},
			actions = {
				Row(
					modifier = pop.fillMaxWidth().padding(horizontal = 24.dp),
					horizontalArrangement = Arrangement.spacedBy(8.dp)
				) {
					ActionPill(
						icon = Icons.Outlined.Lyrics,
						label = stringResource(Res.string.action_lyrics),
						onClick = dropUnlessResumed { backStack.add(Screen.Lyrics) },
						modifier = Modifier.weight(1f)
					)
					Box(Modifier.weight(1f)) {
						SongSaveControl(
							song = song,
							favourite = songIsStarred,
							onSetFavourite = { viewModel.starSong(it) },
							onSaveToPlaylist = { savingToPlaylist = song }
						)
					}
					// Another device playing: the pill is inverted (filled, its words cut out of it), so
					// "not on this phone" reads at a glance.
					ActionPill(
						icon = if (isRemoteActive) Icons.Outlined.Devices else Icons.Outlined.Smartphone,
						label = if (isRemoteActive && remoteName != null) remoteName
						else stringResource(Res.string.staged_this_phone),
						onClick = { showDevicePicker = true },
						modifier = Modifier.weight(1f),
						inverted = isRemoteActive
					)
				}
			},
			progress = {
				Box(pop.padding(horizontal = 8.dp)) { NowPlayingProgressBar() }
			},
			durations = {
				Column(pop.padding(horizontal = 8.dp)) {
					NowPlayingDurationsRow()
					if (preferenceManager.nowPlayingSongInfo) NowPlayingTechnicalInfoRow()
				}
			},
			controls = {
				Box(pop.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
					NowPlayingButtonsRow()
				}
			}
		)

		// Composed only once it starts to show (it is fully clear until halfway from half to full): a
		// disabled button still takes the touches behind it, and the invisible play button sat on the
		// header's more button (seen on the AVD).
		if (miniHeaderActive) MiniHeader(
			song = song,
			isPaused = playerState.isPaused,
			topInsetPx = statusTop,
			alpha = { miniHeaderAlpha(compactionNow()) },
			onDevices = { showDevicePicker = true },
			onPlayPause = { if (isRemoteActive) hubManager.actPlayPause() else player.togglePlay() }
		)

		val next = peekRow(playerState.queue.map { it.id }, playerState.currentIndex, if (isRemoteActive) null else playOrder)
			?.let { playerState.queue.getOrNull(it) }
		StagedQueueLayer(
			state = queueState,
			peek = {
				PeekRow(
					next = next,
					onClick = { queueState.anchors?.let { queueState.launchMove(handleTapTarget(queueState.targetDetent, it)) } }
				)
			}
		) {
			QueueContent(layerLayout)
		}
	}

	if (showDevicePicker) {
		DevicePickerSheet(onDismissRequest = { showDevicePicker = false })
	}
	savingToPlaylist?.let { saving ->
		AddToPlaylistSheet(song = saving, onDismissRequest = { savingToPlaylist = null })
	}
}

/**
 * The Save pill (P2 notes, you, 2026-10-19): the playing song to favourites (a heart; the player's
 * star is the rating now) or to playlists ([AddToPlaylistSheet]). Its heart is filled while the song
 * is a favourite, the one place the player says so. Saving the QUEUE stays in the queue's header.
 */
@Composable
private fun SongSaveControl(
	song: DomainSong?,
	favourite: Boolean,
	onSetFavourite: (Boolean) -> Unit,
	onSaveToPlaylist: () -> Unit
) {
	var menuOpen by remember { mutableStateOf(false) }
	Box {
		ActionPill(
			icon = if (favourite) Icons.Filled.Favorite else Icons.Outlined.Favorite,
			label = stringResource(Res.string.action_save),
			onClick = { menuOpen = true },
			enabled = song != null,
			modifier = Modifier.fillMaxWidth()
		)
		DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
			DropdownMenuItem(
				text = {
					Text(stringResource(if (favourite) Res.string.staged_remove_from_favorites else Res.string.staged_save_to_favorites))
				},
				leadingIcon = { Icon(if (favourite) Icons.Filled.Favorite else Icons.Outlined.Favorite, null) },
				onClick = {
					menuOpen = false
					onSetFavourite(!favourite)
				}
			)
			DropdownMenuItem(
				text = { Text(stringResource(Res.string.staged_save_to_playlist)) },
				leadingIcon = { Icon(Icons.Outlined.PlaylistAdd, null) },
				onClick = {
					menuOpen = false
					onSaveToPlaylist()
				}
			)
		}
	}
}

/**
 * Places the player's pieces once at rest (header under the status bar, the cover as large as the
 * room allows up to the width less 16 dp, then title, actions, progress, durations, controls,
 * centred above the peek strip) and moves each to its compact place by
 * [compaction] in the placement layer: no re-measure while the layer moves. Reports where the
 * compact block ends ([onCompactBottom]) for the half line.
 *
 * Pieces that fade (actions, durations) are placed first, so a faded one never
 * takes a tap from one that shows over it.
 */
@Composable
private fun StagedPlayerLayout(
	compaction: () -> Compaction,
	topInsetPx: Int,
	peekPx: Float,
	onCompactBottom: (Float) -> Unit,
	header: @Composable () -> Unit,
	cover: @Composable () -> Unit,
	info: @Composable () -> Unit,
	actions: @Composable () -> Unit,
	progress: @Composable () -> Unit,
	durations: @Composable () -> Unit,
	controls: @Composable () -> Unit
) {
	Layout(
		contents = listOf(header, cover, info, actions, progress, durations, controls),
		modifier = Modifier.fillMaxSize()
	) { measurables, constraints ->
		val width = constraints.maxWidth
		val height = constraints.maxHeight
		val row = Constraints(minWidth = width, maxWidth = width)
		fun measure(i: Int) = measurables[i].firstOrNull()?.measure(row)
		val h = measure(0)
		val i = measure(2)
		val a = measure(3)
		val p = measure(4)
		val d = measure(5)
		val ct = measure(6)
		val hH = h?.height ?: 0
		val iH = i?.height ?: 0
		val aH = a?.height ?: 0
		val pH = p?.height ?: 0
		val dH = d?.height ?: 0
		val ctH = ct?.height ?: 0

		val gapAfterHeader = 8.dp.roundToPx()
		val gapAfterCover = 16.dp.roundToPx()
		val gapInfoActions = 4.dp.roundToPx()
		val gapActionsProgress = 8.dp.roundToPx()
		val gapBeforeControls = 16.dp.roundToPx()
		val gapAbovePeek = 12.dp.roundToPx()

		val peekTop = (height - peekPx).roundToInt()
		val restBottom = peekTop - gapAbovePeek
		val headerBottom = topInsetPx + hH
		val belowCover = gapAfterCover + iH + gapInfoActions + aH + gapActionsProgress + pH + dH + gapBeforeControls + ctH
		val room = restBottom - headerBottom - gapAfterHeader
		val maxSide = width - 16.dp.roundToPx()
		val side = min(maxSide, room - belowCover).coerceAtLeast(min(maxSide, 120.dp.roundToPx()))
		val c = measurables[1].firstOrNull()?.measure(Constraints.fixed(width, side))

		// At rest: the group from the cover to the controls, centred in the room under the header.
		val groupTop = headerBottom + gapAfterHeader + max(0, (room - side - belowCover) / 2)
		val coverTop = groupTop
		val infoTop = coverTop + side + gapAfterCover
		val actionsTop = infoTop + iH + gapInfoActions
		val progressTop = actionsTop + aH + gapActionsProgress
		val durationsTop = progressTop + pH
		val controlsTop = durationsTop + dH + gapBeforeControls

		// At half (the board's QueueHalf): the cover under the header at 0.445, then the title,
		// progress and controls closed up under it; actions and durations have gone.
		val compactCoverTop = headerBottom
		val compactInfoTop = compactCoverTop + (side * 0.445f).roundToInt() + 8.dp.roundToPx()
		val compactProgressTop = compactInfoTop + iH + 4.dp.roundToPx()
		val compactControlsTop = compactProgressTop + pH + 8.dp.roundToPx()
		onCompactBottom((compactControlsTop + ctH).toFloat())

		layout(width, height) {
			a?.placeWithLayer(0, actionsTop) {
				val k = compaction()
				translationY = (compactInfoTop + iH - actionsTop) * k.toHalf
				alpha = actionsAlpha(k)
			}
			d?.placeWithLayer(0, durationsTop) {
				val k = compaction()
				translationY = (compactProgressTop + pH - durationsTop) * k.toHalf
				alpha = actionsAlpha(k)
			}
			h?.placeWithLayer(0, topInsetPx) { alpha = headerAlpha(compaction()) }
			c?.placeWithLayer(0, coverTop) {
				val k = compaction()
				val s = coverScale(k)
				scaleX = s
				scaleY = s
				transformOrigin = TransformOrigin(0.5f, 0f)
				translationY = (compactCoverTop - coverTop) * k.toHalf
				alpha = coverAlpha(k)
			}
			i?.placeWithLayer(0, infoTop) {
				val k = compaction()
				translationY = (compactInfoTop - infoTop) * k.toHalf
				alpha = playbackAlpha(k)
			}
			p?.placeWithLayer(0, progressTop) {
				val k = compaction()
				translationY = (compactProgressTop - progressTop) * k.toHalf
				alpha = playbackAlpha(k)
			}
			ct?.placeWithLayer(0, controlsTop) {
				val k = compaction()
				translationY = (compactControlsTop - controlsTop) * k.toHalf
				alpha = playbackAlpha(k)
			}
		}
	}
}

/**
 * The controls' entrance pop (scale and rise after [delayMs]), the old player's, as one modifier
 * the pieces share. Applied inside each piece, so the compaction outside it never fights it.
 */
@Composable
private fun rememberEntrancePop(delayMs: Long): Modifier {
	var visible by rememberSaveable { mutableStateOf(false) }
	val scale by animateFloatAsState(if (visible) 1f else 0f)
	val offset by animateDpAsState(if (visible) 0.dp else 200.dp)
	LaunchedEffect(Unit) {
		delay(delayMs.milliseconds)
		visible = true
	}
	return Modifier
		.graphicsLayer {
			scaleX = scale
			scaleY = scale
		}
		.offset { IntOffset(0, offset.roundToPx()) }
}

/** Chevron (closes with the sheet's own hide), "Playing from" over the source, and the more button. */
@Composable
private fun StagedHeader(
	playingFrom: PlayingFrom?,
	onClose: () -> Unit,
	songRating: Int,
	onSetSongRating: (Int) -> Unit
) {
	Row(
		modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		IconButton(onClick = onClose) {
			Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = stringResource(Res.string.action_navigate_back))
		}
		Column(
			modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
			horizontalAlignment = Alignment.CenterHorizontally
		) {
			// Nothing known: nothing said, rather than a guess (playingFromText).
			playingFrom?.let { from ->
				Text(
					text = stringResource(
						when (from.kind) {
							SavedQueueSource.ALBUM -> Res.string.staged_playing_from_album
							SavedQueueSource.PLAYLIST -> Res.string.staged_playing_from_playlist
							else -> Res.string.staged_playing_from
						}
					),
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1
				)
				Text(
					text = from.name,
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.SemiBold,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
			}
		}
		Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
			NowPlayingMoreButton(songRating = songRating, onSetSongRating = onSetSongRating)
		}
	}
}

/**
 * One of the action row's pills (Lyrics, Save, the device). [inverted]: filled, with its icon and
 * words cut out of the fill so what is behind shows through them (the device pill while another
 * device plays).
 */
@Composable
private fun ActionPill(
	icon: ImageVector,
	label: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
	contentDescription: String? = null,
	inverted: Boolean = false
) {
	val onSurface = MaterialTheme.colorScheme.onSurface
	Surface(
		onClick = onClick,
		enabled = enabled,
		shape = ContinuousCapsule,
		color = if (inverted) Color.Transparent else onSurface.copy(alpha = 0.10f),
		contentColor = onSurface.copy(alpha = if (enabled) 1f else 0.38f),
		modifier = modifier.heightIn(min = 40.dp)
	) {
		Row(
			modifier = Modifier
				.then(
					if (inverted) Modifier
						// Its own layer, so the cut-out reaches only this pill's fill.
						.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
						.drawWithContent {
							drawRect(onSurface.copy(alpha = 0.78f))
							drawContent()
						}
					else Modifier
				)
				.padding(horizontal = 12.dp, vertical = 8.dp)
				.then(if (inverted) Modifier.graphicsLayer { blendMode = BlendMode.DstOut } else Modifier),
			horizontalArrangement = Arrangement.Center,
			verticalAlignment = Alignment.CenterVertically
		) {
			Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(18.dp))
			Spacer(Modifier.width(6.dp))
			Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
		}
	}
}

/** What the player becomes at full: the cover small, title and artist, the device and play/pause. */
@Composable
private fun MiniHeader(
	song: DomainSong?,
	isPaused: Boolean,
	topInsetPx: Int,
	alpha: () -> Float,
	onDevices: () -> Unit,
	onPlayPause: () -> Unit
) {
	val topInset = with(LocalDensity.current) { topInsetPx.toDp() }
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(top = topInset)
			.height(72.dp)
			.padding(horizontal = 16.dp)
			.graphicsLayer { this.alpha = alpha() },
		verticalAlignment = Alignment.CenterVertically
	) {
		CoverArt(
			coverArtId = song?.coverArtId,
			modifier = Modifier.size(56.dp),
			shape = RoundedCornerShape(10.dp)
		)
		Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
			Text(
				text = song?.title.orEmpty(),
				style = MaterialTheme.typography.titleMedium,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
			Text(
				text = song?.creditText.orEmpty(),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
		}
		IconButton(onClick = onDevices) {
			Icon(Icons.Outlined.Devices, contentDescription = null)
		}
		FilledIconButton(onClick = onPlayPause, enabled = song != null) {
			Icon(
				if (isPaused) Icons.Filled.Play else Icons.Filled.Pause,
				contentDescription = stringResource(if (isPaused) Res.string.action_play else Res.string.action_pause)
			)
		}
	}
}

/**
 * The peek strip: "Up next", the next song in play order ([peekRow]) and its cover, "Nothing up
 * next" at the end. The same whoever plays: it used to drop to one line without the cover while
 * casting. A tap opens the queue one place.
 */
@Composable
private fun PeekRow(next: DomainSong?, onClick: () -> Unit) {
	val upNext = stringResource(Res.string.staged_up_next)
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClickLabel = stringResource(Res.string.staged_open_queue), role = Role.Button, onClick = onClick)
			.padding(horizontal = 24.dp, vertical = 4.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		if (next == null) {
			Text(
				text = stringResource(Res.string.staged_nothing_up_next),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
			return@Row
		}
		CoverArt(coverArtId = next.coverArtId, modifier = Modifier.size(40.dp), shape = RoundedCornerShape(8.dp))
		Spacer(Modifier.width(12.dp))
		Column(Modifier.weight(1f)) {
			Text(upNext, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
			Text(
				text = buildString {
					append(next.title)
					append(" — ")
					append(next.creditText)
				},
				style = MaterialTheme.typography.bodyMedium,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
		}
	}
}
