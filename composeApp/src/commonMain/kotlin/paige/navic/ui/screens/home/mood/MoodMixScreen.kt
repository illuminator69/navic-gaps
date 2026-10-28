package paige.navic.ui.screens.home.mood

import androidx.compose.foundation.background
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
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import com.kyant.capsule.ContinuousCapsule
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_play
import navic.composeapp.generated.resources.action_retry
import navic.composeapp.generated.resources.action_save_as_playlist
import navic.composeapp.generated.resources.action_shuffle
import navic.composeapp.generated.resources.mood_mix_fresh
import navic.composeapp.generated.resources.mood_mix_from_library
import navic.composeapp.generated.resources.mood_mix_hours_minutes
import navic.composeapp.generated.resources.mood_mix_kind
import navic.composeapp.generated.resources.mood_mix_minutes
import navic.composeapp.generated.resources.mood_mix_gone
import navic.composeapp.generated.resources.mood_mix_short
import navic.composeapp.generated.resources.mood_mix_short_length
import navic.composeapp.generated.resources.mood_mix_songs
import navic.composeapp.generated.resources.mood_unavailable_body
import navic.composeapp.generated.resources.mood_unavailable_title
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import paige.navic.data.database.entities.DownloadStatus
import paige.navic.di.LocalBottomBarScrollManager
import paige.navic.di.LocalNavStack
import paige.navic.domain.manager.MixState
import paige.navic.domain.manager.MoodVariant
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.RadioManager
import paige.navic.domain.models.DomainSong
import paige.navic.domain.models.settings.BottomBarVisibilityMode
import paige.navic.icons.Icons
import paige.navic.icons.filled.Play
import paige.navic.icons.outlined.Equalizer
import paige.navic.icons.outlined.PlaylistAdd
import paige.navic.icons.outlined.Shuffle
import paige.navic.shared.MediaPlayerViewModel
import paige.navic.ui.components.common.ContentUnavailable
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.components.layouts.RootBottomBar
import paige.navic.ui.components.sheets.SongSheet
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.collection.components.CollectionDetailScreenSongRow
import paige.navic.ui.screens.home.MoodArt
import paige.navic.ui.screens.playlist.dialogs.PlaylistCreateDialog
import paige.navic.ui.screens.playlist.dialogs.PlaylistUpdateDialog
import paige.navic.ui.theme.defaultFont
import paige.navic.ui.util.placeholderBrush
import kotlin.time.Duration

/** Placeholder rows while the draw is out, the rows' own height. */
private const val PLACEHOLDER_ROWS = 8

/**
 * A Home mood tile's mix (P7, board MixPage): the tile's art at 220 dp, its title, "Relax mix · 50
 * songs · 3 h 12 min · from your library" (the real count and length), Play, Shuffle and Save as
 * playlist, then the songs as a playlist's rows. Every opening is a new draw (`MoodMixViewModel`);
 * what is shown is what plays. Washed in the playing song's colours by App.kt, like every browsing page.
 *
 * States: placeholder rows while drawing; Short ("Only N songs of this mix are in your library yet.",
 * Retry) when the library holds under 20 of the tile's songs; Unavailable (board HomeMoodEmpty, Retry)
 * when the hub could not search.
 */
@Composable
fun MoodMixScreen(moodId: String, variantId: String) {
	val viewModel = koinViewModel<MoodMixViewModel>(
		key = "mood-$moodId-$variantId",
		parameters = { parametersOf(moodId, variantId) }
	)
	val preferenceManager = koinInject<PreferenceManager>()
	val player = koinInject<MediaPlayerViewModel>()
	val radioManager = koinInject<RadioManager>()
	val backStack = LocalNavStack.current

	val mood by viewModel.mood.collectAsStateWithLifecycle()
	val variant by viewModel.variant.collectAsStateWithLifecycle()
	val draw by viewModel.draw.collectAsStateWithLifecycle()
	val selected by viewModel.selected.collectAsStateWithLifecycle()
	val selectedLiked by viewModel.selectedLiked.collectAsStateWithLifecycle()
	val liked by viewModel.liked.collectAsStateWithLifecycle()
	val allDownloads by viewModel.allDownloads.collectAsStateWithLifecycle()
	val playerState by player.steadyState.collectAsStateWithLifecycle()

	val songs = if (draw.state is MixState.Ready) draw.songs else emptyList()
	var saving by remember { mutableStateOf(false) }
	var addingToPlaylist by remember { mutableStateOf<DomainSong?>(null) }
	val listState = rememberLazyListState()
	val title = variant?.title.orEmpty()
	// The bar's title comes in as the heading scrolls away, as on the mix page: at rest the art and
	// the heading already say it.
	val titleAlpha by remember {
		derivedStateOf {
			if (listState.firstVisibleItemIndex >= 1) return@derivedStateOf 1f
			val height = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }?.size?.toFloat() ?: 0f
			if (height <= 0f) 0f
			else ((listState.firstVisibleItemScrollOffset - height * 0.4f) / (height * 0.6f)).coerceIn(0f, 1f)
		}
	}

	Scaffold(
		topBar = {
			NestedTopBar(
				title = {
					Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.alpha(titleAlpha))
				}
			)
		},
		bottomBar = {
			val scrollManager = LocalBottomBarScrollManager.current
			if (preferenceManager.bottomBarVisibilityMode == BottomBarVisibilityMode.AllScreens) {
				RootBottomBar(scrolled = scrollManager.isTriggered)
			}
		}
	) { contentPadding ->
		LazyColumn(
			modifier = Modifier.fillMaxSize(),
			state = listState,
			horizontalAlignment = Alignment.CenterHorizontally,
			contentPadding = contentPadding
		) {
			item(key = "heading") {
				MoodMixHeading(
					variant = variant ?: MoodVariant(variantId, title),
					line = mixLine(mood?.label.orEmpty(), songs)
				)
			}
			item(key = "buttons") {
				MoodMixButtons(
					enabled = songs.isNotEmpty(),
					onShuffle = { viewModel.play(shuffle = true) },
					onPlay = { viewModel.play() },
					onSave = { saving = true }
				)
			}
			when (val state = draw.state) {
				MixState.Loading -> items(PLACEHOLDER_ROWS) { PlaceholderRow(it) }
				is MixState.Short -> item(key = "short") {
					MoodMixMessage(
						title = pluralStringResource(
							if (state.byLibrary) Res.plurals.mood_mix_short else Res.plurals.mood_mix_short_length,
							state.found, state.found
						),
						body = null,
						onRetry = { viewModel.retry() }
					)
				}
				MixState.Gone -> item(key = "gone") {
					MoodMixMessage(
						title = stringResource(Res.string.mood_mix_gone),
						body = null,
						onRetry = { viewModel.retry() }
					)
				}
				MixState.Unavailable -> item(key = "unavailable") {
					MoodMixMessage(
						title = stringResource(Res.string.mood_unavailable_title),
						body = stringResource(Res.string.mood_unavailable_body),
						onRetry = { viewModel.retry() }
					)
				}
				is MixState.Ready -> {
					itemsIndexed(songs, key = { index, song -> "${song.id}-$index" }) { index, song ->
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
							download = allDownloads.find { it.songId == song.id }
						)
					}
					item(key = "footer") {
						Text(
							stringResource(Res.string.mood_mix_fresh),
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
				dropUnlessResumed { backStack.add(Screen.CollectionDetail(albumId, "mood")) }
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
		// The set on screen, as a playlist named after the tile (the mix page's own Save).
		PlaylistCreateDialog(
			onDismissRequest = { saving = false },
			onRefresh = {},
			songs = songs.toImmutableList(),
			initialName = title
		)
	}
}

/** "Relax mix · 50 songs · 3 h 12 min · from your library"; before the draw, "Relax mix · from your library". */
@Composable
private fun mixLine(moodLabel: String, songs: List<DomainSong>): String {
	val parts = mutableListOf(stringResource(Res.string.mood_mix_kind, moodLabel))
	if (songs.isNotEmpty()) {
		parts += pluralStringResource(Res.plurals.mood_mix_songs, songs.size, songs.size)
		val total = songs.fold(Duration.ZERO) { acc, s -> acc + s.duration }.inWholeMinutes.toInt()
		parts += if (total >= 60) stringResource(Res.string.mood_mix_hours_minutes, total / 60, total % 60)
		else stringResource(Res.string.mood_mix_minutes, total)
	}
	parts += stringResource(Res.string.mood_mix_from_library)
	return parts.joinToString(" · ")
}

@Composable
private fun MoodMixHeading(variant: MoodVariant, line: String) {
	Box(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
		MoodArt(variant, Modifier.size(220.dp), corner = 18.dp, large = true)
	}
	Column(
		modifier = Modifier.padding(horizontal = 31.dp).padding(top = 10.dp, bottom = 8.dp),
		horizontalAlignment = Alignment.CenterHorizontally
	) {
		Text(
			variant.title,
			style = MaterialTheme.typography.headlineSmall,
			textAlign = TextAlign.Center,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis
		)
		Text(
			line,
			color = LocalContentColor.current.copy(alpha = 0.7f),
			style = MaterialTheme.typography.bodySmall,
			textAlign = TextAlign.Center,
			fontFamily = defaultFont(grade = 100, round = 100f)
		)
	}
}

/** Shuffle, Play and Save as playlist, as the mix page has them. */
@Composable
private fun MoodMixButtons(enabled: Boolean, onShuffle: () -> Unit, onPlay: () -> Unit, onSave: () -> Unit) {
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

/** A Short or Unavailable page: what happened, what still works, and Retry. */
@Composable
private fun MoodMixMessage(title: String, body: String?, onRetry: () -> Unit) {
	Column(
		Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(12.dp)
	) {
		ContentUnavailable(
			modifier = Modifier,
			icon = Icons.Outlined.Equalizer,
			label = title,
			description = body
		)
		OutlinedButton(onClick = onRetry, shape = ContinuousCapsule) {
			Text(stringResource(Res.string.action_retry))
		}
	}
}

/** A row's tinted box while the draw is out: the song row's height, a tint per position. */
@Composable
private fun PlaceholderRow(index: Int) {
	Box(
		Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 2.dp)
			.height(64.dp)
			.clip(MaterialTheme.shapes.medium)
			.background(placeholderBrush("mood-row-$index"))
	)
}
