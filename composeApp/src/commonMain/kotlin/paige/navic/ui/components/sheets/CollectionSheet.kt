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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_edit_rules
import navic.composeapp.generated.resources.action_refresh_rules
import navic.composeapp.generated.resources.auto_download_ellipsis
import navic.composeapp.generated.resources.action_add_to_playlist
import navic.composeapp.generated.resources.action_add_to_queue
import navic.composeapp.generated.resources.action_cancel_download
import navic.composeapp.generated.resources.action_delete
import navic.composeapp.generated.resources.action_delete_download
import navic.composeapp.generated.resources.action_download
import navic.composeapp.generated.resources.action_fix_album_match
import navic.composeapp.generated.resources.action_play_next
import navic.composeapp.generated.resources.action_refresh_about
import navic.composeapp.generated.resources.action_remove_star
import navic.composeapp.generated.resources.action_share
import navic.composeapp.generated.resources.action_star
import navic.composeapp.generated.resources.action_view_artist
import navic.composeapp.generated.resources.action_view_on_lastfm
import navic.composeapp.generated.resources.action_view_on_musicbrainz
import navic.composeapp.generated.resources.action_fill_gaps_count
import navic.composeapp.generated.resources.count_songs
import navic.composeapp.generated.resources.info_click_to_retry
import navic.composeapp.generated.resources.info_download_failed
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.di.LocalPlatformContext
import paige.navic.data.database.entities.DownloadStatus
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.SessionManager
import paige.navic.domain.manager.canUserShare
import paige.navic.domain.models.DomainAlbum
import paige.navic.domain.models.DomainAlbumInfo
import paige.navic.domain.models.DomainPlaylist
import paige.navic.domain.models.DomainSongCollection
import paige.navic.icons.outlined.Incomplete
import paige.navic.icons.outlined.Swap
import paige.navic.icons.Icons
import paige.navic.icons.brand.Lastfm
import paige.navic.icons.brand.Musicbrainz
import paige.navic.icons.filled.Star
import paige.navic.icons.outlined.Artist
import paige.navic.icons.outlined.Close
import paige.navic.icons.outlined.Delete
import paige.navic.icons.outlined.Download
import paige.navic.icons.outlined.Offline
import paige.navic.icons.outlined.Refresh
import paige.navic.icons.outlined.Sort
import paige.navic.icons.outlined.DownloadOff
import paige.navic.icons.outlined.PlaylistAdd
import paige.navic.icons.outlined.PlaylistRemove
import paige.navic.icons.outlined.Queue
import paige.navic.icons.outlined.QueuePlayNext
import paige.navic.icons.outlined.Share
import paige.navic.icons.outlined.Star
import paige.navic.ui.components.common.CoverArt
import paige.navic.ui.components.common.MarqueeText
import paige.navic.ui.components.common.RatingRow
import paige.navic.ui.util.rememberCoverAmbient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionSheet(
	onDismissRequest: () -> Unit,
	collection: DomainSongCollection?,
	albumInfo: DomainAlbumInfo? = null,
	onDownloadAll: (() -> Unit)? = null,
	onCancelDownloadAll: (() -> Unit)? = null,
	onDeleteDownloadAll: (() -> Unit)? = null,
	downloadStatus: DownloadStatus? = null,
	onShare: (() -> Unit)? = null,
	onPlayNext: (() -> Unit)? = null,
	onAddToQueue: (() -> Unit)? = null,
	onAddAllToPlaylist: (() -> Unit)? = null,
	onViewArtist: (() -> Unit)? = null,
	onViewOnLastFm: ((String) -> Unit)? = null,
	onViewOnMusicBrainz: ((String) -> Unit)? = null,
	starred: Boolean? = null,
	onSetStarred: ((Boolean) -> Unit)? = null,
	onDelete: (() -> Unit)? = null,
	rating: Int? = null,
	onSetRating: ((Int) -> Unit)? = null,
	onAutoDownload: (() -> Unit)? = null,
	// Only a SMART playlist has rules, and only Navidrome knows which is which —
	// `PlaylistEntity` has no `isSmart` column and the Subsonic sync that fills it
	// cannot see one. So the caller probes and passes null when there is nothing
	// to edit, exactly like every other optional action here.
	onEditRules: (() -> Unit)? = null,
	// Album-mode playlists only. Those are a SNAPSHOT — Navidrome's rules are
	// track-scoped, so collecting whole albums is worked out client-side — and
	// re-running the recipe is the only thing that brings them up to date.
	onRefreshRules: (() -> Unit)? = null,
	// lb-bot knows this album is missing tracks. Passed only from the artist
	// discography shelf, where the gap is known; null everywhere else, so the row
	// hides itself exactly like every other optional action here.
	missingTrackCount: Int? = null,
	onFillGaps: (() -> Unit)? = null,
	// Asks lb-bot to look the album's About up again. Passed only by the album page, and only
	// once it knows which release-group the album is — there is no About to refresh before that.
	onRefreshAbout: (() -> Unit)? = null,
	// Opens "Fix album match" (I-022): which MusicBrainz release this album is. Passed by the
	// callers that can host that sheet; the row itself shows only for an album the library
	// owns, and only while the hub offers the route and lb-bot has not turned out to predate it.
	onFixAlbumMatch: (() -> Unit)? = null
) {
	val preferenceManager = koinInject<PreferenceManager>()
	val lbBot = koinInject<LbBotManager>()
	val canFixAlbumMatch by lbBot.albumOverridesSupported.collectAsState()
	val platformContext = LocalPlatformContext.current
	val sessionManager = koinInject<SessionManager>()
	val contentPadding = PaddingValues(horizontal = 16.dp)
	// Build the row colours from the COVER scheme (not the outer app/system theme): these
	// are computed here, before the cover-themed sheet content, so referencing
	// MaterialTheme would pick up the system Material You colour instead of the album's.
	val ambient = rememberCoverAmbient(collection?.coverArtId)
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
		ambient = ambient,
		contentWindowInsets = { BottomSheetDefaults.modalWindowInsets.add(WindowInsets(
			left = 8.dp,
			right = 8.dp
		)) }
	) {
		Spacer(Modifier.height(16.dp))

		ListItem(
			leadingContent = {
				CoverArt(
					coverArtId = collection?.coverArtId,
					modifier = Modifier.size(50.dp),
					shape = preferenceManager.coverArtShape.decreasedShape
				)
			},
			headlineContent = {
				// Upstream's headline: the sheet's onSurface, not the accent (I-039).
				MarqueeText(collection?.name.orEmpty(), style = MaterialTheme.typography.bodyLarge)
			},
			supportingContent = {
				MarqueeText(
					listOfNotNull(
						(collection as? DomainAlbum)?.artistName,
						(collection as? DomainPlaylist)?.comment,
						(collection as? DomainAlbum)?.genre,
						(collection as? DomainAlbum)?.year,
						collection?.songCount?.let {
							pluralStringResource(Res.plurals.count_songs, it, it)
						}
					).joinToString(" • ")
				)
			},
			colors = colors
		)
		if (rating != null && onSetRating != null && preferenceManager.enableRatings) {
			RatingRow(
				rating = rating,
				setRating = onSetRating
			)
			Spacer(Modifier.height(14.dp))
		}

		HorizontalDivider(Modifier.padding(horizontal = 8.dp, vertical = 2.dp))

		Column(Modifier.verticalScroll(rememberScrollState())) {
			// First, because it is the only row here that says something is *wrong*
			// with the album rather than offering another thing to do with it.
			if (onFillGaps != null) {
				ListItem(
					content = {
						Text(
							pluralStringResource(
								Res.plurals.action_fill_gaps_count,
								missingTrackCount ?: 0,
								missingTrackCount ?: 0
							)
						)
					},
					// Not the download glyph: the album is here, and this completes it (I-039).
					leadingContent = { Icon(Icons.Outlined.Incomplete, null) },
					onClick = {
						platformContext.clickSound()
						onFillGaps()
						onDismissRequest()
					},
					colors = colors,
					contentPadding = contentPadding
				)
			}

			if (onViewOnLastFm != null && albumInfo?.lastFmUrl != null) {
				ListItem(
					content = { Text(stringResource(Res.string.action_view_on_lastfm)) },
					leadingContent = { Icon(Icons.Brand.Lastfm, null) },
					onClick = {
						platformContext.clickSound()
						onViewOnLastFm(albumInfo.lastFmUrl)
						onDismissRequest()
					},
					colors = colors,
					contentPadding = contentPadding
				)
			}

			if (onRefreshAbout != null) {
				ListItem(
					content = { Text(stringResource(Res.string.action_refresh_about)) },
					leadingContent = { Icon(Icons.Outlined.Refresh, null) },
					onClick = {
						platformContext.clickSound()
						onRefreshAbout()
						onDismissRequest()
					},
					colors = colors,
					contentPadding = contentPadding
				)
			}

			if (onViewOnMusicBrainz != null && albumInfo?.musicBrainzId != null) {
				ListItem(
					content = { Text(stringResource(Res.string.action_view_on_musicbrainz)) },
					leadingContent = { Icon(Icons.Brand.Musicbrainz, null) },
					onClick = {
						platformContext.clickSound()
						onViewOnMusicBrainz(albumInfo.musicBrainzId)
						onDismissRequest()
					},
					colors = colors,
					contentPadding = contentPadding
				)
			}

			if (onFixAlbumMatch != null && canFixAlbumMatch && lbBot.isConfigured &&
				collection is DomainAlbum && !collection.isExternal
			) {
				ListItem(
					content = { Text(stringResource(Res.string.action_fix_album_match)) },
					// Not View on MusicBrainz's logo, which it used to share: this changes
					// the match, that one leaves the app (I-039).
					leadingContent = { Icon(Icons.Outlined.Swap, null) },
					onClick = {
						platformContext.clickSound()
						onFixAlbumMatch()
						onDismissRequest()
					},
					colors = colors,
					contentPadding = contentPadding
				)
			}

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
					contentPadding = contentPadding,
					enabled = !collection?.songs.isNullOrEmpty()
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
					contentPadding = contentPadding,
					enabled = !collection?.songs.isNullOrEmpty()
				)
			}

			if (onAddAllToPlaylist != null) {
				ListItem(
					content = { Text(stringResource(Res.string.action_add_to_playlist)) },
					leadingContent = { Icon(Icons.Outlined.PlaylistAdd, null) },
					onClick = {
						platformContext.clickSound()
						onAddAllToPlaylist()
						onDismissRequest()
					},
					colors = colors,
					contentPadding = contentPadding,
					enabled = !collection?.songs.isNullOrEmpty()
				)
			}

			if (onViewArtist != null) {
				ListItem(
					content = { Text(stringResource(Res.string.action_view_artist)) },
					leadingContent = { Icon(Icons.Outlined.Artist, null) },
					onClick = {
						platformContext.clickSound()
						onViewArtist()
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
								onCancelDownloadAll?.invoke()
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
								onDeleteDownloadAll?.invoke()
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
								onDownloadAll?.invoke()
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
								onDownloadAll?.invoke()
								onDismissRequest()
							},
							colors = colors,
							contentPadding = contentPadding
						)
					}
				}
			} else if (onDownloadAll != null) {
				ListItem(
					content = { Text(stringResource(Res.string.action_download)) },
					leadingContent = { Icon(Icons.Outlined.Download, null) },
					onClick = {
						platformContext.clickSound()
						onDownloadAll()
						onDismissRequest()
					},
					colors = colors,
					contentPadding = contentPadding,
					enabled = !collection?.songs.isNullOrEmpty()
				)
			}

			if (onAutoDownload != null) {
				ListItem(
					content = { Text(stringResource(Res.string.auto_download_ellipsis)) },
					// Deliberately not `Download`, which is what the plain "Download"
					// row two entries up already uses — two rows a few pixels apart
					// under the same glyph read as one duplicated item.
					leadingContent = { Icon(Icons.Outlined.Offline, null) },
					onClick = {
						platformContext.clickSound()
						onAutoDownload()
						onDismissRequest()
					},
					colors = colors,
					contentPadding = contentPadding
				)
			}

			if (onEditRules != null) {
				ListItem(
					content = { Text(stringResource(Res.string.action_edit_rules)) },
					leadingContent = { Icon(Icons.Outlined.Sort, null) },
					onClick = {
						platformContext.clickSound()
						onEditRules()
						onDismissRequest()
					},
					colors = colors,
					contentPadding = contentPadding
				)
			}

			if (onRefreshRules != null) {
				ListItem(
					content = { Text(stringResource(Res.string.action_refresh_rules)) },
					leadingContent = { Icon(Icons.Outlined.Refresh, null) },
					onClick = {
						platformContext.clickSound()
						onRefreshRules()
						onDismissRequest()
					},
					colors = colors,
					contentPadding = contentPadding
				)
			}

			if (onDelete != null) {
				ListItem(
					content = { Text(stringResource(Res.string.action_delete)) },
					leadingContent = { Icon(Icons.Outlined.PlaylistRemove, null) },
					onClick = {
						platformContext.clickSound()
						onDelete()
						onDismissRequest()
					},
					colors = colors,
					contentPadding = contentPadding
				)
			}
		}
	}
}
