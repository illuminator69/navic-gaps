package paige.navic.ui.screens.matches

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import navic.composeapp.generated.resources.title_navi_connect
import paige.navic.di.LocalBottomBarScrollManager
import paige.navic.di.LocalPlatformContext
import paige.navic.di.isLandscape
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.models.settings.BottomBarVisibilityMode
import paige.navic.icons.Icons
import paige.navic.icons.brand.Musicbrainz
import paige.navic.ui.components.common.ContentUnavailable
import paige.navic.ui.components.common.GroupHeader
import paige.navic.ui.components.common.PageChips
import paige.navic.ui.components.common.SegmentedListItem
import paige.navic.ui.components.common.SegmentedListItemDefaults
import paige.navic.ui.components.common.SegmentedListItemDefaults.segmentedShapes
import paige.navic.ui.components.common.SongRowDefaults
import paige.navic.ui.components.layouts.PullToRefreshBox
import paige.navic.ui.components.layouts.RootBottomBar
import paige.navic.ui.util.withoutTop
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.title_library_matches
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.di.LocalNavStack
import paige.navic.domain.manager.LbArt
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbPanelData
import paige.navic.domain.manager.MATCHES_TO_CHECK_BLURB
import paige.navic.domain.manager.MatchesSection
import paige.navic.domain.manager.albumJobInWay
import paige.navic.domain.manager.albumRowCanUndo
import paige.navic.domain.manager.albumRowDetail
import paige.navic.domain.manager.albumRowProblems
import paige.navic.domain.manager.albumRowTitle
import paige.navic.domain.manager.albumRowsNote
import paige.navic.domain.manager.heldInLibrary
import paige.navic.domain.manager.lbLayerLine
import paige.navic.domain.manager.libraryArtistArt
import paige.navic.domain.manager.matchAlbumArt
import paige.navic.domain.manager.matchCanKeep
import paige.navic.domain.manager.matchDetail
import paige.navic.domain.manager.matchesNote
import paige.navic.domain.manager.matchesSectionLabel
import paige.navic.domain.manager.matchesSectionToShow
import paige.navic.domain.manager.matchesSections
import paige.navic.domain.manager.mergeJobInWay
import paige.navic.domain.manager.mergeRowCanUndo
import paige.navic.domain.manager.mergeRowDetail
import paige.navic.domain.manager.mergeRowProblems
import paige.navic.domain.manager.mergeRowTitle
import paige.navic.domain.manager.mergeRowsNote
import paige.navic.domain.manager.mirrorLine
import paige.navic.domain.manager.overrideTagLine
import paige.navic.domain.manager.overridesNote
import paige.navic.ui.components.common.LbArtImage
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.components.sheets.MbidMatchSheet
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.matches.viewmodels.LibraryMatchesViewModel

/**
 * "Library Matches" (I-026): what lb-bot holds about which MusicBrainz artist and release the
 * library's artists and albums are — a page of its own, reached from the library home's top
 * buttons, where it used to be a text section of the navi-connect settings.
 *
 * Four sections, switched with the chips the fork's pages use for such a choice, each with its
 * count: **To check** (artists whose identity is worth a look), **Artists** (the MusicBrainz
 * overrides), **Albums** (the albums lb-bot retagged) and **Merged names**. It opens on To check
 * when that has rows, else on Artists (`matchesSectionToShow`). A row carries the artist's image
 * or the album's cover — from ids the row already has, no request per row (`LbArt`) — and is a
 * link when the library holds what it names. The actions are the ones the section had, through
 * the same manager calls and the same job-following. At the foot, the index mirror with its own
 * Refresh, reporting only what the mirror did.
 *
 * What the lists contain and every sentence they say are unchanged (`LbBotData.kt`,
 * `MatchesToCheck.kt`): an absent list is still no section, and "older lb-bot", "not reachable"
 * and "the copy loaded last" are still said as they were.
 */
@Composable
fun LibraryMatchesScreen() {
	val viewModel = koinViewModel<LibraryMatchesViewModel>()
	val lbBot = koinInject<LbBotManager>()
	val backStack = LocalNavStack.current
	val hubSaysNoLbBot by viewModel.hubSaysNoLbBot.collectAsStateWithLifecycle()

	// Read when the page appears, and again when the hub's configuration changes or the hub
	// says its lb-bot is back: what was read before is about a layer that is no longer there.
	LaunchedEffect(lbBot.routeSignature, hubSaysNoLbBot) { viewModel.read() }

	val overrides by viewModel.overrides.collectAsStateWithLifecycle()
	val matches by viewModel.matches.collectAsStateWithLifecycle()
	val albumRows by viewModel.albumRows.collectAsStateWithLifecycle()
	val mergeRows by viewModel.mergeRows.collectAsStateWithLifecycle()
	val albumJobs by viewModel.albumJobs.collectAsStateWithLifecycle()
	val mergeJobs by viewModel.mergeJobs.collectAsStateWithLifecycle()
	val busyOverride by viewModel.busyOverride.collectAsStateWithLifecycle()
	val busyUndo by viewModel.busyUndo.collectAsStateWithLifecycle()
	val overrideLine by viewModel.overrideLine.collectAsStateWithLifecycle()
	val undoLine by viewModel.undoLine.collectAsStateWithLifecycle()
	val pick by viewModel.pick.collectAsStateWithLifecycle()
	val artistArt by viewModel.artistArt.collectAsStateWithLifecycle()
	val albumArt by viewModel.albumArt.collectAsStateWithLifecycle()
	val mirror by viewModel.mirror.collectAsStateWithLifecycle()
	val indexLine by viewModel.indexLine.collectAsStateWithLifecycle()
	val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()

	// The hub is the only way to lb-bot. Without one — or with one that says it has no lb-bot —
	// this whole layer is off, and the page says which rather than showing empty lists.
	val layerLine = lbLayerLine(viewModel.hubSet, hubSaysNoLbBot)

	// A count is only a count of something that was loaded.
	fun known(data: LbPanelData) = data == LbPanelData.CURRENT || data == LbPanelData.STALE
	val overridesKnown = known(overrides.data)
	val matchesKnown = known(matches.data)

	val offered = matchesSections(toCheck = matches.shown, albums = albumRows.shown, merges = mergeRows.shown)
	var chosen by rememberSaveable { mutableStateOf<MatchesSection?>(null) }
	val section = matchesSectionToShow(chosen, offered, if (matchesKnown) matches.rows.size else 0)

	val openArtist: (String) -> (() -> Unit)? = { id ->
		// Only a link when Room has the artist: an id is not a page that exists.
		if (heldInLibrary(id, artistArt)) ({ backStack.add(Screen.ArtistDetail(id)) }) else null
	}

	val preferenceManager = koinInject<PreferenceManager>()
	val platformContext = LocalPlatformContext.current
	val scope = rememberCoroutineScope()
	var pulling by remember { mutableStateOf(false) }

	Scaffold(
		topBar = {
			Column {
				NestedTopBar({ Text(stringResource(Res.string.title_library_matches)) })
				// The four sections are the page's own, so they sit under its title as upstream's
				// search categories do.
				if (layerLine == null && offered.size > 1) {
					PageChips(
						options = offered,
						selected = section,
						label = { entry ->
							val count = when (entry) {
								MatchesSection.TO_CHECK -> matches.rows.size.takeIf { matchesKnown }
								MatchesSection.ARTISTS -> overrides.rows.size.takeIf { overridesKnown }
								// A shown list is a loaded one (`overrideListShown`).
								MatchesSection.ALBUMS -> albumRows.rows.size
								MatchesSection.MERGES -> mergeRows.rows.size
							}
							matchesSectionLabel(entry, count)
						},
						onSelect = { chosen = it },
						contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp)
					)
				}
			}
		},
		bottomBar = {
			val scrollManager = LocalBottomBarScrollManager.current
			val preferVisible =
				preferenceManager.bottomBarVisibilityMode == BottomBarVisibilityMode.AllScreens
			if (!platformContext.isLandscape() && preferVisible) {
				RootBottomBar(scrolled = scrollManager.isTriggered)
			}
		}
	) { innerPadding ->
		// No hub, or one without lb-bot: this whole layer is off, and the page says which, with
		// the way to the settings that set it up, rather than showing empty lists.
		if (layerLine != null) {
			Column(
				modifier = Modifier
					.fillMaxSize()
					.padding(innerPadding)
					.padding(horizontal = 32.dp),
				verticalArrangement = Arrangement.Center,
				horizontalAlignment = Alignment.CenterHorizontally
			) {
				ContentUnavailable(
					modifier = Modifier.fillMaxWidth(),
					icon = Icons.Brand.Musicbrainz,
					label = layerLine
				)
				Button(
					onClick = { backStack.add(Screen.Settings.NaviConnect) },
					modifier = Modifier.padding(top = 16.dp)
				) { Text(stringResource(Res.string.title_navi_connect)) }
			}
			return@Scaffold
		}

		PullToRefreshBox(
			isRefreshing = pulling,
			onRefresh = {
				scope.launch {
					pulling = true
					viewModel.read().join()
					pulling = false
				}
			},
			modifier = Modifier.padding(top = innerPadding.calculateTopPadding())
		) {
		LazyColumn(
			modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
			contentPadding = innerPadding.withoutTop() + PaddingValues(top = 8.dp, bottom = 24.dp)
		) {
			when (section) {
				MatchesSection.TO_CHECK -> {
					item(key = "c:note") {
						Column(Modifier.padding(bottom = 8.dp)) {
							if (matchesKnown) Muted(MATCHES_TO_CHECK_BLURB)
							matchesNote(matches.data, matches.rows.size)?.let { Muted(it) }
						}
					}
					if (matchesKnown) itemsIndexed(matches.rows, key = { _, it -> "c:${it.ndArtistId}" }) { index, row ->
						MatchListRow(
							art = libraryArtistArt(row.ndArtistId, artistArt),
							shapes = segmentedShapes(index = index, count = matches.rows.size),
							title = row.name.ifBlank { row.ndArtistId },
							lines = listOf(matchDetail(row)),
							onOpen = openArtist(row.ndArtistId),
							actions = {
								OutlinedButton(enabled = busyOverride == null, onClick = { viewModel.choose(row) }) {
									Text("Choose…")
								}
								if (matchCanKeep(row)) {
									OutlinedButton(enabled = busyOverride == null, onClick = { viewModel.keepMatch(row) }) {
										Text("Keep this match")
									}
								}
							}
						)
					}
				}

				MatchesSection.ARTISTS -> {
					item(key = "a:note") {
						overridesNote(overrides.data, overrides.rows.size)?.let {
							Box(Modifier.padding(bottom = 8.dp)) { Muted(it) }
						}
					}
					if (overridesKnown) itemsIndexed(overrides.rows, key = { _, it -> "a:${it.ndArtistId}" }) { index, row ->
						MatchListRow(
							art = libraryArtistArt(row.ndArtistId, artistArt),
							shapes = segmentedShapes(index = index, count = overrides.rows.size),
							// An orphan (its Navidrome artist re-minted by a retag) is still listed,
							// by the name lb-bot stored; with no name at all, by the id.
							title = row.name.ifBlank { row.ndArtistId },
							lines = listOf(row.mbid, overrideTagLine(row)),
							onOpen = openArtist(row.ndArtistId),
							actions = {
								OutlinedButton(
									enabled = busyOverride == null,
									onClick = { viewModel.removeOverride(row.ndArtistId) }
								) { Text("Remove") }
							}
						)
					}
				}

				MatchesSection.ALBUMS -> {
					item(key = "l:note") {
						albumRowsNote(albumRows.data, albumRows.rows.size)?.let {
							Box(Modifier.padding(bottom = 8.dp)) { Muted(it) }
						}
					}
					itemsIndexed(albumRows.rows, key = { _, it -> "l:${it.id}" }) { index, row ->
						val held = heldInLibrary(row.albumId, albumArt)
						MatchListRow(
							art = matchAlbumArt(row.albumId, row.coverUrl, albumArt),
							shapes = segmentedShapes(index = index, count = albumRows.rows.size),
							title = albumRowTitle(row),
							// A state this build has never heard of is not put into words: no line.
							lines = listOf(row.releaseMbid, albumRowDetail(row)) + albumRowProblems(row),
							onOpen = if (held) ({ backStack.add(Screen.CollectionDetail(row.albumId, "library")) }) else null,
							actions = {
								OutlinedButton(
									// At rest, nothing of this page's out, and no job the manager
									// still follows for the row or its album — it would refuse it.
									enabled = busyUndo == null && albumRowCanUndo(row) &&
										albumJobInWay(albumJobs.values, row.albumId, row.id) == null,
									onClick = { viewModel.undoAlbum(row) }
								) { Text("Undo") }
							}
						)
					}
				}

				MatchesSection.MERGES -> {
					item(key = "m:note") {
						mergeRowsNote(mergeRows.data, mergeRows.rows.size)?.let {
							Box(Modifier.padding(bottom = 8.dp)) { Muted(it) }
						}
					}
					itemsIndexed(mergeRows.rows, key = { _, it -> "m:${it.id}" }) { index, row ->
						MatchListRow(
							// The artist the names were merged INTO: the one that is still there.
							art = libraryArtistArt(row.into.ndArtistId, artistArt),
							shapes = segmentedShapes(index = index, count = mergeRows.rows.size),
							title = mergeRowTitle(row),
							lines = listOf(row.mbid, mergeRowDetail(row)) + mergeRowProblems(row),
							onOpen = openArtist(row.into.ndArtistId),
							actions = {
								OutlinedButton(
									enabled = busyUndo == null && mergeRowCanUndo(row) &&
										mergeJobInWay(
											mergeJobs.values,
											listOf(row.into.ndArtistId, row.from.ndArtistId),
											row.id
										) == null,
									onClick = { viewModel.undoMerge(row) }
								) { Text("Undo") }
							}
						)
					}
				}
			}

			// What the last Remove, Keep, Choose or Undo came to, in lb-bot's words where it refused.
			item(key = "lines") {
				Column(Modifier.padding(top = 8.dp)) {
					if (overrideLine.isNotBlank()) Muted(overrideLine)
					if (undoLine.isNotBlank()) Muted(undoLine)
				}
			}

			// The index mirror, quietly, with its own Refresh: it reports only what the mirror did.
			item(key = "mirror") {
				Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
					GroupHeader(title = "Index mirror")
					Muted(mirrorLine(viewModel.mirrorSupported, mirror))
					if (viewModel.mirrorSupported) Muted(indexLine)
					OutlinedButton(enabled = !refreshing, onClick = { viewModel.refresh() }) {
						Text(if (refreshing) "Refreshing…" else "Refresh")
					}
				}
			}
		}
		}
	}

	// The picker "Choose…" opens — the artist page's own. These artists have no tag in Navidrome
	// and no override (that is what puts them on the list), so there is no id in force to mark.
	pick?.let { picking ->
		MbidMatchSheet(
			artistName = picking.row.name,
			ndArtistId = picking.row.ndArtistId,
			coverArtId = (libraryArtistArt(picking.row.ndArtistId, artistArt) as? LbArt.Library)?.coverArtId,
			tagMbid = "",
			currentMbid = "",
			overridden = false,
			working = picking.working,
			error = picking.error,
			onPick = { viewModel.pick(it) },
			onRemoveOverride = {},
			onDismissRequest = { viewModel.dismissPick() }
		)
	}
}

@Composable
private fun Muted(text: String) {
	Text(
		text,
		style = MaterialTheme.typography.bodySmall,
		color = MaterialTheme.colorScheme.onSurfaceVariant
	)
}

/**
 * One row of any section, as a segmented library row: the picture, the name, the muted lines under
 * it, and the row's actions under those, so a row with two keeps its title. The whole row is the
 * link when [onOpen] is set (an id is only a link when its page exists); a row with nowhere to go
 * is drawn inert, in its usual colours.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MatchListRow(
	art: LbArt,
	shapes: ListItemShapes,
	title: String,
	lines: List<String>,
	onOpen: (() -> Unit)?,
	actions: @Composable () -> Unit
) {
	val preferenceManager = koinInject<PreferenceManager>()
	val container = SongRowDefaults.containerColor(false)
	val supporting = SongRowDefaults.supportingContentColor(false)
	SegmentedListItem(
		onClick = onOpen ?: {},
		enabled = onOpen != null,
		shapes = shapes,
		modifier = Modifier.padding(bottom = SegmentedListItemDefaults.SegmentedGap),
		colors = ListItemDefaults.colors(
			containerColor = container,
			supportingContentColor = supporting,
			disabledContainerColor = container,
			disabledContentColor = MaterialTheme.colorScheme.onSurface,
			disabledLeadingContentColor = MaterialTheme.colorScheme.onSurface,
			disabledSupportingContentColor = supporting
		),
		contentPadding = SongRowDefaults.ContentPadding,
		leadingContent = {
			LbArtImage(
				art,
				Modifier.size(50.dp),
				contentDescription = title,
				shape = if (art is LbArt.Library && art.isArtist) {
					preferenceManager.artistImageShape.decreasedShape
				} else preferenceManager.coverArtShape.decreasedShape
			)
		},
		content = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
		supportingContent = {
			Column {
				lines.filter { it.isNotBlank() }.forEach {
					Text(it, style = MaterialTheme.typography.bodySmall)
				}
				FlowRow(
					modifier = Modifier.padding(top = 6.dp),
					horizontalArrangement = Arrangement.spacedBy(8.dp)
				) { actions() }
			}
		}
	)
}
