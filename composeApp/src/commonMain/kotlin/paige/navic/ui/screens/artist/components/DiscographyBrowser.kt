package paige.navic.ui.screens.artist.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousCapsule
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_rescan_discography
import navic.composeapp.generated.resources.discography_chip
import navic.composeapp.generated.resources.discography_matched_missing
import navic.composeapp.generated.resources.discography_matched_owned
import navic.composeapp.generated.resources.discography_matched_partly
import navic.composeapp.generated.resources.discography_none_match
import navic.composeapp.generated.resources.discography_of_total
import navic.composeapp.generated.resources.discography_ownership
import navic.composeapp.generated.resources.discography_sort_most_played
import navic.composeapp.generated.resources.discography_sort_newest
import navic.composeapp.generated.resources.discography_sort_oldest
import navic.composeapp.generated.resources.discography_sort_title
import navic.composeapp.generated.resources.option_all
import navic.composeapp.generated.resources.release_missing
import navic.composeapp.generated.resources.release_owned
import navic.composeapp.generated.resources.release_partly_owned
import navic.composeapp.generated.resources.release_tap_hint
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import paige.navic.icons.Icons
import paige.navic.icons.outlined.KeyboardArrowDown
import paige.navic.icons.outlined.Refresh
import paige.navic.icons.outlined.Sort
import paige.navic.ui.screens.artist.DiscographyFilter
import paige.navic.ui.screens.artist.DiscographySort
import paige.navic.ui.screens.artist.Ownership
import paige.navic.ui.screens.artist.discographyView
import paige.navic.ui.screens.artist.entryTypes
import paige.navic.ui.screens.artist.ownershipOf
import paige.navic.ui.screens.artist.typeChips
import paige.navic.ui.screens.artist.viewmodels.DiscographyEntry
import paige.navic.ui.screens.artist.viewmodels.DiscographySection
import paige.navic.ui.screens.external.components.ReleaseRows

/*
 * navi-connect P4, round 2: the discography behind chips, as the owner's mockups draw it. Type chips
 * with counts in one scrolling row; under them the ownership menu, the sort and a rescan. With an
 * ownership filter on, the list is one list whose rows name their type, under a heading that counts
 * what matched ("4 missing of 13 · tap to find sources"). The discography page and the unowned
 * artist page both draw it.
 */

/**
 * The chips and the list for [sections], filtered by [filter]. [onRescan] null hides the rescan
 * chip (nothing to scan from here); [pills] false where every row would read "Owned".
 */
@Composable
fun DiscographyBrowser(
	sections: List<DiscographySection>,
	filter: DiscographyFilter,
	onFilter: (DiscographyFilter) -> Unit,
	onOpenEntry: (DiscographyEntry) -> Unit,
	onLongClick: ((DiscographyEntry) -> Unit)?,
	onRescan: (() -> Unit)?,
	rescanning: Boolean,
	pills: Boolean,
	/** The unowned artist page: a tap is where a release's sources are found, so say so. */
	tapHint: Boolean = false,
	/** No plays to sort by (an artist the library lacks): the sort menu leaves "Most played" out. */
	playsKnown: Boolean = true
) {
	val chips = typeChips(sections)
	// The menu offers only what this discography holds; one kind alone needs no menu.
	val kinds = sections.flatMap { it.entries }.map(::ownershipOf).toSet()
	val view = discographyView(sections, filter)
	Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
		Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
			if (chips.size > 2) {
				Row(
					Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
					horizontalArrangement = Arrangement.spacedBy(8.dp)
				) {
					chips.forEach { chip ->
						val name = chip.type?.let { sectionTitle(it) } ?: stringResource(Res.string.option_all)
						PageChip(
							selected = filter.type == chip.type,
							label = stringResource(Res.string.discography_chip, name, chip.count),
							onClick = { onFilter(filter.copy(type = chip.type)) }
						)
					}
				}
			}
			Row(
				Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				if (kinds.size > 1) {
					OwnershipChip(filter.ownership, kinds) { onFilter(filter.copy(ownership = it)) }
				}
				SortChip(filter.sort, playsKnown) { onFilter(filter.copy(sort = it)) }
				if (onRescan != null) RescanChip(rescanning, onRescan)
			}
		}
		// Every change of chip or sort plays the rows' arrival again (the owner's note on round 2):
		// the ownership menu already did, by swapping between the flat list and the sections, and
		// the type chips and the sort changed the rows in place with no motion at all. A new filter
		// is a new composition of the list, so its arrival gate starts empty.
		key(filter) {
			when {
				view.flat && view.sections.isEmpty() -> Text(
					stringResource(Res.string.discography_none_match),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(horizontal = 20.dp)
				)
				view.flat -> {
					val types = remember(sections) { entryTypes(sections) }
					Column {
						MatchedHeading(filter.ownership, view.matched, view.total)
						ReleaseRows(
							sections = view.sections,
							sectionTitle = { sectionTitle(it) },
							onOpenEntry = onOpenEntry,
							onLongClick = onLongClick,
							headings = false,
							typeOf = { types[it.key] ?: "album" },
							pills = pills
						)
					}
				}
				else -> ReleaseRows(
					sections = view.sections,
					sectionTitle = { sectionTitle(it) },
					onOpenEntry = onOpenEntry,
					onLongClick = onLongClick,
					tapHint = tapHint,
					countInHeading = !tapHint,
					pills = pills
				)
			}
		}
	}
}

/** "4 missing  of 13 · tap to find sources": the count in the heading's weight, the rest quiet. */
@Composable
private fun MatchedHeading(ownership: Ownership, matched: Int, total: Int) {
	val count = when (ownership) {
		Ownership.OWNED -> stringResource(Res.string.discography_matched_owned, matched)
		Ownership.PARTLY -> stringResource(Res.string.discography_matched_partly, matched)
		else -> stringResource(Res.string.discography_matched_missing, matched)
	}
	val rest = listOfNotNull(
		stringResource(Res.string.discography_of_total, total),
		stringResource(Res.string.release_tap_hint).lowercase().takeIf { ownership == Ownership.MISSING }
	).joinToString(" · ")
	val quiet = MaterialTheme.colorScheme.onSurfaceVariant
	Text(
		buildAnnotatedString {
			withStyle(SpanStyle(fontWeight = FontWeight(600))) { append(count) }
			append("  ")
			withStyle(SpanStyle(color = quiet, fontSize = MaterialTheme.typography.bodySmall.fontSize)) { append(rest) }
		},
		style = MaterialTheme.typography.titleMediumEmphasized,
		modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 6.dp)
	)
}

/** The mockup's chip: a filled capsule when chosen, the page's glass when not, no outline. */
@Composable
private fun PageChip(
	selected: Boolean,
	label: String,
	onClick: () -> Unit,
	leadingIcon: (@Composable () -> Unit)? = null,
	trailingIcon: (@Composable () -> Unit)? = null
) {
	FilterChip(
		selected = selected,
		onClick = onClick,
		label = { Text(label, style = MaterialTheme.typography.labelLarge) },
		leadingIcon = leadingIcon,
		trailingIcon = trailingIcon,
		shape = ContinuousCapsule,
		colors = FilterChipDefaults.filterChipColors(
			containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
			labelColor = MaterialTheme.colorScheme.onSurface,
			iconColor = MaterialTheme.colorScheme.onSurface,
			selectedContainerColor = MaterialTheme.colorScheme.inverseSurface,
			selectedLabelColor = MaterialTheme.colorScheme.inverseOnSurface,
			selectedLeadingIconColor = MaterialTheme.colorScheme.inverseOnSurface,
			selectedTrailingIconColor = MaterialTheme.colorScheme.inverseOnSurface
		),
		border = FilterChipDefaults.filterChipBorder(
			enabled = true,
			selected = selected,
			borderColor = Color.Transparent,
			selectedBorderColor = Color.Transparent
		)
	)
}

private val OWNERSHIP_ORDER = listOf(Ownership.ALL, Ownership.OWNED, Ownership.PARTLY, Ownership.MISSING)

private fun Ownership.label(): StringResource = when (this) {
	Ownership.ALL -> Res.string.option_all
	Ownership.OWNED -> Res.string.release_owned
	Ownership.PARTLY -> Res.string.release_partly_owned
	Ownership.MISSING -> Res.string.release_missing
}

@Composable
private fun OwnershipChip(current: Ownership, kinds: Set<Ownership>, onPick: (Ownership) -> Unit) {
	var open by remember { mutableStateOf(false) }
	Box {
		PageChip(
			selected = current != Ownership.ALL,
			label = stringResource(Res.string.discography_ownership, stringResource(current.label())),
			onClick = { open = true },
			trailingIcon = { Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(18.dp)) }
		)
		DropdownMenu(open, onDismissRequest = { open = false }) {
			OWNERSHIP_ORDER.filter { it == Ownership.ALL || it in kinds || it == current }.forEach { option ->
				DropdownMenuItem(
					text = { Text(stringResource(option.label())) },
					leadingIcon = { RadioButton(selected = option == current, onClick = null) },
					onClick = {
						onPick(option)
						open = false
					}
				)
			}
		}
	}
}

private fun DiscographySort.label(): StringResource = when (this) {
	DiscographySort.MOST_PLAYED -> Res.string.discography_sort_most_played
	DiscographySort.NEWEST -> Res.string.discography_sort_newest
	DiscographySort.OLDEST -> Res.string.discography_sort_oldest
	DiscographySort.TITLE -> Res.string.discography_sort_title
}

@Composable
private fun SortChip(current: DiscographySort, playsKnown: Boolean, onPick: (DiscographySort) -> Unit) {
	var open by remember { mutableStateOf(false) }
	Box {
		PageChip(
			selected = false,
			label = stringResource(current.label()),
			onClick = { open = true },
			leadingIcon = { Icon(Icons.Outlined.Sort, null, Modifier.size(18.dp)) }
		)
		DropdownMenu(open, onDismissRequest = { open = false }) {
			DiscographySort.entries.filter { playsKnown || it != DiscographySort.MOST_PLAYED }.forEach { option ->
				DropdownMenuItem(
					text = { Text(stringResource(option.label())) },
					leadingIcon = { RadioButton(selected = option == current, onClick = null) },
					onClick = {
						onPick(option)
						open = false
					}
				)
			}
		}
	}
}

/** Rescan this artist: an icon in a chip-high capsule, a spinner while the walk runs. */
@Composable
private fun RescanChip(rescanning: Boolean, onRescan: () -> Unit) {
	val label = stringResource(Res.string.action_rescan_discography)
	Box(
		Modifier
			.size(width = 48.dp, height = 32.dp)
			.clip(ContinuousCapsule)
			.background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f))
			.clickable(enabled = !rescanning, onClickLabel = label, role = Role.Button, onClick = onRescan),
		contentAlignment = Alignment.Center
	) {
		if (rescanning) {
			CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
		} else {
			Icon(Icons.Outlined.Refresh, contentDescription = label, modifier = Modifier.size(18.dp))
		}
	}
}
