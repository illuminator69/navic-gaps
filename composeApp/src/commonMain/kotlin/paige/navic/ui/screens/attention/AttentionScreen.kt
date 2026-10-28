package paige.navic.ui.screens.attention

import paige.navic.ui.components.common.ROW_PLACEHOLDERS
import paige.navic.ui.components.common.RowPlaceholder
import org.jetbrains.compose.resources.StringResource
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_open_album
import navic.composeapp.generated.resources.action_open_in_lbbot
import navic.composeapp.generated.resources.attention_artists_to_check
import navic.composeapp.generated.resources.attention_artists_to_check_hint
import navic.composeapp.generated.resources.attention_choose_source
import navic.composeapp.generated.resources.attention_empty
import navic.composeapp.generated.resources.attention_failed
import navic.composeapp.generated.resources.attention_files
import navic.composeapp.generated.resources.attention_folder_hint
import navic.composeapp.generated.resources.attention_lbbot_only_hint
import navic.composeapp.generated.resources.attention_matched
import navic.composeapp.generated.resources.attention_needs_pick
import navic.composeapp.generated.resources.attention_nothing_to_fix
import navic.composeapp.generated.resources.attention_notice_older
import navic.composeapp.generated.resources.attention_notice_truncated
import navic.composeapp.generated.resources.attention_notice_unreachable
import navic.composeapp.generated.resources.attention_notice_unreachable_never
import navic.composeapp.generated.resources.attention_device_only
import navic.composeapp.generated.resources.attention_section_albums
import navic.composeapp.generated.resources.attention_section_to_fix
import navic.composeapp.generated.resources.attention_to_fix
import navic.composeapp.generated.resources.attention_tracks_missing
import navic.composeapp.generated.resources.attention_unidentified_folder
import navic.composeapp.generated.resources.title_needs_attention
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.di.LocalBottomBarScrollManager
import paige.navic.di.LocalNavStack
import paige.navic.di.LocalPlatformContext
import paige.navic.di.isLandscape
import paige.navic.domain.manager.AttentionFolder
import paige.navic.domain.manager.AttentionNotice
import paige.navic.domain.manager.AttentionPage
import paige.navic.domain.manager.AttentionRow
import paige.navic.domain.manager.LbFillEntry
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.manager.asLedgerRow
import paige.navic.domain.manager.folderDetail
import paige.navic.domain.manager.folderLine
import paige.navic.domain.manager.folderTitle
import paige.navic.domain.manager.formatListenedAt
import paige.navic.domain.models.settings.BottomBarVisibilityMode
import paige.navic.icons.Icons
import paige.navic.icons.brand.Musicbrainz
import paige.navic.icons.outlined.Check
import paige.navic.icons.outlined.Download
import paige.navic.icons.outlined.Link
import paige.navic.ui.components.common.ContentUnavailable
import paige.navic.ui.components.common.FillButton
import paige.navic.ui.components.common.GroupHeader
import paige.navic.ui.components.common.LibraryRowCover
import paige.navic.ui.components.common.LibraryRowDefaults
import paige.navic.ui.components.common.Pill
import paige.navic.ui.components.common.PillTone
import paige.navic.ui.components.common.RemoteCoverArt
import paige.navic.ui.components.common.SegmentedListItem
import paige.navic.ui.components.common.SegmentedListItemDefaults
import paige.navic.ui.components.common.SegmentedListItemDefaults.segmentedShapes
import paige.navic.ui.components.common.SongRowDefaults
import paige.navic.ui.components.common.StatePill
import paige.navic.ui.components.common.FillText
import paige.navic.ui.components.common.describeFill
import paige.navic.ui.components.common.fillRowColors
import paige.navic.ui.components.common.label
import paige.navic.ui.components.common.rememberFillClock
import paige.navic.ui.components.common.resolve
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.components.layouts.PullToRefreshBox
import paige.navic.ui.components.layouts.RootBottomBar
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.downloads.viewmodels.DownloadCenterViewModel
import paige.navic.ui.util.Fold
import paige.navic.ui.util.withoutTop

/**
 * Needs attention (P6): lb-bot's own "to fix", the count behind the avatar's dot, in two sections.
 *
 * *Albums*: failed gaps and failed whole-album requests, then the albums waiting on a pick. A tap
 * unfolds a row in place into the fill vocabulary's words; a row this device's ledger holds carries
 * the vocabulary's buttons (Retry, Try another source, Allow MP3, Add to wishlist, Dismiss through
 * the Downloads page's handlers), a row only lb-bot knows carries Open album alone. *To fix*: folders
 * waiting to be filed, as what they were identified as and how many files (never a name or a path),
 * each with "Open in lb-bot" only, since filing moves and retags files; then the artists to check,
 * which open Library Matches. Nothing here writes anything new to lb-bot.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AttentionScreen() {
	val viewModel = koinViewModel<AttentionViewModel>()
	// The row actions are the Downloads page's own (they run to completion if the page is left).
	val actions = koinViewModel<DownloadCenterViewModel>()
	val page by viewModel.page.collectAsStateWithLifecycle()
	val webUrl by viewModel.webUrl.collectAsStateWithLifecycle()
	val wishlisted by actions.wishlisted.collectAsStateWithLifecycle()
	val backStack = LocalNavStack.current
	val preferenceManager = koinInject<PreferenceManager>()
	val platformContext = LocalPlatformContext.current
	val uriHandler = LocalUriHandler.current
	val scope = rememberCoroutineScope()
	val now = rememberFillClock()

	var refreshing by remember { mutableStateOf(false) }
	// Which rows are unfolded, by row id: kept across a refresh and a rotation.
	var open by rememberSaveable { mutableStateOf(setOf<String>()) }

	val snackbar = remember { SnackbarHostState() }
	LaunchedEffect(Unit) { actions.actionMessages.collect { snackbar.showSnackbar(it) } }
	LaunchedEffect(Unit) { viewModel.refresh() }

	val openLb = { path: String -> if (webUrl.isNotBlank()) runCatching { uriHandler.openUri("$webUrl/#/$path") } }
	val openAlbum = { row: AttentionRow ->
		val a = row.album
		when {
			// A gap is an album the library partly has: its own page, with the gaps box open.
			a.kind == "gap" && a.albumId.isNotBlank() ->
				backStack.add(Screen.CollectionDetail(a.albumId, "attention", openGaps = true))
			a.rgid.isNotBlank() ->
				backStack.add(Screen.ExternalAlbum(rgid = a.rgid, artistName = a.artist, title = a.album))
			a.albumId.isNotBlank() -> backStack.add(Screen.CollectionDetail(a.albumId, "attention"))
			else -> Unit
		}
	}

	Scaffold(
		topBar = { NestedTopBar(title = { Text(stringResource(Res.string.title_needs_attention)) }) },
		bottomBar = {
			val scrollManager = LocalBottomBarScrollManager.current
			if (!platformContext.isLandscape() &&
				preferenceManager.bottomBarVisibilityMode == BottomBarVisibilityMode.AllScreens
			) {
				RootBottomBar(scrolled = scrollManager.isTriggered)
			}
		},
		snackbarHost = { SnackbarHost(snackbar) }
	) { innerPadding ->
		PullToRefreshBox(
			isRefreshing = refreshing,
			onRefresh = {
				scope.launch {
					refreshing = true
					viewModel.refresh().join()
					refreshing = false
				}
			},
			modifier = Modifier.padding(top = innerPadding.calculateTopPadding())
		) {
			when (val p = page) {
				// Waiting rows under the title's place, then lb-bot's rows (audit L1-05).
				AttentionPage.Loading -> Column(
					Modifier.fillMaxSize().padding(innerPadding.withoutTop()).padding(horizontal = 16.dp, vertical = 8.dp),
					verticalArrangement = Arrangement.spacedBy(SegmentedListItemDefaults.SegmentedGap)
				) {
					repeat(ROW_PLACEHOLDERS) { RowPlaceholder() }
				}
				is AttentionPage.Rows -> LazyColumn(
					modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
					contentPadding = innerPadding.withoutTop() + PaddingValues(top = 4.dp, bottom = 24.dp),
					verticalArrangement = Arrangement.spacedBy(SegmentedListItemDefaults.SegmentedGap)
				) {
					item(key = "head") {
						AttentionHead(p, Modifier.animateItem())
					}
					val toCheck = p.artistsToCheck?.takeIf { it > 0 }
					if (p.albums.isEmpty() && p.folders.isEmpty() && toCheck == null && p.deviceOnly.isEmpty()) {
						item(key = "empty") {
							ContentUnavailable(
								modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp).animateItem(),
								icon = Icons.Outlined.Check,
								label = stringResource(Res.string.attention_empty)
							)
						}
					}
					val albumRows: LazyListScope.(String, StringResource, List<AttentionRow>) -> Unit = { key, title, rows ->
						if (rows.isNotEmpty()) {
							item(key = "$key-header") {
								GroupHeader(stringResource(title), count = rows.size, modifier = Modifier.animateItem())
							}
							itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
								AlbumRow(
									row = row,
									now = now,
									shapes = segmentedShapes(index, rows.size),
									open = row.id in open,
									onToggle = { open = if (row.id in open) open - row.id else open + row.id },
									wishlistAvailable = actions.wishlistSupported && row.entry?.key !in wishlisted,
									onAction = { button, entry ->
										when (button) {
											FillButton.RETRY -> actions.retryFill(entry.key)
											FillButton.TRY_ANOTHER -> actions.retryAnotherSource(entry.key)
											FillButton.CANCEL -> actions.cancelFill(entry.key)
											FillButton.ALLOW_MP3 -> actions.allowMp3AndRetry(entry)
											FillButton.WISHLIST -> actions.addToWishlist(entry)
											FillButton.DISMISS -> actions.dismissFill(entry.key)
											FillButton.OPEN_ALBUM -> openAlbum(row)
										}
									},
									onOpenAlbum = { openAlbum(row) },
									modifier = Modifier.animateItem()
								)
							}
						}
					}
					albumRows("albums", Res.string.attention_section_albums, p.albums)
					val toFixRows = p.folders.size + (if (toCheck != null) 1 else 0)
					if (toFixRows > 0) {
						item(key = "fix-header") {
							GroupHeader(
								stringResource(Res.string.attention_section_to_fix),
								count = toFixRows,
								modifier = Modifier.animateItem()
							)
						}
						itemsIndexed(p.folders, key = { _, f -> "folder:${f.id}" }) { index, folder ->
							FolderRow(
								folder = folder,
								shapes = segmentedShapes(index, toFixRows),
								open = "folder:${folder.id}" in open,
								onToggle = {
									val id = "folder:${folder.id}"
									open = if (id in open) open - id else open + id
								},
								canOpenInLbBot = webUrl.isNotBlank(),
								onOpenInLbBot = { openLb("place/${folder.id}") },
								modifier = Modifier.animateItem()
							)
						}
						if (toCheck != null) {
							item(key = "artists-to-check") {
								SegmentedListItem(
									onClick = { backStack.add(Screen.LibraryMatches) },
									modifier = Modifier.animateItem(),
									shapes = segmentedShapes(toFixRows - 1, toFixRows),
									colors = fillRowColors(),
									contentPadding = SongRowDefaults.ContentPadding,
									verticalAlignment = Alignment.CenterVertically,
									leadingContent = {
										Box(Modifier.size(LibraryRowDefaults.ArtSize), contentAlignment = Alignment.Center) {
											Icon(Icons.Brand.Musicbrainz, contentDescription = null)
										}
									},
									supportingContent = {
										Text(stringResource(Res.string.attention_artists_to_check_hint), maxLines = 2)
									}
								) {
									Text(pluralStringResource(Res.plurals.attention_artists_to_check, toCheck, toCheck))
								}
							}
						}
					}
					// L4b-02: this phone's own rows lb-bot no longer lists, apart from lb-bot's and its count.
					albumRows("device-only", Res.string.attention_device_only, p.deviceOnly)
					if (webUrl.isNotBlank()) {
						item(key = "open-lb") {
							TextButton(
								onClick = { openLb("needs/fix") },
								modifier = Modifier.padding(top = 8.dp).animateItem()
							) {
								Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(18.dp))
								Text(stringResource(Res.string.action_open_in_lbbot), modifier = Modifier.padding(start = 8.dp))
							}
						}
					}
				}
			}
		}
	}
}

/** The title line ("20 to fix") and the one muted line of notices under it. */
@Composable
private fun AttentionHead(page: AttentionPage.Rows, modifier: Modifier = Modifier) {
	val unreachable = page.notices.any { it is AttentionNotice.Unreachable }
	Column(modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, bottom = 4.dp)) {
		val count = page.toFix
		// "Nothing to fix" is said only for an answer lb-bot just gave; a number from an older
		// answer is still said, with the notice saying when it was read.
		val headline = when {
			count == null -> null
			count == 0 && unreachable -> null
			count == 0 -> stringResource(Res.string.attention_nothing_to_fix)
			else -> stringResource(Res.string.attention_to_fix, count)
		}
		if (headline != null) {
			Text(headline, style = MaterialTheme.typography.titleMedium)
		}
		val zone = remember { TimeZone.currentSystemDefault() }
		val lines = page.notices.map { notice ->
			when (notice) {
				AttentionNotice.Older -> stringResource(Res.string.attention_notice_older)
				is AttentionNotice.Unreachable -> notice.since?.let {
					stringResource(Res.string.attention_notice_unreachable, formatListenedAt(it / 1000.0, zone))
				} ?: stringResource(Res.string.attention_notice_unreachable_never)
				is AttentionNotice.Truncated ->
					stringResource(Res.string.attention_notice_truncated, notice.albums + notice.folders)
			}
		}
		if (lines.isNotEmpty()) {
			Text(
				lines.joinToString(" "),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
		}
	}
}

/**
 * One album: art, name, artist, a pill. Unfolds in place into the vocabulary's words and buttons:
 * every button for a row the ledger holds, Open album alone for one only lb-bot knows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlbumRow(
	row: AttentionRow,
	now: State<Long>,
	shapes: ListItemShapes,
	open: Boolean,
	onToggle: () -> Unit,
	wishlistAvailable: Boolean,
	onAction: (FillButton, LbFillEntry) -> Unit,
	onOpenAlbum: () -> Unit,
	modifier: Modifier = Modifier
) {
	val a = row.album
	val preferenceManager = koinInject<PreferenceManager>()
	val entry = row.entry
	val view = describeFill(entry ?: a.asLedgerRow(), now.value, wishlistAvailable)
	// Dismiss only where it removes the row: one only this phone holds (L4b-09).
	val buttons = if (entry != null) view.buttons.filter { it != FillButton.DISMISS || row.dismissable }
	else listOf(FillButton.OPEN_ALBUM).filter { a.rgid.isNotBlank() || a.albumId.isNotBlank() }
	val pill = if (a.picking) Pill(FillText.Res(Res.string.attention_choose_source), PillTone.WARN)
	else Pill(FillText.Res(Res.string.attention_failed), PillTone.BAD)
	val stateWords = pill.label.resolve()
	SegmentedListItem(
		onClick = onToggle,
		modifier = modifier.semantics { stateDescription = stateWords },
		shapes = shapes,
		colors = fillRowColors(),
		contentPadding = SongRowDefaults.ContentPadding,
		leadingContent = {
			if (a.albumId.isNotBlank()) {
				LibraryRowCover(coverArtId = a.albumId, name = a.album, fallbackAlbumId = a.albumId)
			} else {
				RemoteCoverArt(
					url = a.coverUrl.ifBlank { null },
					modifier = Modifier.size(LibraryRowDefaults.ArtSize),
					shape = preferenceManager.coverArtShape.decreasedShape
				)
			}
		},
		trailingContent = { StatePill(pill) },
		supportingContent = {
			Column {
				val missing = if (a.kind == "gap" && a.missingCount > 0)
					pluralStringResource(Res.plurals.attention_tracks_missing, a.missingCount, a.missingCount) else null
				Text(
					listOfNotNull(a.artist.ifBlank { null }, missing).joinToString(" · "),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
				Fold(visible = open) {
					Column(Modifier.padding(top = 6.dp)) {
						Text(
							view.headline.resolve(),
							color = if (a.picking) MaterialTheme.colorScheme.onSurfaceVariant
							else MaterialTheme.colorScheme.error
						)
						view.sublines.forEach { line ->
							Text(line.resolve(), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
						}
						view.explain?.let { Text(it.resolve(), style = MaterialTheme.typography.bodySmall) }
						if (entry == null) {
							Text(
								stringResource(Res.string.attention_lbbot_only_hint),
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant
							)
						}
						if (buttons.isNotEmpty()) {
							FlowRow(
								modifier = Modifier.padding(top = 4.dp),
								horizontalArrangement = Arrangement.spacedBy(4.dp)
							) {
								buttons.forEach { button ->
									TextButton(onClick = {
										if (entry != null) onAction(button, entry) else onOpenAlbum()
									}) {
										Text(stringResource(if (entry == null) Res.string.action_open_album else button.label))
									}
								}
							}
						}
					}
				}
			}
		}
	) {
		Text(a.album.ifBlank { a.artist }.ifBlank { a.key }, maxLines = 1, overflow = TextOverflow.Ellipsis)
	}
}

/** A downloaded folder waiting to be filed: what it was identified as and how many files, nothing else. */
@Composable
private fun FolderRow(
	folder: AttentionFolder,
	shapes: ListItemShapes,
	open: Boolean,
	onToggle: () -> Unit,
	canOpenInLbBot: Boolean,
	onOpenInLbBot: () -> Unit,
	modifier: Modifier = Modifier
) {
	val unidentified = stringResource(Res.string.attention_unidentified_folder)
	val filesWord = @Composable { n: Int -> pluralStringResource(Res.plurals.attention_files, n, n) }
	val files = filesWord(folder.fileCount)
	val spoken = folderLine(folder, unidentified) { files }
	val matched = folder.identified && folder.matchLabel.isNotBlank()
	val pill = if (matched) Pill(FillText.Res(Res.string.attention_matched), PillTone.NEUTRAL)
	else Pill(FillText.Res(Res.string.attention_needs_pick), PillTone.WARN)
	// Its pill is said too: the row's description replaces what its children would say (L4b-11).
	val stateWords = pill.label.resolve()
	SegmentedListItem(
		onClick = onToggle,
		modifier = modifier.semantics {
			contentDescription = spoken
			stateDescription = stateWords
		},
		shapes = shapes,
		colors = fillRowColors(),
		contentPadding = SongRowDefaults.ContentPadding,
		leadingContent = {
			Box(Modifier.size(LibraryRowDefaults.ArtSize), contentAlignment = Alignment.Center) {
				Icon(Icons.Outlined.Download, contentDescription = null)
			}
		},
		trailingContent = { StatePill(pill) },
		supportingContent = {
			Column {
				Text(folderDetail(folder) { files }, maxLines = 1, overflow = TextOverflow.Ellipsis)
				Fold(visible = open) {
					Column(Modifier.padding(top = 6.dp)) {
						Text(stringResource(Res.string.attention_folder_hint), style = MaterialTheme.typography.bodySmall)
						if (canOpenInLbBot) {
							TextButton(onClick = onOpenInLbBot, modifier = Modifier.semantics { }) {
								Text(stringResource(Res.string.action_open_in_lbbot))
							}
						}
					}
				}
			}
		}
	) {
		Text(folderTitle(folder, unidentified), maxLines = 2, overflow = TextOverflow.Ellipsis)
	}
}
