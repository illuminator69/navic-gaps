package paige.navic.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.banner_sync_failed_cached
import navic.composeapp.generated.resources.count_songs
import navic.composeapp.generated.resources.library_kind_album
import navic.composeapp.generated.resources.library_kind_album_bare
import navic.composeapp.generated.resources.library_kind_artist
import navic.composeapp.generated.resources.library_kind_playlist
import navic.composeapp.generated.resources.library_kind_saved_queue
import navic.composeapp.generated.resources.library_liked
import navic.composeapp.generated.resources.library_pinned
import navic.composeapp.generated.resources.library_recents_empty
import navic.composeapp.generated.resources.library_recents_empty_hint
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import paige.navic.data.database.entities.SavedQueueEntity
import paige.navic.di.LocalNavStack
import paige.navic.ui.navigation.Screen
import paige.navic.domain.models.DomainAlbum
import paige.navic.domain.models.DomainPlaylist
import paige.navic.domain.models.Mix
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Favorite
import paige.navic.icons.outlined.LibraryMusic
import paige.navic.ui.components.common.ContentUnavailable
import paige.navic.ui.components.common.CoverArt
import paige.navic.ui.components.common.SegmentedListItem
import paige.navic.ui.components.common.SegmentedListItemDefaults
import paige.navic.ui.components.common.SegmentedListItemDefaults.segmentedShapes
import paige.navic.ui.components.common.SongRowDefaults
import paige.navic.ui.screens.mixes.MixArtwork
import paige.navic.ui.screens.mixes.mixKindLabel
import paige.navic.ui.screens.savedqueues.savedQueueTitle
import paige.navic.ui.screens.savedqueues.trackCountLabel
import paige.navic.ui.util.arrive
import paige.navic.ui.util.rememberArrivalGate
import paige.navic.ui.util.withoutTop

/**
 * Recents (board Library): Liked pinned, then what was used last, one row each in the app's
 * segmented rows with 50 dp art (artists round). A tap opens the thing; a long press its sheet.
 * [entries] null: not read yet, so nothing is drawn (no "empty" before the first read).
 */
@Composable
fun LibraryRecents(
	entries: List<LibraryEntry>?,
	contentPadding: PaddingValues,
	listState: LazyListState,
	busyKey: String?,
	/** The last library sync failed: what is listed is the cached copy (as the old home said). */
	syncFailed: Boolean,
	onOpen: (LibraryEntry) -> Unit,
	onMenu: (LibraryEntry) -> Unit,
	modifier: Modifier = Modifier,
	scrollConnection: NestedScrollConnection? = null
) {
	val gate = rememberArrivalGate()
	val rows = entries ?: return
	LazyColumn(
		modifier = modifier
			.padding(top = contentPadding.calculateTopPadding())
			.fillMaxSize()
			.then(if (scrollConnection != null) Modifier.nestedScroll(scrollConnection) else Modifier)
			.padding(horizontal = 16.dp),
		state = listState,
		contentPadding = contentPadding.withoutTop().let {
			PaddingValues(top = 4.dp, bottom = it.calculateBottomPadding() + 24.dp)
		},
		verticalArrangement = if (rows.isEmpty()) Arrangement.Center
		else Arrangement.spacedBy(SegmentedListItemDefaults.SegmentedGap)
	) {
		if (syncFailed) {
			item(key = "sync-failed") {
				// An error row as Data & storage's danger zone draws them, leading to the sync's own
				// status and its manual trigger there.
				val backStack = LocalNavStack.current
				SegmentedListItem(
					onClick = { backStack.add(Screen.Settings.DataStorage) },
					shapes = segmentedShapes(index = 0, count = 1),
					colors = SegmentedListItemDefaults.segmentedErrorColors(),
					modifier = Modifier.padding(bottom = 8.dp),
					content = { Text(stringResource(Res.string.banner_sync_failed_cached)) }
				)
			}
		}
		if (rows.isEmpty() || rows.all { it.kind == EntryKind.Liked }) {
			itemsIndexed(rows, key = { _, it -> it.key }) { index, entry ->
				RecentRow(entry, segmentedShapes(index, rows.size), busy = false, onOpen, onMenu,
					Modifier.arrive(gate, entry.key, index).animateItem())
			}
			item(key = "empty") {
				ContentUnavailable(
					modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
					icon = Icons.Outlined.LibraryMusic,
					label = stringResource(Res.string.library_recents_empty),
					description = stringResource(Res.string.library_recents_empty_hint)
				)
			}
			return@LazyColumn
		}
		itemsIndexed(rows, key = { _, it -> it.key }) { index, entry ->
			RecentRow(
				entry = entry,
				shapes = segmentedShapes(index, rows.size),
				busy = entry.key == busyKey,
				onOpen = onOpen,
				onMenu = onMenu,
				modifier = Modifier.arrive(gate, entry.key, index).animateItem()
			)
		}
	}
}

@Composable
private fun RecentRow(
	entry: LibraryEntry,
	shapes: androidx.compose.material3.ListItemShapes,
	busy: Boolean,
	onOpen: (LibraryEntry) -> Unit,
	onMenu: (LibraryEntry) -> Unit,
	modifier: Modifier = Modifier
) {
	SegmentedListItem(
		onClick = { onOpen(entry) },
		onLongClick = { onMenu(entry) }.takeIf { entry.kind != EntryKind.Liked },
		enabled = !busy,
		shapes = shapes,
		modifier = modifier,
		colors = ListItemDefaults.colors(
			containerColor = SongRowDefaults.containerColor(false),
			supportingContentColor = SongRowDefaults.supportingContentColor(false),
			disabledContainerColor = SongRowDefaults.containerColor(false)
		),
		verticalAlignment = Alignment.CenterVertically,
		contentPadding = SongRowDefaults.ContentPadding,
		leadingContent = {
			Box(Modifier.size(50.dp), contentAlignment = Alignment.Center) {
				when {
					busy -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
					entry.kind == EntryKind.Liked -> Box(
						Modifier.size(50.dp),
						contentAlignment = Alignment.Center
					) {
						androidx.compose.material3.Surface(
							modifier = Modifier.size(50.dp),
							shape = MaterialTheme.shapes.medium,
							color = MaterialTheme.colorScheme.primaryContainer
						) {
							Box(contentAlignment = Alignment.Center) {
								androidx.compose.material3.Icon(
									Icons.Outlined.Favorite,
									contentDescription = null,
									tint = MaterialTheme.colorScheme.onPrimaryContainer,
									modifier = Modifier.size(24.dp)
								)
							}
						}
					}
					entry.source is Mix -> MixArtwork(mix = entry.source, modifier = Modifier.size(50.dp))
					else -> CoverArt(
						modifier = Modifier.size(50.dp),
						coverArtId = entry.coverArtId,
						contentDescription = null,
						shape = if (entry.kind == EntryKind.Artist) CircleShape else null,
						isArtist = entry.kind == EntryKind.Artist,
						placeholderName = entry.title
					)
				}
			}
		},
		content = {
			Text(
				entryTitle(entry),
				maxLines = if (entry.kind == EntryKind.CustomRadio) 2 else 1,
				overflow = TextOverflow.Ellipsis
			)
		},
		supportingContent = {
			Text(entryLine(entry), maxLines = 1, overflow = TextOverflow.Ellipsis)
		}
	)
}

@Composable
private fun entryTitle(entry: LibraryEntry): String = when (val source = entry.source) {
	null -> if (entry.kind == EntryKind.Liked) stringResource(Res.string.library_liked) else entry.title
	is SavedQueueEntity -> savedQueueTitle(source)
	else -> entry.title
}

/** The row's line: what kind of thing it is, and its size or its artist. */
@Composable
private fun entryLine(entry: LibraryEntry): String = when (val source = entry.source) {
	is Mix -> mixKindLabel(source)
	is SavedQueueEntity -> stringResource(Res.string.library_kind_saved_queue, trackCountLabel(source.songCount))
	is DomainPlaylist -> stringResource(
		Res.string.library_kind_playlist,
		pluralStringResource(Res.plurals.count_songs, source.songCount, source.songCount)
	)
	is DomainAlbum -> when (entry.kind) {
		EntryKind.Artist -> stringResource(Res.string.library_kind_artist)
		else -> source.artistName?.takeIf { it.isNotBlank() }
			?.let { stringResource(Res.string.library_kind_album, it) }
			?: stringResource(Res.string.library_kind_album_bare)
	}
	else -> stringResource(
		Res.string.library_pinned,
		pluralStringResource(Res.plurals.count_songs, entry.count ?: 0, entry.count ?: 0)
	)
}
