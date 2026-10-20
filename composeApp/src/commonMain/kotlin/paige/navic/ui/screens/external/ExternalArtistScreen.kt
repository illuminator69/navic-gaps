@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package paige.navic.ui.screens.external

import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import navic.composeapp.generated.resources.info_external_artist_unreachable
import org.koin.compose.koinInject
import paige.navic.di.LocalBottomBarScrollManager
import paige.navic.di.LocalPlatformContext
import paige.navic.di.isLandscape
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.models.settings.BottomBarVisibilityMode
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Artist
import paige.navic.ui.components.common.ContentUnavailable
import paige.navic.ui.components.layouts.RootBottomBar
import paige.navic.ui.screens.artist.components.ArtistDetailScreenHeading
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
import paige.navic.di.LocalNavStack
import paige.navic.ui.components.layouts.NestedTopBar
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
	val artistName by viewModel.name.collectAsStateWithLifecycle()
	val loading by viewModel.loading.collectAsStateWithLifecycle()
	val preferenceManager = koinInject<PreferenceManager>()
	val platformContext = LocalPlatformContext.current
	val density = LocalDensity.current
	val scrollState = rememberScrollState()
	// The owned artist page's rule: the name moves into the bar once the heading has scrolled by.
	val scrolled by remember {
		derivedStateOf { with(density) { scrollState.value.toDp() } >= 200.dp }
	}

	// The owned artist page's layout: its heading under a bar that takes the name once scrolled,
	// then the discography. An artist the library lacks has no Navidrome photo, so the heading is
	// the one an owned artist without a picture has.
	Scaffold(
		topBar = {
			// The owned artist page's bar: transparent over the heading, the page's surface
			// once the heading has scrolled by, so the list never runs under the title.
			val barAlpha by animateFloatAsState(if (scrolled) 1f else 0f)
			NestedTopBar(
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = MaterialTheme.colorScheme.surface.copy(alpha = barAlpha)
				),
				title = {
					AnimatedVisibility(
						scrolled || loading || !discography.available,
						enter = scaleIn() + fadeIn(),
						exit = scaleOut() + fadeOut()
					) { Text(artistName) }
				}
			)
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
		when {
			loading -> Box(Modifier.fillMaxSize()) {
				ContainedLoadingIndicator(Modifier.size(80.dp).align(Alignment.Center))
			}

			// lb-bot out of reach, or this hub without it: the page has nothing to read.
			!discography.available -> ContentUnavailable(
				modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 32.dp),
				icon = Icons.Outlined.Artist,
				label = stringResource(Res.string.info_external_artist_unreachable)
			)

			else -> Column(
				Modifier
					.fillMaxSize()
					.verticalScroll(scrollState)
					.padding(bottom = innerPadding.calculateBottomPadding() + 24.dp),
				verticalArrangement = Arrangement.spacedBy(12.dp)
			) {
				ArtistDetailScreenHeading(
					artistName = artistName,
					coverArtId = null,
					subtitle = null,
					lastfm = null,
					innerPadding = innerPadding,
					scrolled = scrolled,
					ambientColor = MaterialTheme.colorScheme.surface
				)
				Text(
					stringResource(Res.string.info_external_artist),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(horizontal = 20.dp)
				)
				DiscographyShelf(
					ui = discography,
					artistName = artistName,
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
	}
}
