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
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import paige.navic.di.LocalNavStack
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.artist.components.DiscographyShelf
import paige.navic.ui.screens.external.viewmodels.ExternalArtistViewModel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Button
import navic.composeapp.generated.resources.action_more
import navic.composeapp.generated.resources.artist_no_releases
import navic.composeapp.generated.resources.artist_scan_failed
import navic.composeapp.generated.resources.artist_try_again
import paige.navic.ui.components.common.FillText
import paige.navic.ui.components.common.Pill
import paige.navic.ui.components.common.PillTone
import paige.navic.ui.components.common.StatePill
import paige.navic.ui.components.sheets.AboutSheet
import paige.navic.ui.screens.artist.components.ARTIST_TEASER_LIMIT
import paige.navic.ui.screens.external.components.ArtistHero
import paige.navic.ui.screens.search.components.GhostRow
import paige.navic.ui.util.Fold
import paige.navic.ui.util.teaserText

/**
 * An artist the library does not have (P4).
 *
 * A heading tinted from the name (no portrait), lb-bot's About when it has one, then the
 * discography through the *same* [DiscographyShelf] the owned artist page uses. An artist lb-bot
 * has never walked is scanned on arrival (the view model's, once per visit), and the page says what
 * it is doing: scanning (a pill, ghost rows that fold away as the real ones arrive), a scan that
 * failed (lb-bot's sentence and "Try again"), no releases, or the releases. Wears the playing
 * song's colours: it has no cover of its own, and a page without one is an absence, not a hue.
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
	val meta by viewModel.meta.collectAsStateWithLifecycle()
	val aboutOpen by viewModel.aboutOpen.collectAsStateWithLifecycle()
	val preferenceManager = koinInject<PreferenceManager>()
	val platformContext = LocalPlatformContext.current
	val density = LocalDensity.current
	val scrollState = rememberScrollState()
	// The owned artist page's rule: the name moves into the bar once the heading has scrolled by.
	val scrolled by remember {
		derivedStateOf { with(density) { scrollState.value.toDp() } >= 200.dp }
	}
	val page = artistPageState(discography, loading)

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
						scrolled || page == ArtistPageState.Loading || page == ArtistPageState.Unreachable,
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
		when (page) {
			ArtistPageState.Loading -> Box(Modifier.fillMaxSize()) {
				ContainedLoadingIndicator(Modifier.size(80.dp).align(Alignment.Center))
			}

			// lb-bot out of reach, or this hub without it: the page has nothing to read.
			ArtistPageState.Unreachable -> ContentUnavailable(
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
				ArtistHero(
					name = artistName,
					scanning = page == ArtistPageState.Scanning,
					innerPadding = innerPadding,
					scrolled = scrolled
				)
				// lb-bot's About, the only one an unowned artist can have. Absent when lb-bot has
				// none; an article that arrives later rolls in rather than pushing the page down.
				val about = meta?.summary?.ifBlank { null } ?: meta?.wikidataDescription?.ifBlank { null }
				Fold(visible = about != null) {
					AboutTeaser(about.orEmpty(), onOpen = viewModel::openAbout)
				}
				// Rows standing in for the discography while the first scan runs; they roll away
				// as the real rows arrive.
				Fold(visible = page == ArtistPageState.Scanning && discography.sections.isEmpty(), tall = true) {
					ScanningGhosts(artistName)
				}
				when (page) {
					is ArtistPageState.ScanFailed -> ScanFailed(page.error, onRetry = viewModel::indexArtist)
					ArtistPageState.NoReleases -> Text(
						stringResource(Res.string.artist_no_releases),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.padding(horizontal = 20.dp)
					)
					else -> Unit
				}
				if (page == ArtistPageState.Releases || page == ArtistPageState.NoReleases ||
					(page == ArtistPageState.Scanning && discography.sections.isNotEmpty())
				) {
					DiscographyShelf(
						ui = discography,
						artistName = artistName,
						// There is always an MBID here — it is the route key.
						canIndex = artistMbid.isNotBlank(),
						onIndex = { viewModel.indexArtist() },
						onOpenEntry = { entry ->
							entry.release?.let { release ->
								// A row that names a Navidrome album is not a download any
								// more: the library holds it, and sending it to the request
								// box offers to fetch a record the user already has. This is
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

	if (aboutOpen) {
		AboutSheet(
			title = artistName,
			meta = meta,
			onDismissRequest = viewModel::dismissAbout
		)
	}
}

/** Three lines of lb-bot's About, and "More" to open the rest. */
@Composable
private fun AboutTeaser(text: String, onOpen: () -> Unit) {
	val more = stringResource(Res.string.action_more)
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClickLabel = more, role = Role.Button, onClick = onOpen)
			.padding(horizontal = 20.dp, vertical = 4.dp)
	) {
		Text(
			teaserText(text, ARTIST_TEASER_LIMIT),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 3,
			overflow = TextOverflow.Ellipsis
		)
		// Its own line, never appended to the teaser, which fills its three lines.
		Text(
			more,
			style = MaterialTheme.typography.bodySmall,
			fontWeight = FontWeight.Medium,
			color = MaterialTheme.colorScheme.primary
		)
	}
}

/** Three ghost lines (a section heading's place) and the rows a discography usually starts with. */
@Composable
private fun ScanningGhosts(seed: String) {
	val bar = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
	Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
		Column(
			Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
			verticalArrangement = Arrangement.spacedBy(6.dp)
		) {
			listOf(120.dp, 220.dp, 180.dp).forEach { width ->
				Box(Modifier.width(width).height(12.dp).clip(MaterialTheme.shapes.extraSmall).background(bar))
			}
		}
		val count = 4
		repeat(count) { index -> GhostRow(index = index, count = count, seed = "$seed-$index") }
	}
}

/** A scan lb-bot could not finish: its own sentence, and one way to ask again. Never retried by itself. */
@Composable
private fun ScanFailed(error: String, onRetry: () -> Unit) {
	Column(
		Modifier.fillMaxWidth().padding(horizontal = 20.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp)
	) {
		StatePill(Pill(FillText.Res(Res.string.artist_scan_failed), PillTone.BAD))
		Text(
			error,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant
		)
		Button(onClick = onRetry) { Text(stringResource(Res.string.artist_try_again)) }
	}
}
