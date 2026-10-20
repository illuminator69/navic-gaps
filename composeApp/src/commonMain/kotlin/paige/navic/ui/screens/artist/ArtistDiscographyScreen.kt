@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package paige.navic.ui.screens.artist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.info_discography_shared
import navic.composeapp.generated.resources.title_discography
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import paige.navic.di.LocalBottomBarScrollManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.models.settings.BottomBarVisibilityMode
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.components.layouts.RootBottomBar
import paige.navic.ui.screens.artist.components.DiscographyBrowser
import paige.navic.ui.screens.artist.components.DiscographyScanAction
import paige.navic.ui.screens.artist.components.indexFreshnessLabel
import paige.navic.ui.screens.artist.viewmodels.ArtistDetailViewModel

/**
 * An owned artist's whole discography (P4 round 2), behind the chips the owner's mockups draw: type
 * chips with counts, the ownership menu, the sort and a rescan. The artist page shows a few releases
 * and "See discography" opens this; it keeps the page short, which is what the chips are for.
 *
 * Its own view model instance (a back-stack entry has its own store), reading the same Room mirror
 * the artist page does, so it opens on what that page showed. Without lb-bot it lists the library's
 * albums, with no pills, since each would say "Owned".
 */
@Composable
fun ArtistDiscographyScreen(artistId: String) {
	val viewModel = koinViewModel<ArtistDetailViewModel>(
		key = artistId,
		parameters = { parametersOf(artistId) }
	)
	val preferenceManager = koinInject<PreferenceManager>()
	val artistState by viewModel.artistState.collectAsStateWithLifecycle()
	val discography by viewModel.discography.collectAsStateWithLifecycle()
	val artistMbid by viewModel.artistMbid.collectAsStateWithLifecycle()
	val state = artistState.data
	val fromIndex = discography.available && discography.indexed && discography.sections.isNotEmpty()
	val sections = if (fromIndex) discography.sections else ownedSections(state?.albums.orEmpty())
	var filter by rememberSaveable(stateSaver = FilterSaver) { mutableStateOf(DiscographyFilter()) }
	val open = rememberDiscographyOpener(viewModel, artistId)

	Scaffold(
		topBar = {
			NestedTopBar(
				title = {
					Column {
						Text(stringResource(Res.string.title_discography), maxLines = 1)
						// "Moth Season · last scanned 2 days ago", the mockup's line under the title.
						val freshness = if (fromIndex) indexFreshnessLabel(discography) else null
						val line = listOfNotNull(state?.artist?.name, freshness).joinToString(" · ")
						if (line.isNotBlank()) {
							Text(
								line,
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis
							)
						}
					}
				}
			)
		},
		bottomBar = {
			val scrollManager = LocalBottomBarScrollManager.current
			if (preferenceManager.bottomBarVisibilityMode == BottomBarVisibilityMode.AllScreens) {
				RootBottomBar(scrolled = scrollManager.isTriggered)
			}
		}
	) { innerPadding ->
		if (state == null) {
			Box(Modifier.fillMaxSize()) {
				ContainedLoadingIndicator(Modifier.size(80.dp).align(Alignment.Center))
			}
			return@Scaffold
		}
		Column(
			Modifier
				.fillMaxSize()
				.verticalScroll(rememberScrollState())
				.padding(top = innerPadding.calculateTopPadding() + 4.dp)
				.padding(bottom = innerPadding.calculateBottomPadding() + 24.dp)
		) {
			DiscographyBrowser(
				sections = sections,
				filter = filter,
				onFilter = { filter = it },
				onOpenEntry = open,
				onLongClick = null,
				// The rescan chip once there is an index; before that the scan action below offers it.
				onRescan = if (fromIndex && artistMbid.effective.isNotBlank()) {
					{ viewModel.indexArtist() }
				} else null,
				rescanning = discography.indexing,
				pills = fromIndex
			)
			// A rescan lb-bot answered "another artist holds this discography", or one that failed:
			// said under the list, as plain lines.
			if (fromIndex && discography.scanShared) {
				Text(
					stringResource(Res.string.info_discography_shared),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					textAlign = TextAlign.Center,
					modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
				)
			}
			if (fromIndex && discography.scanError.isNotBlank()) {
				Text(
					discography.scanError,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.error,
					modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
				)
			}
			if (!fromIndex) {
				DiscographyScanAction(
					ui = discography,
					canIndex = artistMbid.effective.isNotBlank(),
					onIndex = { viewModel.indexArtist() }
				)
			}
		}
	}
}

/** The filter survives a rotation and coming back to the page. */
internal val FilterSaver = listSaver<DiscographyFilter, String>(
	save = { listOf(it.type.orEmpty(), it.ownership.name, it.sort.name) },
	restore = { saved ->
		DiscographyFilter(
			type = saved[0].ifBlank { null },
			ownership = Ownership.entries.firstOrNull { it.name == saved[1] } ?: Ownership.ALL,
			sort = DiscographySort.entries.firstOrNull { it.name == saved[2] } ?: DiscographySort.MOST_PLAYED
		)
	}
)
