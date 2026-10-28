package paige.navic.ui.screens.search.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.map
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.count_albums
import navic.composeapp.generated.resources.count_songs
import navic.composeapp.generated.resources.search_in_library_pill
import navic.composeapp.generated.resources.search_kind_album
import navic.composeapp.generated.resources.search_kind_artist
import navic.composeapp.generated.resources.search_kind_playlist
import navic.composeapp.generated.resources.search_kind_song
import navic.composeapp.generated.resources.search_not_in_library
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.domain.manager.LbAlbumCandidate
import paige.navic.domain.manager.LbArt
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbCachePolicy
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.models.DomainAlbum
import paige.navic.domain.models.DomainArtist
import paige.navic.domain.models.DomainPlaylist
import paige.navic.domain.models.DomainSong
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Error
import paige.navic.icons.outlined.Lock
import paige.navic.icons.outlined.Offline
import paige.navic.ui.components.common.CoverArt
import paige.navic.ui.components.common.LbArtImage
import paige.navic.ui.components.common.MarqueeText
import paige.navic.ui.components.common.RemoteCoverArt
import paige.navic.ui.components.common.SegmentedListItem
import paige.navic.ui.components.common.SegmentedListItemDefaults.segmentedShapes
import paige.navic.ui.components.common.SongRowDefaults
import paige.navic.ui.screens.search.ExternalArtist
import paige.navic.ui.util.COVER_FADE_MS
import paige.navic.ui.util.placeholderBrush

/*
 * P3: the Search page's rows. One shape for every row (the app's `SegmentedListItem`, 50 dp art, a
 * title and one "kind · detail" line), so the library half, the unowned half and the ghosts that
 * stand in for an answer still out are the same height and replace one another in place.
 */

/** The horizontal gutter and the gap between grouped rows, as the album page's song rows have. */
private val RowPadding = Modifier.padding(horizontal = 16.dp, vertical = 1.5.dp)

private val ArtSize = 50.dp

@Composable
private fun supporting(kind: String, detail: String?): String =
	listOfNotNull(kind, detail?.takeIf { it.isNotBlank() }).joinToString(" · ")

/** A library artist: round art, "Artist · N albums", and the "In library" pill. */
@Composable
fun SearchArtistRow(
	artist: DomainArtist,
	index: Int,
	count: Int,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
	modifier: Modifier = Modifier
) {
	SegmentedListItem(
		modifier = modifier.then(RowPadding),
		onClick = onClick,
		onLongClick = onLongClick,
		shapes = segmentedShapes(index, count),
		leadingContent = {
			CoverArt(
				coverArtId = artist.coverArtId,
				modifier = Modifier.size(ArtSize),
				isArtist = true,
				shape = CircleShape,
				crossfadeMs = COVER_FADE_MS,
				placeholderName = artist.name
			)
		},
		supportingContent = {
			Text(
				supporting(
					stringResource(Res.string.search_kind_artist),
					artist.albumCount.takeIf { it > 0 }?.let {
						pluralStringResource(Res.plurals.count_albums, it, it)
					}
				),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
		},
		trailingContent = { SearchPill(stringResource(Res.string.search_in_library_pill)) }
	) {
		MarqueeText(artist.name)
	}
}

/** A library album: "Album · Artist". */
@Composable
fun SearchAlbumRow(
	album: DomainAlbum,
	index: Int,
	count: Int,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
	modifier: Modifier = Modifier
) {
	val preferenceManager = koinInject<PreferenceManager>()
	SegmentedListItem(
		modifier = modifier.then(RowPadding),
		onClick = onClick,
		onLongClick = onLongClick,
		shapes = segmentedShapes(index, count),
		leadingContent = {
			CoverArt(
				coverArtId = album.coverArtId,
				modifier = Modifier.size(ArtSize),
				shape = preferenceManager.coverArtShape.decreasedShape,
				crossfadeMs = COVER_FADE_MS,
				placeholderName = album.name ?: album.id
			)
		},
		supportingContent = {
			Text(
				supporting(stringResource(Res.string.search_kind_album), album.artistName),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
		}
	) {
		MarqueeText(album.name.orEmpty())
	}
}

/** A library song: "Song · Artist", the explicit lock and the offline mark where they apply. */
@Composable
fun SearchSongRow(
	song: DomainSong,
	index: Int,
	count: Int,
	explicitLocked: Boolean,
	unavailableOffline: Boolean,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
	modifier: Modifier = Modifier
) {
	SegmentedListItem(
		modifier = modifier,
		onClick = onClick,
		onLongClick = onLongClick,
		shapes = segmentedShapes(index, count),
		leadingContent = {
			CoverArt(
				coverArtId = song.coverArtId,
				fallbackAlbumId = song.albumId,
				modifier = Modifier.size(ArtSize),
				shape = SongRowDefaults.CoverShape,
				crossfadeMs = COVER_FADE_MS,
				placeholderName = song.albumTitle ?: song.title
			)
		},
		supportingContent = {
			Text(
				supporting(stringResource(Res.string.search_kind_song), song.artistName),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
		},
		trailingContent = if (explicitLocked || unavailableOffline) {
			{
				Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
					if (explicitLocked) Icon(Icons.Outlined.Lock, null, Modifier.size(20.dp))
					if (unavailableOffline) Icon(Icons.Outlined.Offline, null, Modifier.size(20.dp))
				}
			}
		} else null
	) {
		MarqueeText(song.title)
	}
}

/** A playlist: "Playlist · N songs". */
@Composable
fun SearchPlaylistRow(
	playlist: DomainPlaylist,
	index: Int,
	count: Int,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
	modifier: Modifier = Modifier
) {
	val preferenceManager = koinInject<PreferenceManager>()
	SegmentedListItem(
		modifier = modifier.then(RowPadding),
		onClick = onClick,
		onLongClick = onLongClick,
		shapes = segmentedShapes(index, count),
		leadingContent = {
			CoverArt(
				coverArtId = playlist.coverArtId,
				modifier = Modifier.size(ArtSize),
				shape = preferenceManager.coverArtShape.decreasedShape,
				crossfadeMs = COVER_FADE_MS,
				placeholderName = playlist.name ?: playlist.id
			)
		},
		supportingContent = {
			Text(
				supporting(
					stringResource(Res.string.search_kind_playlist),
					pluralStringResource(Res.plurals.count_songs, playlist.songCount, playlist.songCount)
				),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
		}
	) {
		MarqueeText(playlist.name.orEmpty())
	}
}

/**
 * A MusicBrainz artist the library does not hold: "Artist · Not in your library", and MusicBrainz's
 * "(UK band)" note under it, the only thing that tells two artists of one name apart. The picture is
 * one lb-bot already holds for that id (its cached About), read from Room with no request; else the
 * generic portrait.
 */
@Composable
fun ExternalArtistRow(
	artist: ExternalArtist,
	index: Int,
	count: Int,
	onClick: () -> Unit,
	modifier: Modifier = Modifier
) {
	val lbBot = koinInject<LbBotManager>()
	val mbid = artist.candidate.mbid
	val image by remember(mbid) {
		lbBot.artistMeta(mbid, null, LbCachePolicy.CACHE_ONLY).map { it?.imageUrl?.ifBlank { null } }
	}.collectAsState(null)
	SegmentedListItem(
		modifier = modifier.then(RowPadding),
		onClick = onClick,
		shapes = segmentedShapes(index, count),
		leadingContent = {
			val url = image
			if (url != null) {
				LbArtImage(art = LbArt.Remote(url), modifier = Modifier.size(ArtSize), shape = CircleShape)
			} else {
				// No picture lb-bot holds: the name's tint, as every placeholder is (P1), not an empty box.
				CoverArt(
					coverArtId = null,
					modifier = Modifier.size(ArtSize),
					isArtist = true,
					shape = CircleShape,
					placeholderName = artist.candidate.name
				)
			}
		},
		supportingContent = {
			Column {
				Text(
					supporting(stringResource(Res.string.search_kind_artist), stringResource(Res.string.search_not_in_library)),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
				artist.candidate.disambiguation.takeIf { it.isNotBlank() }?.let {
					Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
				}
			}
		}
	) {
		MarqueeText(artist.candidate.name)
	}
}

/**
 * A MusicBrainz album: "Album · Artist · year". An owned one (lb-bot's release-group index) says so
 * in place of the year and opens the library album, as it always has.
 */
@Composable
fun ExternalAlbumRow(
	album: LbAlbumCandidate,
	index: Int,
	count: Int,
	ownedLabel: String?,
	onClick: () -> Unit,
	modifier: Modifier = Modifier
) {
	val preferenceManager = koinInject<PreferenceManager>()
	SegmentedListItem(
		modifier = modifier.then(RowPadding),
		onClick = onClick,
		shapes = segmentedShapes(index, count),
		leadingContent = {
			RemoteCoverArt(
				url = album.coverUrl.takeIf { it.isNotBlank() },
				modifier = Modifier.size(ArtSize),
				shape = preferenceManager.coverArtShape.decreasedShape,
				crossfadeMs = COVER_FADE_MS,
				placeholderName = album.title
			)
		},
		supportingContent = {
			Text(
				listOfNotNull(
					stringResource(Res.string.search_kind_album),
					album.artist.takeIf { it.isNotBlank() },
					ownedLabel ?: album.year.takeIf { it.isNotBlank() }
				).joinToString(" · "),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
		}
	) {
		MarqueeText(album.title)
	}
}

/** What stands in for a row whose answer is still out: the same size, the row's tint, no words. */
@Composable
fun GhostRow(index: Int, count: Int, seed: String, modifier: Modifier = Modifier) {
	val bar = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
	SegmentedListItem(
		modifier = modifier.then(RowPadding),
		onClick = {},
		enabled = false,
		shapes = segmentedShapes(index, count),
		colors = paige.navic.ui.components.common.SegmentedListItemDefaults.segmentedColors(
			disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer
		),
		leadingContent = {
			Box(
				Modifier
					.size(ArtSize)
					.clip(MaterialTheme.shapes.small)
					.background(placeholderBrush(seed))
			)
		},
		supportingContent = {
			Box(Modifier.padding(top = 4.dp).width(96.dp).height(12.dp).clip(MaterialTheme.shapes.extraSmall).background(bar))
		}
	) {
		Box(Modifier.padding(vertical = 4.dp).width(160.dp).height(14.dp).clip(MaterialTheme.shapes.extraSmall).background(bar))
	}
}

/** A small rounded label at the end of a row or a header. With [onClick] it is a button. */
@Composable
fun SearchPill(
	text: String,
	modifier: Modifier = Modifier,
	busy: Boolean = false,
	failed: Boolean = false,
	onClick: (() -> Unit)? = null,
	clickLabel: String? = null
) {
	val container = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
	val content = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
	Row(
		modifier = modifier
			.clip(CircleShape)
			.background(container)
			.then(
				if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = clickLabel, onClick = onClick)
				else Modifier
			)
			.padding(horizontal = 10.dp, vertical = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(6.dp)
	) {
		if (busy) {
			CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = content)
		}
		if (failed) {
			Icon(Icons.Outlined.Error, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
		}
		Text(text, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
	}
}

/**
 * One browse card: a panel tinted from its name (no fixed colours in a component), its title and one
 * line under it. [fullWidth] cards are wider, not taller.
 */
@Composable
fun BrowseTileCard(
	title: String,
	line: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier
) {
	Box(
		modifier = modifier
			.fillMaxWidth()
			// At least, not exactly: at font scale 2.0 the two lines are taller than 92 dp (W-07).
			.heightIn(min = 92.dp)
			.clip(MaterialTheme.shapes.large)
			.background(placeholderBrush(title))
			.clickable(role = Role.Button, onClick = onClick)
			.semantics { contentDescription = "$title. $line" }
			.padding(14.dp)
	) {
		Column(Modifier.align(Alignment.BottomStart)) {
			Text(
				title,
				style = MaterialTheme.typography.titleMediumEmphasized,
				color = Color.White,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
			Spacer(Modifier.height(2.dp))
			Text(
				line,
				style = MaterialTheme.typography.bodySmall,
				color = Color.White.copy(alpha = 0.85f),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
		}
	}
}

/**
 * A section's title on the Search page, the whole row wide. `GroupHeader` shares its row with a
 * spacer, which gave "Downloaded on this device" half the width and cut it short (seen on the AVD).
 */
@Composable
fun SearchSectionHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
	Row(
		modifier = modifier
			.fillMaxWidth()
			.padding(start = 20.dp, end = 16.dp, top = 12.dp)
			.height(48.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(12.dp)
	) {
		Text(
			title,
			style = MaterialTheme.typography.titleMediumEmphasized,
			fontWeight = androidx.compose.ui.text.font.FontWeight(600),
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier
				.weight(1f)
				.semantics { heading() }
		)
		trailing()
	}
}
