package paige.navic.ui.screens.matches

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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

	Scaffold(
		topBar = { NestedTopBar({ Text(stringResource(Res.string.title_library_matches)) }) },
		contentWindowInsets = WindowInsets.statusBars
	) { innerPadding ->
		LazyColumn(
			modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding()),
			contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 48.dp)
		) {
			if (layerLine != null) {
				item(key = "layer") { Muted(layerLine) }
				return@LazyColumn
			}

			item(key = "sections") {
				Row(
					Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
					horizontalArrangement = Arrangement.spacedBy(8.dp)
				) {
					offered.forEach { entry ->
						val count = when (entry) {
							MatchesSection.TO_CHECK -> matches.rows.size.takeIf { matchesKnown }
							MatchesSection.ARTISTS -> overrides.rows.size.takeIf { overridesKnown }
							// A shown list is a loaded one (`overrideListShown`).
							MatchesSection.ALBUMS -> albumRows.rows.size
							MatchesSection.MERGES -> mergeRows.rows.size
						}
						FilterChip(
							selected = entry == section,
							onClick = { chosen = entry },
							label = { Text(matchesSectionLabel(entry, count)) }
						)
					}
				}
			}

			when (section) {
				MatchesSection.TO_CHECK -> {
					item(key = "c:note") {
						Column {
							if (matchesKnown) Muted(MATCHES_TO_CHECK_BLURB)
							matchesNote(matches.data, matches.rows.size)?.let { Muted(it) }
						}
					}
					if (matchesKnown) items(matches.rows, key = { "c:${it.ndArtistId}" }) { row ->
						MatchListRow(
							art = libraryArtistArt(row.ndArtistId, artistArt),
							title = row.name.ifBlank { row.ndArtistId },
							lines = listOf(matchDetail(row)),
							onOpen = openArtist(row.ndArtistId),
							below = {
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
					item(key = "a:note") { overridesNote(overrides.data, overrides.rows.size)?.let { Muted(it) } }
					if (overridesKnown) items(overrides.rows, key = { "a:${it.ndArtistId}" }) { row ->
						MatchListRow(
							art = libraryArtistArt(row.ndArtistId, artistArt),
							// An orphan (its Navidrome artist re-minted by a retag) is still listed,
							// by the name lb-bot stored; with no name at all, by the id.
							title = row.name.ifBlank { row.ndArtistId },
							lines = listOf(row.mbid, overrideTagLine(row)),
							onOpen = openArtist(row.ndArtistId),
							trailing = {
								OutlinedButton(
									enabled = busyOverride == null,
									onClick = { viewModel.removeOverride(row.ndArtistId) }
								) { Text("Remove") }
							}
						)
					}
				}

				MatchesSection.ALBUMS -> {
					item(key = "l:note") { albumRowsNote(albumRows.data, albumRows.rows.size)?.let { Muted(it) } }
					items(albumRows.rows, key = { "l:${it.id}" }) { row ->
						val held = heldInLibrary(row.albumId, albumArt)
						MatchListRow(
							art = matchAlbumArt(row.albumId, row.coverUrl, albumArt),
							title = albumRowTitle(row),
							// A state this build has never heard of is not put into words: no line.
							lines = listOf(row.releaseMbid, albumRowDetail(row)) + albumRowProblems(row),
							onOpen = if (held) ({ backStack.add(Screen.CollectionDetail(row.albumId, "library")) }) else null,
							trailing = {
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
					item(key = "m:note") { mergeRowsNote(mergeRows.data, mergeRows.rows.size)?.let { Muted(it) } }
					items(mergeRows.rows, key = { "m:${it.id}" }) { row ->
						MatchListRow(
							// The artist the names were merged INTO: the one that is still there.
							art = libraryArtistArt(row.into.ndArtistId, artistArt),
							title = mergeRowTitle(row),
							lines = listOf(row.mbid, mergeRowDetail(row)) + mergeRowProblems(row),
							onOpen = openArtist(row.into.ndArtistId),
							trailing = {
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
				Column {
					if (overrideLine.isNotBlank()) Muted(overrideLine)
					if (undoLine.isNotBlank()) Muted(undoLine)
				}
			}

			// The index mirror, quietly, with its own Refresh: it reports only what the mirror did.
			item(key = "mirror") {
				Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
					HorizontalDivider()
					Text("Index mirror", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
					Muted(mirrorLine(viewModel.mirrorSupported, mirror))
					if (viewModel.mirrorSupported) Muted(indexLine)
					OutlinedButton(enabled = !refreshing, onClick = { viewModel.refresh() }) {
						Text(if (refreshing) "Refreshing…" else "Refresh")
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
 * One row of any section: the picture, the name (a link when [onOpen] is set — an id is only a
 * link when its page exists), the muted lines under it, and the row's action — at the end of the
 * row ([trailing]), or under the text where there are two ([below]).
 */
@Composable
private fun MatchListRow(
	art: LbArt,
	title: String,
	lines: List<String>,
	onOpen: (() -> Unit)?,
	trailing: (@Composable () -> Unit)? = null,
	below: (@Composable RowScope.() -> Unit)? = null
) {
	Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp)
		) {
			LbArtImage(art, Modifier.size(48.dp), contentDescription = title, onClick = onOpen)
			Column(Modifier.weight(1f)) {
				Text(
					title,
					style = MaterialTheme.typography.bodyLarge,
					color = if (onOpen != null) MaterialTheme.colorScheme.primary
					else MaterialTheme.colorScheme.onSurface,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier
				)
				lines.filter { it.isNotBlank() }.forEach { Muted(it) }
			}
			trailing?.invoke()
		}
		if (below != null) {
			Row(
				modifier = Modifier.padding(start = 60.dp, top = 4.dp),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				content = below
			)
		}
	}
}
