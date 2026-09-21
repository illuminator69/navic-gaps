package paige.navic.ui.screens.external

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_find_sources
import navic.composeapp.generated.resources.action_view_artist
import navic.composeapp.generated.resources.info_external_album_index_failed
import navic.composeapp.generated.resources.title_missing_album
import navic.composeapp.generated.resources.title_tracklist
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import paige.navic.di.LocalNavStack
import paige.navic.domain.manager.LbBotManager
import paige.navic.ui.components.common.RemoteCoverArt
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.components.sheets.MissingAlbumSheet
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.external.viewmodels.ExternalAlbumViewModel

/**
 * A release the library does not have: its tracklist, and the way to get it.
 *
 * The download path is the *existing* two-step picker ([MissingAlbumSheet]) rather
 * than a second one built here — that sheet has already paid for the traps
 * (coverage measured against the canonical tracklist and not a file count, the
 * "is this even the right album" verdict, sending the resolved edition so lb-bot
 * doesn't overrule the choice and doesn't re-enter its MusicBrainz cooldown), and
 * a duplicate would drift off all of them.
 *
 * `presenceKnown` comes back false with no Navidrome album ids, which is the normal
 * case here. Every track is missing; that is not an error, and it must not render
 * as "0/12".
 */
@Composable
fun ExternalAlbumScreen(
	rgid: String,
	artistMbid: String,
	artistName: String,
	title: String,
	artistId: String = ""
) {
	val viewModel = koinViewModel<ExternalAlbumViewModel>(
		key = rgid,
		parameters = { parametersOf(rgid, artistMbid, artistName) }
	)
	val backStack = LocalNavStack.current
	val state by viewModel.state.collectAsStateWithLifecycle()
	var pickerOpen by remember(rgid) { mutableStateOf(false) }

	// The library turns out to hold this release-group after all — only lb-bot's
	// index knows which Navidrome album that is, so this can only be answered once
	// the index row has been read (or added).
	LaunchedEffect(state.ownedAlbumId) {
		state.ownedAlbumId?.let { albumId ->
			backStack.removeLastOrNull()
			backStack.add(Screen.CollectionDetail(albumId, "artist"))
		}
	}

	val heading = state.detail?.title?.ifBlank { null } ?: title
	val artist = state.detail?.artist?.ifBlank { null } ?: artistName

	Scaffold(
		topBar = { NestedTopBar({ Text(heading, maxLines = 1, overflow = TextOverflow.Ellipsis) }) }
	) { innerPadding ->
		Column(
			Modifier
				.padding(innerPadding)
				.fillMaxSize()
				.verticalScroll(rememberScrollState())
				.padding(horizontal = 16.dp)
		) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				RemoteCoverArt(
					url = LbBotManager.caaCoverUrl(rgid),
					modifier = Modifier.size(96.dp)
				)
				Column(Modifier.padding(start = 14.dp)) {
					Text(heading, style = MaterialTheme.typography.titleMedium, maxLines = 2)
					Text(
						listOf(artist, stringResource(Res.string.title_missing_album))
							.filter { it.isNotBlank() }
							.joinToString(" • "),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
				}
			}

			// The real artist page when whatever opened this knew the library has
			// the artist, the external one otherwise. This always opened the
			// external page, where an owned artist's every album reads "Added —
			// syncing" — the worse of the two answers, and the caller already knew
			// better.
			if (artistId.isNotBlank() || artistMbid.isNotBlank()) {
				Button(
					onClick = {
						backStack.add(
							if (artistId.isNotBlank()) {
								Screen.ArtistDetail(artistId)
							} else {
								Screen.ExternalArtist(artistMbid, artist)
							}
						)
					},
					modifier = Modifier.padding(top = 12.dp)
				) { Text(stringResource(Res.string.action_view_artist)) }
			}

			Button(
				onClick = { pickerOpen = true },
				modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
			) { Text(stringResource(Res.string.action_find_sources)) }

			// Not fatal, and worth saying: the album is still fully downloadable
			// through the picker above. What is lost is the index row, so this
			// release keeps listing as missing on the artist page until a rescan.
			if (state.indexAddFailed) {
				Text(
					stringResource(Res.string.info_external_album_index_failed),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(top = 8.dp)
				)
			}

			Text(
				stringResource(Res.string.title_tracklist),
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(top = 20.dp, bottom = 4.dp)
			)
			HorizontalDivider()

			if (state.loading) {
				Box(Modifier.fillMaxWidth().height(72.dp), Alignment.Center) {
					CircularProgressIndicator(Modifier.size(24.dp))
				}
			} else {
				// No `present` counting anywhere: presenceKnown is false here by
				// definition, so every row is just the canonical tracklist.
				state.tracklist?.tracks.orEmpty().forEach { track ->
					Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
						Text(
							track.position.toString(),
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.size(width = 28.dp, height = 20.dp)
						)
						Text(
							track.title,
							style = MaterialTheme.typography.bodyMedium,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis
						)
					}
				}
			}
		}
	}

	if (pickerOpen) {
		MissingAlbumSheet(
			release = viewModel.releaseForPicker(title),
			onDismissRequest = { pickerOpen = false }
		)
	}
}
