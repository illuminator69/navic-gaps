package paige.navic.ui.components.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_add_to_another_playlist
import navic.composeapp.generated.resources.action_add_to_playlist
import navic.composeapp.generated.resources.action_add_to_queue
import navic.composeapp.generated.resources.action_cancel_download
import navic.composeapp.generated.resources.action_delete_download
import navic.composeapp.generated.resources.action_download
import navic.composeapp.generated.resources.action_play_next
import navic.composeapp.generated.resources.action_remove_from_playlist
import navic.composeapp.generated.resources.action_remove_star
import navic.composeapp.generated.resources.action_share
import navic.composeapp.generated.resources.action_sleep_timer
import navic.composeapp.generated.resources.action_sleep_timer_enabled
import navic.composeapp.generated.resources.action_star
import navic.composeapp.generated.resources.action_track_info
import navic.composeapp.generated.resources.action_view_album
import navic.composeapp.generated.resources.action_view_artist
import navic.composeapp.generated.resources.info_click_to_retry
import navic.composeapp.generated.resources.info_download_failed
import navic.composeapp.generated.resources.option_playback_speed
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.data.database.dao.ArtistDao
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalPlatformContext
import paige.navic.data.database.entities.DownloadStatus
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.SessionManager
import paige.navic.domain.manager.SleepTimerManager
import paige.navic.domain.manager.canUserShare
import paige.navic.domain.models.DomainAlbum
import paige.navic.domain.models.DomainExplicitStatus
import paige.navic.domain.models.DomainSong
import paige.navic.domain.models.DomainSongCollection
import paige.navic.icons.Icons
import paige.navic.icons.filled.Star
import paige.navic.icons.outlined.Album
import paige.navic.icons.outlined.Artist
import paige.navic.icons.outlined.Bedtime
import paige.navic.icons.outlined.ChevronForward
import paige.navic.icons.outlined.Close
import paige.navic.icons.outlined.Delete
import paige.navic.icons.outlined.Download
import paige.navic.icons.outlined.DownloadOff
import paige.navic.icons.outlined.Info
import paige.navic.icons.outlined.PlaylistAdd
import paige.navic.icons.outlined.PlaylistRemove
import paige.navic.icons.outlined.Queue
import paige.navic.icons.outlined.QueuePlayNext
import paige.navic.icons.outlined.Radio
import paige.navic.icons.outlined.Route
import paige.navic.icons.outlined.Share
import paige.navic.icons.outlined.Speed
import paige.navic.icons.outlined.Star
import paige.navic.ui.components.common.CoverArt
import paige.navic.ui.components.common.MarqueeText
import paige.navic.ui.components.common.RatingRow
import paige.navic.ui.navigation.Screen
import paige.navic.ui.theme.positive
import paige.navic.ui.util.InlineExplicitIcon
import paige.navic.ui.util.buildSongInfoString
import paige.navic.ui.util.label
import paige.navic.ui.util.rememberColorSchemeFromCoverArt
import paige.navic.ui.util.rememberCoverAmbient
import paige.navic.util.linkableArtists
import paige.navic.domain.models.DomainSongArtist

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SongSheet(
	onDismissRequest: () -> Unit,
	song: DomainSong,
	collection: DomainSongCollection? = null,
	starred: Boolean? = null,
	onSetStarred: ((Boolean) -> Unit)? = null,
	onShare: (() -> Unit)? = null,
	onStartRadio: (() -> Unit)? = null,
	onStartJourney: (() -> Unit)? = null,
	onPlayNext: (() -> Unit)? = null,
	onAddToQueue: (() -> Unit)? = null,
	onTrackInfo: (() -> Unit)? = null,
	onViewAlbum: (() -> Unit)? = null,
	onViewArtist: (() -> Unit)? = null,
	/**
	 * Open a SPECIFIC artist, for the chooser shown when a song credits more than one.
	 *
	 * Callers that live inside a sheet must pass this: [onViewArtist] usually closes its own host
	 * (the player, the queue) before navigating, and the chooser cannot know how. Without it the
	 * chooser falls back to a plain push, which would leave that host sitting over the destination.
	 */
	onViewArtistId: ((String) -> Unit)? = null,
	onAddToPlaylist: (() -> Unit)? = null,
	onRemoveFromPlaylist: (() -> Unit)? = null,
	downloadStatus: DownloadStatus? = null,
	onDownload: (() -> Unit)? = null,
	onCancelDownload: (() -> Unit)? = null,
	onDeleteDownload: (() -> Unit)? = null,
	rating: Int? = null,
	onSetRating: ((Int) -> Unit)? = null,
	showSleepTimer: Boolean = false,
	showPlaybackSpeed: Boolean = false
) {
	val preferenceManager = koinInject<PreferenceManager>()
	val sessionManager = koinInject<SessionManager>()

	val platformContext = LocalPlatformContext.current
	val backStack = LocalNavStack.current
	// Resolved here rather than by every caller: the sheet already has the song, and "View artist"
	// is ambiguous on a collaboration wherever it is shown. One artist behaves exactly as before.
	// song.artists is the server's own OpenSubsonic artists[]; only the ones this library
	// actually holds get linked (see linkableArtists).
	val artistDao = koinInject<ArtistDao>()
	var credits by remember { mutableStateOf<List<DomainSongArtist>>(emptyList()) }
	LaunchedEffect(song.id) { credits = linkableArtists(song.artists, artistDao) }
	var artistChooserShown by rememberSaveable { mutableStateOf(false) }
	var sleepTimerSheetShown by rememberSaveable { mutableStateOf(false) }
	val sleepTimerManager = koinInject<SleepTimerManager>()
	val sleepTimerLeft = sleepTimerManager.timeLeft
	val contentPadding = PaddingValues(horizontal = 16.dp)
	// Cover-scheme row colours (not the outer app/system theme) — see CollectionSheet.
	val ambient = rememberCoverAmbient(song.coverArtId)
	val colors = ListItemDefaults.colors(
		containerColor = Color.Transparent,
		headlineColor = ambient.scheme.onSurface,
		leadingIconColor = ambient.scheme.onSurfaceVariant,
		supportingColor = ambient.scheme.onSurfaceVariant,
		trailingIconColor = ambient.scheme.onSurface
	)

	ModalBottomSheet(
		onDismissRequest = onDismissRequest,
		dragHandle = null,
		sheetState = rememberModalBottomSheetState(true),
		ambient = ambient,
		contentWindowInsets = {
			BottomSheetDefaults.modalWindowInsets.add(
				WindowInsets(
					left = 8.dp,
					right = 8.dp
				)
			)
		}
	) {
		Spacer(Modifier.height(16.dp))

		ListItem(
			headlineContent = {
				MarqueeText(
					text = buildAnnotatedString {
						append(song.title)
						if (song.explicitStatus == DomainExplicitStatus.Explicit) {
							append(" ")
							appendInlineContent("InlineExplicitIcon")
						}
					},
					inlineContent = InlineExplicitIcon,
					style = MaterialTheme.typography.bodyLarge.copy(
						color = MaterialTheme.colorScheme.primary
					),
				)
			},
			supportingContent = {
				MarqueeText(
					"${song.albumTitle ?: ""} • ${song.artistName} • ${song.year ?: ""}"
				)
			},
			leadingContent = {
				CoverArt(
					coverArtId = song.coverArtId,
					modifier = Modifier.size(50.dp),
					shape = preferenceManager.coverArtShape.decreasedShape
				)
			},
			colors = colors
		)
		if (rating != null && onSetRating != null) {
			RatingRow(
				rating = rating,
				setRating = onSetRating
			)
			Spacer(Modifier.height(14.dp))
		}

		HorizontalDivider(Modifier.padding(horizontal = 8.dp, vertical = 2.dp))

		Column(Modifier.verticalScroll(rememberScrollState())) {
			if (onShare != null && sessionManager.canUserShare()) {
				ListItem(
					content = { Text(stringResource(Res.string.action_share)) },
					leadingContent = { Icon(Icons.Outlined.Share, null) },
					onClick = {
						platformContext.clickSound()
						onShare()
						onDismissRequest()
					},
					colors = colors,
					contentPadding = contentPadding
				)
			}

			if (starred != null && onSetStarred != null) {
				ListItem(
					content = {
						Text(stringResource(if (starred) Res.string.action_remove_star else Res.string.action_star))
					},
					leadingContent = {
						Icon(if (starred) Icons.Filled.Star else Icons.Outlined.Star, null)
					},
					onClick = {
						platformContext.clickSound()
						onSetStarred(!starred)
						onDismissRequest()
					},
					colors = colors,
					contentPadding = contentPadding
				)
			}

			if (downloadStatus != null) {
				when (downloadStatus) {
					DownloadStatus.DOWNLOADING -> {
						ListItem(
							content = { Text(stringResource(Res.string.action_cancel_download)) },
							leadingContent = { Icon(Icons.Outlined.Close, null) },
							onClick = {
								platformContext.clickSound()
								onCancelDownload?.invoke()
								onDismissRequest()
							},
							colors = colors,
							contentPadding = contentPadding
						)
					}

					DownloadStatus.DOWNLOADED -> {
						ListItem(
							content = { Text(stringResource(Res.string.action_delete_download)) },
							leadingContent = { Icon(Icons.Outlined.Delete, null) },
							onClick = {
								platformContext.clickSound()
								onDeleteDownload?.invoke()
								onDismissRequest()
							},
							colors = colors,
							contentPadding = contentPadding
						)
					}

					DownloadStatus.FAILED -> {
						ListItem(
							content = {
								Text(
									text = stringResource(Res.string.info_download_failed),
									color = MaterialTheme.colorScheme.error
								)
							},
							supportingContent = {
								Text(
									text = stringResource(Res.string.info_click_to_retry),
									color = MaterialTheme.colorScheme.error,
									style = MaterialTheme.typography.labelSmall
								)
							},
							leadingContent = {
								Icon(
									Icons.Outlined.DownloadOff,
									null,
									tint = MaterialTheme.colorScheme.error
								)
							},
							onClick = {
								platformContext.clickSound()
								onDownload?.invoke()
								onDismissRequest()
							},
							colors = colors,
							contentPadding = contentPadding
						)
					}

					else -> {
						ListItem(
							content = { Text(stringResource(Res.string.action_download)) },
							leadingContent = { Icon(Icons.Outlined.Download, null) },
							onClick = {
								platformContext.clickSound()
								onDownload?.invoke()
								onDismissRequest()
							},
							colors = colors,
							contentPadding = contentPadding
						)
					}
				}
			} else if (onDownload != null) {
				ListItem(
					content = { Text(stringResource(Res.string.action_download)) },
					leadingContent = { Icon(Icons.Outlined.Download, null) },
					onClick = {
						platformContext.clickSound()
						onDownload()
						onDismissRequest()
					},
					colors = colors,
					contentPadding = contentPadding
				)
			}

			if (onStartRadio != null) {
				ListItem(
					content = { Text("Start radio") },
					leadingContent = { Icon(Icons.Outlined.Radio, null) },
					onClick = {
						platformContext.clickSound()
						onStartRadio()
						onDismissRequest()
					},
					colors = colors,
					contentPadding = contentPadding
				)
			}

			if (onStartJourney != null) {
				ListItem(
					content = { Text("Journey to this song") },
					leadingContent = { Icon(Icons.Outlined.Route, null) },
					onClick = {
						platformContext.clickSound()
						onStartJourney()
						onDismissRequest()
					},
					colors = colors,
					contentPadding = contentPadding
				)
			}

			if (onPlayNext != null) {
				ListItem(
					content = { Text(stringResource(Res.string.action_play_next)) },
					leadingContent = { Icon(Icons.Outlined.QueuePlayNext, null) },
					onClick = {
						platformContext.clickSound()
						onPlayNext()
						onDismissRequest()
					},
					colors = colors,
					contentPadding = contentPadding
				)
			}

			if (onAddToQueue != null) {
				ListItem(
					content = { Text(stringResource(Res.string.action_add_to_queue)) },
					leadingContent = { Icon(Icons.Outlined.Queue, null) },
					onClick = {
						platformContext.clickSound()
						onAddToQueue()
						onDismissRequest()
					},
					colors = colors,
					contentPadding = contentPadding
				)
			}

			if (onAddToPlaylist != null) {
				ListItem(
					content = {
						Text(
							stringResource(
								if (collection != null && collection !is DomainAlbum)
									Res.string.action_add_to_another_playlist
								else Res.string.action_add_to_playlist
							)
						)
					},
					leadingContent = { Icon(Icons.Outlined.PlaylistAdd, null) },
					onClick = {
						platformContext.clickSound()
						onAddToPlaylist()
						onDismissRequest()
					},
					colors = colors,
					contentPadding = contentPadding
				)
			}

			if (onRemoveFromPlaylist != null && collection != null && collection !is DomainAlbum) {
				ListItem(
					content = { Text(stringResource(Res.string.action_remove_from_playlist)) },
					leadingContent = { Icon(Icons.Outlined.PlaylistRemove, null) },
					onClick = {
						platformContext.clickSound()
						onRemoveFromPlaylist()
						onDismissRequest()
					},
					colors = colors,
					contentPadding = contentPadding
				)
			}

			if (onViewAlbum != null) {
				ListItem(
					content = {
						Text(stringResource(Res.string.action_view_album))
					},
					leadingContent = { Icon(Icons.Outlined.Album, null) },
					onClick = {
						platformContext.clickSound()
						onViewAlbum()
						onDismissRequest()
					},
					colors = colors,
					contentPadding = contentPadding
				)
			}

			if (onViewArtist != null) {
				val severalArtists = credits.size > 1
				ListItem(
					content = { Text(stringResource(Res.string.action_view_artist)) },
					leadingContent = { Icon(Icons.Outlined.Artist, null) },
					// With two or more credited artists there is no single right answer, so ask
					// instead of silently picking the first. The sheet stays open behind the
					// chooser; a single artist keeps the direct, caller-supplied action.
					trailingContent = if (severalArtists) {
						{ Icon(Icons.Outlined.ChevronForward, null) }
					} else null,
					onClick = {
						platformContext.clickSound()
						if (severalArtists) {
							artistChooserShown = true
						} else {
							onViewArtist()
							onDismissRequest()
						}
					},
					colors = colors,
					contentPadding = contentPadding
				)
			}

			if (showSleepTimer) {
				if (sleepTimerLeft != null) {
					ListItem(
						content = {
							Text(
								stringResource(
									Res.string.action_sleep_timer_enabled,
									sleepTimerLeft.label()
								),
								color = MaterialTheme.colorScheme.positive
							)
						},
						leadingContent = {
							Icon(
								Icons.Outlined.Bedtime,
								null,
								tint = MaterialTheme.colorScheme.positive
							)
						},
						onClick = {
							platformContext.clickSound()
							sleepTimerSheetShown = true
						},
						colors = colors,
						contentPadding = contentPadding
					)
				} else {
					ListItem(
						content = {
							Text(
								stringResource(Res.string.action_sleep_timer)
							)
						},
						leadingContent = {
							Icon(
								Icons.Outlined.Bedtime,
								null
							)
						},
						onClick = {
							platformContext.clickSound()
							sleepTimerSheetShown = true
						},
						colors = colors,
						contentPadding = contentPadding
					)
				}
			}

			if (showPlaybackSpeed) {
				ListItem(
					content = {
						Text(
							stringResource(Res.string.option_playback_speed)
						)
					},
					leadingContent = {
						Icon(
							Icons.Outlined.Speed,
							null
						)
					},
					onClick = dropUnlessResumed {
						platformContext.clickSound()
						backStack.add(Screen.PlaybackSpeed)
					},
					colors = colors,
					contentPadding = contentPadding
				)
			}

			if (onTrackInfo != null) {
				ListItem(
					content = { Text(stringResource(Res.string.action_track_info)) },
					leadingContent = { Icon(Icons.Outlined.Info, null) },
					onClick = {
						platformContext.clickSound()
						onTrackInfo()
						onDismissRequest()
					},
					colors = colors,
					contentPadding = contentPadding
				)
			}
		}
	}

	if (sleepTimerSheetShown) {
		SleepTimerSheet(
			onDismissRequest = { confirmed ->
				sleepTimerSheetShown = false
				if (confirmed) {
					onDismissRequest()
				}
			}
		)
	}

	if (artistChooserShown) {
		// A second SHEET, not a dialog: this is a pick-one-of-a-list action reached from a sheet,
		// exactly like the sleep timer, and a centred alert box in the middle of that flow read as
		// borrowed from another app. Carries the same cover ambient as its parent so the two
		// surfaces match.
		//
		// Main artist first, then the guests — the order they are credited in, which is the order
		// song.artists returns them in credited order.
		ModalBottomSheet(
			onDismissRequest = { artistChooserShown = false },
			sheetState = rememberModalBottomSheetState(true),
			ambient = ambient,
			contentWindowInsets = {
				BottomSheetDefaults.modalWindowInsets.add(
					WindowInsets(left = 8.dp, right = 8.dp)
				)
			}
		) {
			Column(
				modifier = Modifier.verticalScroll(rememberScrollState())
			) {
				Text(
					text = stringResource(Res.string.action_view_artist),
					style = MaterialTheme.typography.titleLarge,
					color = ambient.scheme.onSurface,
					modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
				)
				credits.forEach { credit ->
					val id = credit.id ?: return@forEach
					ListItem(
						content = { Text(credit.name) },
						leadingContent = { Icon(Icons.Outlined.Artist, null) },
						onClick = {
							platformContext.clickSound()
							artistChooserShown = false
							onDismissRequest()
							onViewArtistId?.invoke(id) ?: backStack.add(Screen.ArtistDetail(id))
						},
						colors = colors,
						contentPadding = contentPadding
					)
				}
				Spacer(Modifier.height(8.dp))
			}
		}
	}
}
