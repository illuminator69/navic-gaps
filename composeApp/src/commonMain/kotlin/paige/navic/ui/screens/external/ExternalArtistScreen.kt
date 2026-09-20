package paige.navic.ui.screens.external

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.info_external_artist
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import paige.navic.LocalNavStack
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.components.sheets.MissingAlbumSheet
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.artist.components.DiscographyShelf
import paige.navic.ui.screens.external.viewmodels.ExternalArtistViewModel

/**
 * An artist the library does not have.
 *
 * The discography is lb-bot's, rendered through the *same* [DiscographyShelf] the
 * owned artist page uses, so the two groupings cannot diverge — every entry is
 * absent, which is what lb-bot's `external` classification says and what the shelf
 * already knows how to draw.
 */
@Composable
fun ExternalArtistScreen(artistMbid: String, name: String) {
	val viewModel = koinViewModel<ExternalArtistViewModel>(
		key = artistMbid,
		parameters = { parametersOf(artistMbid, name) }
	)
	val backStack = LocalNavStack.current
	val discography by viewModel.discography.collectAsStateWithLifecycle()
	val selectedRelease by viewModel.selectedRelease.collectAsStateWithLifecycle()
	val artistName by viewModel.name.collectAsStateWithLifecycle()

	Scaffold(
		topBar = { NestedTopBar({ Text(artistName) }) }
	) { innerPadding ->
		Column(
			Modifier
				.padding(innerPadding)
				.fillMaxSize()
				.verticalScroll(rememberScrollState())
		) {
			Text(
				stringResource(Res.string.info_external_artist),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
			)
			DiscographyShelf(
				ui = discography,
				// There is always an MBID here — it is the route key.
				canIndex = artistMbid.isNotBlank(),
				onIndex = { viewModel.indexArtist() },
				onOpenEntry = { entry ->
					entry.release?.let { release ->
						// A row that names a Navidrome album is not a download any
						// more: the library holds it, and sending it to the picker
						// offers to fetch a record the user already has. This is
						// the shape a completed fill leaves behind once lb-bot has
						// resolved the album ids its placement path could not write.
						val ownedAlbumId = release.navidromeAlbumIds.firstOrNull()
						backStack.add(
							if (!ownedAlbumId.isNullOrBlank()) {
								Screen.CollectionDetail(ownedAlbumId, "artist")
							} else {
								Screen.ExternalAlbum(
									rgid = release.rgid,
									artistMbid = artistMbid,
									artistName = artistName,
									title = release.title
								)
							}
						)
					}
				},
				// Nothing owned here, so there is no long-press album menu to offer.
				onSelectEntry = {}
			)
		}
	}

	selectedRelease?.let { release ->
		MissingAlbumSheet(
			release = release,
			onDismissRequest = { viewModel.selectRelease(null) }
		)
	}
}
