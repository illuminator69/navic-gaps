package paige.navic.ui.screens.nowPlaying.components.rows

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.info_not_playing
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.di.LocalNavStack
import paige.navic.data.database.dao.ArtistDao
import paige.navic.domain.manager.HubManager
import paige.navic.ui.navigation.NowPlayingSheetController
import paige.navic.ui.navigation.Screen
import paige.navic.domain.models.DomainExplicitStatus
import paige.navic.shared.MediaPlayerViewModel
import paige.navic.ui.components.common.MarqueeText
import paige.navic.ui.screens.nowPlaying.components.controls.NowPlayingMoreButton
import paige.navic.ui.screens.nowPlaying.components.controls.NowPlayingStarButton
import paige.navic.ui.util.InlineExplicitIconLarge
import paige.navic.ui.util.appendArtists
import paige.navic.util.CreditedArtist
import paige.navic.util.PlainArtistLinkStyles
import paige.navic.util.artistCreditsText
import paige.navic.util.creditedArtists
import paige.navic.domain.models.creditText

@Composable
fun NowPlayingInfoRow(
	songIsStarred: Boolean,
	onSetSongIsStarred: (Boolean) -> Unit,
	songRating: Int,
	onSetSongRating: (Int) -> Unit
) {
	val backStack = LocalNavStack.current
	val player = koinInject<MediaPlayerViewModel>()
	val playerState by player.steadyState.collectAsState()
	val song = playerState.currentSong
	// Spotify-style "Playing on <device>" cue when the session runs on another device.
	val hubManager = koinInject<HubManager>()
	val isRemoteActive by hubManager.isRemoteActive.collectAsState()
	val hubDevices by hubManager.devices.collectAsState()
	val hubActiveId by hubManager.activeDeviceId.collectAsState()
	val remoteDeviceName = hubDevices.firstOrNull { it.id == hubActiveId }?.name ?: "remote device"
	val artistDao = koinInject<ArtistDao>()
	var credits by remember { mutableStateOf<List<CreditedArtist>>(emptyList()) }
	LaunchedEffect(song?.id, song?.artistName) {
		credits = song?.let { creditedArtists(it, artistDao) }.orEmpty()
	}
	Row(
		modifier = Modifier
			.padding(horizontal = 16.dp)
			.padding(bottom = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp)
	) {
		Column(Modifier.weight(1f)) {
			if (isRemoteActive) {
				Text(
					"Playing on $remoteDeviceName",
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.primary
				)
			}
			song?.let { song ->
				MarqueeText(
					text = buildAnnotatedString {
						append(song.title)
						if (song.explicitStatus == DomainExplicitStatus.Explicit) {
							append(" ")
							appendInlineContent("InlineExplicitIcon")
						}
					},
					inlineContent = InlineExplicitIconLarge,
					modifier = Modifier.clickable(onClick = dropUnlessResumed {
						// Navigate by the SONG's album. This used to close the player first and
						// only then resolve `currentCollection`, bailing on the elvis when it was
						// null — exactly the remote case (the queue is a mirror of another device's
						// session, so there is no local collection), so the player shut instantly
						// and went nowhere. `currentCollection` was the wrong target anyway: it is
						// whatever the queue was started from, which can be a playlist or a
						// generated mix rather than this song's album.
						val albumId = song.albumId ?: return@dropUnlessResumed
						// Animated close, then navigate — see the artist line below.
						NowPlayingSheetController.requestHide {
							val lastScreen = backStack.lastOrNull()
							val isSameAlbum = lastScreen is Screen.CollectionDetail &&
								lastScreen.collectionId == albumId

							if (!isSameAlbum) backStack.add(Screen.CollectionDetail(albumId, ""))
						}
					}),
					style = MaterialTheme.typography.bodyLarge
						.copy(
							fontSize = MaterialTheme.typography.bodyLarge.fontSize * 1.1
						),
				)
			}
			// Every credited artist is its own tap target — "A feat. B" opens B's page when you
			// tap B, not A's. The joining text ("feat.", "&") is kept exactly as tagged; see
			// [creditedArtists] for why the split is conservative.
			val artistStyle = MaterialTheme.typography.bodyMedium.copy(
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				fontSize = MaterialTheme.typography.bodyMedium.fontSize * 1.1
			)
			val notPlaying = stringResource(Res.string.info_not_playing)
			if (song == null || credits.isEmpty()) {
				MarqueeText(
					style = artistStyle,
					text = song?.artistName ?: notPlaying
				)
			} else {
				val openArtist: (String) -> Unit = { id ->
					// Close the player WITH its animation and navigate once it's gone. Removing
					// the sheet entry and pushing in the same frame destroyed it outright, which
					// is why this used to snap straight to the artist page.
					NowPlayingSheetController.requestHide {
						backStack.add(Screen.ArtistDetail(id))
					}
				}
				MarqueeText(
					style = artistStyle,
					// Drag to read: an auto-scrolling line slides the artist you are aiming at out
					// from under your finger, which made a featured name unhittable here. Only
					// this line — everything else in the app keeps the marquee.
					manualScroll = true,
					text = artistCreditsText(
						display = song.creditText,
						credits = credits,
						linkStyles = PlainArtistLinkStyles,
						onClick = openArtist
					)
				)
			}
		}
		Row(
			horizontalArrangement = Arrangement.spacedBy(10.dp)
		) {
			NowPlayingStarButton(
				songIsStarred = songIsStarred,
				onSetSongIsStarred = onSetSongIsStarred
			)
			NowPlayingMoreButton(
				songRating = songRating,
				onSetSongRating = onSetSongRating
			)
		}
	}
}
