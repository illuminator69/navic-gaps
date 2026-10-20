package paige.navic.ui.screens.starred

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.capsule.ContinuousCapsule
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_play
import navic.composeapp.generated.resources.action_shuffle
import navic.composeapp.generated.resources.count_songs
import navic.composeapp.generated.resources.library_liked
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import paige.navic.di.LocalBottomBarScrollManager
import paige.navic.di.LocalPlatformContext
import paige.navic.di.isLandscape
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.models.DomainFilter
import paige.navic.domain.models.DomainSongListType
import paige.navic.domain.models.settings.BottomBarVisibilityMode
import paige.navic.icons.Icons
import paige.navic.icons.filled.Play
import paige.navic.icons.outlined.Shuffle
import paige.navic.shared.MediaPlayerViewModel
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.components.layouts.RootBottomBar
import paige.navic.ui.screens.library.LikedArt
import paige.navic.ui.screens.song.SongListBody
import paige.navic.ui.screens.song.SongListSortAction
import paige.navic.ui.screens.song.viewmodels.SongListViewModel
import paige.navic.ui.theme.defaultFont

/**
 * Liked (the Library's pinned row; P5 round 2, the owner's note): every liked song in one list, in
 * the Library's rows, with Shuffle and Play above it. It was "Starred": a few songs behind a See
 * all, then the starred albums and artists, in rows of another look. Starred albums and artists
 * are the Albums and Artists chips' "starred" filter now. Liked is Subsonic's star underneath.
 */
@Composable
fun StarredScreen() {
	val platformContext = LocalPlatformContext.current
	val preferenceManager = koinInject<PreferenceManager>()
	val player = koinInject<MediaPlayerViewModel>()

	// The page's own, read on each opening: one kept in the persistent store (as "Starred" had it)
	// went on showing the songs liked when it was first read (seen on the AVD: Liked "1 song", the
	// page empty).
	val viewModel = koinViewModel<SongListViewModel>(
		key = "likedSongs",
		parameters = { parametersOf(DomainSongListType.FrequentlyPlayed, setOf(DomainFilter.Starred)) }
	)

	Scaffold(
		containerColor = Color.Transparent,
		topBar = {
			NestedTopBar(
				title = { Text(stringResource(Res.string.library_liked)) },
				actions = { SongListSortAction(viewModel, nested = true) }
			)
		},
		bottomBar = {
			val scrollManager = LocalBottomBarScrollManager.current
			val preferVisible = preferenceManager.bottomBarVisibilityMode == BottomBarVisibilityMode.AllScreens
			if (!platformContext.isLandscape() && preferVisible) {
				RootBottomBar(scrolled = scrollManager.isTriggered)
			}
		}
	) { inner ->
		SongListBody(
			viewModel = viewModel,
			contentPadding = inner,
			playFromList = true,
			header = { songs ->
				if (songs.isNotEmpty()) item(key = "liked-header") {
					LikedHeader(
						count = songs.size,
						onShuffle = { player.playNow(songs.shuffled(), 0) },
						onPlay = { player.playNow(songs, 0) }
					)
				}
			}
		)
	}
}

/** The heart, how many, and Shuffle and Play as a playlist page has them. */
@Composable
private fun LikedHeader(count: Int, onShuffle: () -> Unit, onPlay: () -> Unit) {
	Row(
		Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(10.dp)
	) {
		LikedArt(Modifier.size(44.dp), iconSize = 22.dp)
		Text(
			pluralStringResource(Res.plurals.count_songs, count, count),
			style = MaterialTheme.typography.titleSmall,
			modifier = Modifier.weight(1f)
		)
		OutlinedButton(
			modifier = Modifier.size(width = 52.dp, height = 44.dp),
			onClick = onShuffle,
			shape = ContinuousCapsule,
			colors = ButtonDefaults.outlinedButtonColors(
				containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f)
			),
			contentPadding = PaddingValues(0.dp)
		) {
			Icon(Icons.Outlined.Shuffle, stringResource(Res.string.action_shuffle), Modifier.size(24.dp))
		}
		Button(
			modifier = Modifier.height(44.dp),
			onClick = onPlay,
			shape = ContinuousCapsule
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
	}
}
