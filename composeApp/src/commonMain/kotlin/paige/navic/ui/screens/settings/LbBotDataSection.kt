package paige.navic.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.di.LocalNavStack
import paige.navic.domain.manager.LbArtistOverride
import paige.navic.domain.manager.LbMatchToCheck
import paige.navic.domain.manager.MATCHES_TO_CHECK_BLURB
import paige.navic.domain.manager.albumJobInWay
import paige.navic.domain.manager.albumRowCanUndo
import paige.navic.domain.manager.albumRowDetail
import paige.navic.domain.manager.albumRowTitle
import paige.navic.domain.manager.albumRowsNote
import paige.navic.domain.manager.matchCanKeep
import paige.navic.domain.manager.matchDetail
import paige.navic.domain.manager.matchesNote
import paige.navic.domain.manager.LbPanelData
import paige.navic.domain.manager.lbLayerLine
import paige.navic.domain.manager.mergeJobInWay
import paige.navic.domain.manager.mergeRowCanUndo
import paige.navic.domain.manager.mergeRowDetail
import paige.navic.domain.manager.mergeRowTitle
import paige.navic.domain.manager.mergeRowsNote
import paige.navic.domain.manager.mirrorLine
import paige.navic.domain.manager.overrideTagLine
import paige.navic.domain.manager.overridesNote
import paige.navic.ui.components.sheets.MbidMatchSheet
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.settings.viewmodels.LbBotDataViewModel

/**
 * "lb-bot data" (I-020), a section of the navi-connect settings screen: what this device holds
 * of lb-bot's and what lb-bot holds for this user — the artist MBID overrides, the albums it
 * retagged (I-022) and the artist names it merged (I-023), each with its Undo, the index mirror,
 * the listening history — with one "Sync now".
 *
 * Its strings are plain English, like the rest of that screen, and every one of them is decided
 * by a tested function in `LbBotData.kt` from something lb-bot, the hub or Room actually said.
 * None of the lists says "Loading…" unless a read is in flight; with lb-bot out of reach and
 * nothing loaded before, each says that lb-bot is needed and is not reachable.
 */
@Composable
fun LbBotDataSection(
	/**
	 * `LbBotManager.routeSignature`: the hub switch, address and token of the screen around this
	 * section, which are preference reads with no Flow behind them. A change of it is what makes
	 * the section recompose, ask again whether the lb-bot layer is there, and read again.
	 */
	routeSignature: String,
	/**
	 * How many times "Save & reconnect" (or the switch) has been used on the screen around this
	 * section. A save of unchanged values, or one made while the hub is out of reach, moves
	 * nothing else — and is still the user asking for the panel to look again.
	 */
	saves: Int
) {
	val viewModel = koinViewModel<LbBotDataViewModel>()
	val hubSaysNoLbBot by viewModel.hubSaysNoLbBot.collectAsStateWithLifecycle()

	// Read when the section appears, and again when the hub is set up, changed or switched on
	// above, or says its lb-bot is back: what was read (or not read) before is about a layer
	// that is no longer the one on screen.
	LaunchedEffect(routeSignature, hubSaysNoLbBot, saves) { viewModel.read() }

	Text("lb-bot data", style = MaterialTheme.typography.titleMedium)

	// The hub is the only way to lb-bot. Without one — or with one that says it has no lb-bot
	// — this whole layer is off, and the section says which in a sentence rather than showing
	// empty lists that look like "you have nothing".
	val layerLine = lbLayerLine(viewModel.hubSet, hubSaysNoLbBot)
	if (layerLine != null) {
		Muted(layerLine)
		return
	}

	val overrides by viewModel.overrides.collectAsStateWithLifecycle()
	val busyOverride by viewModel.busyOverride.collectAsStateWithLifecycle()
	val overrideLine by viewModel.overrideLine.collectAsStateWithLifecycle()
	val matches by viewModel.matches.collectAsStateWithLifecycle()
	val pick by viewModel.pick.collectAsStateWithLifecycle()
	val knownArtistIds by viewModel.knownArtistIds.collectAsStateWithLifecycle()
	val albumRows by viewModel.albumRows.collectAsStateWithLifecycle()
	val mergeRows by viewModel.mergeRows.collectAsStateWithLifecycle()
	val albumJobs by viewModel.albumJobs.collectAsStateWithLifecycle()
	val mergeJobs by viewModel.mergeJobs.collectAsStateWithLifecycle()
	val busyUndo by viewModel.busyUndo.collectAsStateWithLifecycle()
	val undoLine by viewModel.undoLine.collectAsStateWithLifecycle()
	val backStack = LocalNavStack.current
	val mirror by viewModel.mirror.collectAsStateWithLifecycle()
	val indexLine by viewModel.indexLine.collectAsStateWithLifecycle()
	val syncing by viewModel.syncing.collectAsStateWithLifecycle()
	val listeningLine by viewModel.listeningLine.collectAsStateWithLifecycle()
	val listeningSyncLine by viewModel.listeningSyncLine.collectAsStateWithLifecycle()

	// Disabled, and saying so, while a sync it started is still at work: a second tap would
	// otherwise do nothing and show nothing.
	Button(enabled = !syncing, onClick = { viewModel.syncNow() }) {
		Text(if (syncing) "Syncing…" else "Sync now")
	}

	// ----- overrides ------------------------------------------------------------------------
	var overridesOpen by rememberSaveable { mutableStateOf(false) }
	// A count is only a count of something that was loaded.
	val overridesKnown = overrides.data == LbPanelData.CURRENT || overrides.data == LbPanelData.STALE
	Heading(
		title = if (overridesKnown) "MusicBrainz overrides (${overrides.rows.size})" else "MusicBrainz overrides",
		expandable = overridesKnown && overrides.rows.isNotEmpty(),
		open = overridesOpen,
		onToggle = { overridesOpen = !overridesOpen }
	)
	overridesNote(overrides.data, overrides.rows.size)?.let { Muted(it) }
	if (overridesOpen && overridesKnown) {
		overrides.rows.forEach { row ->
			OverrideRow(
				row = row,
				enabled = busyOverride == null,
				onRemove = { viewModel.removeOverride(row.ndArtistId) }
			)
		}
	}

	// ----- album overrides (I-022) and name merges (I-023) ------------------------------------
	// Each is a section only when lb-bot sent the list and the hub carries its Undo
	// (`overrideListShown`): a missing list renders nothing, an empty one says there are none.
	// A shown list is a loaded one, so its count is always a count of something.
	if (albumRows.shown) {
		var albumsOpen by rememberSaveable { mutableStateOf(false) }
		Heading(
			title = "Album matches changed by lb-bot (${albumRows.rows.size})",
			expandable = albumRows.rows.isNotEmpty(),
			open = albumsOpen,
			onToggle = { albumsOpen = !albumsOpen }
		)
		albumRowsNote(albumRows.data, albumRows.rows.size)?.let { Muted(it) }
		if (albumsOpen) {
			albumRows.rows.forEach { row ->
				UndoRow(
					title = albumRowTitle(row),
					id = row.releaseMbid,
					detail = albumRowDetail(row),
					// At rest, nothing of this panel's out, and no job the manager still follows
					// for the row or its album — that one it would refuse, unsent.
					enabled = busyUndo == null && albumRowCanUndo(row) &&
						albumJobInWay(albumJobs.values, row.albumId, row.id) == null,
					onUndo = { viewModel.undoAlbum(row) }
				)
			}
		}
	}
	if (mergeRows.shown) {
		var mergesOpen by rememberSaveable { mutableStateOf(false) }
		Heading(
			title = "Merged artist names (${mergeRows.rows.size})",
			expandable = mergeRows.rows.isNotEmpty(),
			open = mergesOpen,
			onToggle = { mergesOpen = !mergesOpen }
		)
		mergeRowsNote(mergeRows.data, mergeRows.rows.size)?.let { Muted(it) }
		if (mergesOpen) {
			mergeRows.rows.forEach { row ->
				UndoRow(
					title = mergeRowTitle(row),
					id = row.mbid,
					detail = mergeRowDetail(row),
					enabled = busyUndo == null && mergeRowCanUndo(row) &&
						mergeJobInWay(mergeJobs.values, listOf(row.into.ndArtistId, row.from.ndArtistId), row.id) == null,
					onUndo = { viewModel.undoMerge(row) }
				)
			}
		}
	}
	// What the last Undo came to: lb-bot's refusal, that it is at work, then how it ended.
	if (undoLine.isNotBlank()) Muted(undoLine)

	// ----- matches to check (I-021) ---------------------------------------------------------
	// Shown only when lb-bot sends the list (or when nothing could be loaded at all, to say
	// so). An lb-bot that predates it sends none, and there is then no section — not an empty
	// one claiming there is nothing to check.
	if (matches.shown) {
		var matchesOpen by rememberSaveable { mutableStateOf(false) }
		val matchesKnown = matches.data == LbPanelData.CURRENT || matches.data == LbPanelData.STALE
		Heading(
			title = if (matchesKnown) "Matches to check (${matches.rows.size})" else "Matches to check",
			expandable = matchesKnown && matches.rows.isNotEmpty(),
			open = matchesOpen,
			onToggle = { matchesOpen = !matchesOpen }
		)
		if (matchesKnown) Muted(MATCHES_TO_CHECK_BLURB)
		matchesNote(matches.data, matches.rows.size)?.let { Muted(it) }
		if (matchesOpen && matchesKnown) {
			matches.rows.forEach { row ->
				MatchRow(
					row = row,
					enabled = busyOverride == null,
					// Only a link when Room has the artist: an id is not a page that exists.
					onOpen = if (row.ndArtistId in knownArtistIds) {
						{ backStack.add(Screen.ArtistDetail(row.ndArtistId)) }
					} else {
						null
					},
					onChoose = { viewModel.choose(row) },
					onKeep = if (matchCanKeep(row)) {
						{ viewModel.keepMatch(row) }
					} else {
						null
					}
				)
			}
		}
	}

	// What the last Remove, Keep or Choose did, in lb-bot's words where it refused.
	if (overrideLine.isNotBlank()) Muted(overrideLine)

	// The picker "Choose…" opens — the artist page's own. These artists have no tag in
	// Navidrome and no override (that is what puts them on the list), so there is no id in
	// force to mark, and lb-bot's guess is as pickable as any other candidate.
	pick?.let { picking ->
		MbidMatchSheet(
			artistName = picking.row.name,
			coverArtId = null,
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

	// ----- the index mirror -----------------------------------------------------------------
	Heading("Index mirror")
	Muted(mirrorLine(viewModel.mirrorSupported, mirror))
	if (viewModel.mirrorSupported) Muted(indexLine)

	// ----- listening history ----------------------------------------------------------------
	Heading("Listening history")
	if (listeningLine.isNotBlank()) Muted(listeningLine)
	if (listeningSyncLine.isNotBlank()) Muted(listeningSyncLine)
}

@Composable
private fun Heading(
	title: String,
	expandable: Boolean = false,
	open: Boolean = false,
	onToggle: () -> Unit = {}
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.then(if (expandable) Modifier.clickable(onClick = onToggle) else Modifier)
			.padding(top = 4.dp),
		horizontalArrangement = Arrangement.SpaceBetween,
		verticalAlignment = Alignment.CenterVertically
	) {
		Text(title, style = MaterialTheme.typography.bodyLarge)
		if (expandable) {
			Text(
				if (open) "Hide" else "Show",
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.primary
			)
		}
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
 * One artist to check (I-021): the name (a link to their page when there is one), what was
 * measured and how many albums they have (`matchDetail`), "Choose…", and "Keep this match" where
 * lb-bot has a guess to keep.
 */
@Composable
private fun MatchRow(
	row: LbMatchToCheck,
	enabled: Boolean,
	onOpen: (() -> Unit)?,
	onChoose: () -> Unit,
	onKeep: (() -> Unit)?
) {
	Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
		Text(
			row.name.ifBlank { row.ndArtistId },
			style = MaterialTheme.typography.bodyLarge,
			color = if (onOpen != null) MaterialTheme.colorScheme.primary
			else MaterialTheme.colorScheme.onSurface,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier
		)
		Muted(matchDetail(row))
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			OutlinedButton(enabled = enabled, onClick = onChoose) { Text("Choose…") }
			if (onKeep != null) {
				OutlinedButton(enabled = enabled, onClick = onKeep) { Text("Keep this match") }
			}
		}
	}
}

/**
 * One album override or name merge: what it is, the MusicBrainz id it is about, what lb-bot says
 * of it (`albumRowDetail`, `mergeRowDetail`), and Undo.
 */
@Composable
private fun UndoRow(title: String, id: String, detail: String, enabled: Boolean, onUndo: () -> Unit) {
	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.SpaceBetween,
		verticalAlignment = Alignment.CenterVertically
	) {
		Column(Modifier.weight(1f)) {
			Text(
				title,
				style = MaterialTheme.typography.bodyLarge,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
			if (id.isNotBlank()) Muted(id)
			// A state this build has never heard of is not put into words: no line then.
			if (detail.isNotBlank()) Muted(detail)
		}
		OutlinedButton(enabled = enabled, onClick = onUndo) { Text("Undo") }
	}
}

/** One override: who, which MusicBrainz artist, what Navidrome had said, and Remove. */
@Composable
private fun OverrideRow(row: LbArtistOverride, enabled: Boolean, onRemove: () -> Unit) {
	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.SpaceBetween,
		verticalAlignment = Alignment.CenterVertically
	) {
		Column(Modifier.weight(1f)) {
			Text(
				// An orphan (its Navidrome artist re-minted by a retag) is still listed, by the
				// name lb-bot stored; with no name at all, by the id.
				row.name.ifBlank { row.ndArtistId },
				style = MaterialTheme.typography.bodyLarge,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
			Muted(row.mbid)
			Muted(overrideTagLine(row))
		}
		OutlinedButton(enabled = enabled, onClick = onRemove) { Text("Remove") }
	}
}
