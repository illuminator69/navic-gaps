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
import paige.navic.domain.manager.matchCanKeep
import paige.navic.domain.manager.matchReason
import paige.navic.domain.manager.matchesNote
import paige.navic.domain.manager.LbPanelData
import paige.navic.domain.manager.indexRunLine
import paige.navic.domain.manager.mirrorLine
import paige.navic.domain.manager.overrideTagLine
import paige.navic.domain.manager.overridesNote
import paige.navic.ui.components.sheets.MbidMatchSheet
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.settings.viewmodels.LbBotDataViewModel

/**
 * "lb-bot data" (I-020), a section of the navi-connect settings screen: what this device holds
 * of lb-bot's and what lb-bot holds for this user — the artist MBID overrides, the index mirror,
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
	 * `LbBotManager.routeSignature`. Not read here: it is what makes this section recompose when
	 * the hub is switched on or off on the screen around it, since "is the lb-bot layer there"
	 * is a preference read with no Flow behind it.
	 */
	@Suppress("UNUSED_PARAMETER") routeSignature: String
) {
	val viewModel = koinViewModel<LbBotDataViewModel>()

	Text("lb-bot data", style = MaterialTheme.typography.titleMedium)

	// The hub is the only way to lb-bot. Without one this whole layer is off, and the section
	// says so in a sentence rather than showing empty lists that look like "you have nothing".
	if (!viewModel.configured) {
		Muted("lb-bot is reached through the hub. Set the hub up above to see its data here.")
		return
	}

	val overrides by viewModel.overrides.collectAsStateWithLifecycle()
	val busyOverride by viewModel.busyOverride.collectAsStateWithLifecycle()
	val overrideLine by viewModel.overrideLine.collectAsStateWithLifecycle()
	val matches by viewModel.matches.collectAsStateWithLifecycle()
	val pick by viewModel.pick.collectAsStateWithLifecycle()
	val knownArtistIds by viewModel.knownArtistIds.collectAsStateWithLifecycle()
	val backStack = LocalNavStack.current
	val mirror by viewModel.mirror.collectAsStateWithLifecycle()
	val indexRun by viewModel.indexRun.collectAsStateWithLifecycle()
	val listeningLine by viewModel.listeningLine.collectAsStateWithLifecycle()
	val listeningSyncLine by viewModel.listeningSyncLine.collectAsStateWithLifecycle()

	Button(onClick = { viewModel.syncNow() }) { Text("Sync now") }

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
	if (viewModel.mirrorSupported) Muted(indexRunLine(indexRun))

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
 * One artist to check (I-021): the name (a link to their page when there is one), how many
 * albums and what was measured, "Choose…", and "Keep this match" where lb-bot has a guess to keep.
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
		Muted(matchReason(row))
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			OutlinedButton(enabled = enabled, onClick = onChoose) { Text("Choose…") }
			if (onKeep != null) {
				OutlinedButton(enabled = enabled, onClick = onKeep) { Text("Keep this match") }
			}
		}
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
