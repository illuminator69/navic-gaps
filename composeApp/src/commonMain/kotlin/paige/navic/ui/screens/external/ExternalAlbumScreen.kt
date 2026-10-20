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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.kyant.capsule.ContinuousCapsule
import com.kyant.capsule.ContinuousRoundedRectangle
import org.koin.compose.koinInject
import paige.navic.di.LocalBottomBarScrollManager
import paige.navic.di.LocalPlatformContext
import paige.navic.di.isLandscape
import paige.navic.domain.manager.LbTrack
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.models.settings.BottomBarVisibilityMode
import paige.navic.icons.outlined.Artist
import paige.navic.icons.outlined.Download
import paige.navic.ui.components.common.SegmentedListItem
import paige.navic.ui.components.common.SegmentedListItemDefaults.segmentedShapes
import paige.navic.ui.components.common.SongRowDefaults
import paige.navic.ui.components.layouts.RootBottomBar
import paige.navic.ui.theme.defaultFont
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.request_found_from_link
import navic.composeapp.generated.resources.request_on_wishlist
import navic.composeapp.generated.resources.title_wishlist
import navic.composeapp.generated.resources.action_preview_track
import navic.composeapp.generated.resources.preview_none_found
import navic.composeapp.generated.resources.action_view_artist
import navic.composeapp.generated.resources.info_external_album_index_failed
import navic.composeapp.generated.resources.title_missing_album
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import paige.navic.di.LocalNavStack
import paige.navic.domain.manager.LbBotManager
import paige.navic.ui.components.common.CoverAmbientBackground
import paige.navic.ui.components.common.RemoteCoverArt
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.icons.Icons
import paige.navic.icons.filled.Play
import paige.navic.ui.components.sheets.AboutSheet
import paige.navic.ui.components.common.Pill
import paige.navic.ui.components.common.PillTone
import paige.navic.ui.components.common.FillText
import paige.navic.ui.components.common.StatePill
import paige.navic.ui.screens.collection.components.collectionDetailScreenAboutRow
import paige.navic.ui.screens.external.components.RequestBox
import paige.navic.icons.outlined.Favorite
import paige.navic.icons.filled.Favorite
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.external.viewmodels.ArtistTarget
import paige.navic.ui.screens.external.Support
import paige.navic.ui.screens.external.viewmodels.ExternalAlbumViewModel
import paige.navic.ui.theme.NavicTheme
import paige.navic.di.ForceSystemBars
import paige.navic.ui.util.coverAmbientGradient
import paige.navic.ui.util.onAmbientColor
import paige.navic.ui.util.rememberAppIsDark
import paige.navic.ui.util.rememberCoverColorScheme

/**
 * A release the library does not have: its tracklist, and the way to get it.
 *
 * The way to get it is the request box under the heading ([RequestBox], P4): find sources,
 * choose, request, watch and recover without leaving the page. It took over the missing-album
 * sheet's state and its traps (coverage measured against the canonical tracklist and not a file
 * count, the "is this even the right album" verdict, sending the resolved edition so lb-bot
 * doesn't overrule the choice and doesn't re-enter its MusicBrainz cooldown); they live in
 * `ExternalAlbumViewModel` now. Under the box, lb-bot's About for the release-group when it has
 * one.
 *
 * `presenceKnown` comes back false with no Navidrome album ids, which is the normal
 * case here. Every track is missing; that is not an error, and it must not render
 * as "0/12".
 */
@Composable
fun ExternalAlbumScreen(
	rgid: String,
	artistMbid: String,
	artistName: String,
	title: String,
	artistId: String = "",
	/** Reached from a pasted link: the heading says so. */
	fromLink: Boolean = false
) {
	val viewModel = koinViewModel<ExternalAlbumViewModel>(
		key = rgid,
		parameters = { parametersOf(rgid, artistMbid, artistName, artistId, title) }
	)
	val backStack = LocalNavStack.current
	val state by viewModel.state.collectAsStateWithLifecycle()
	val previewsAvailable by viewModel.previewsAvailable.collectAsStateWithLifecycle()
	val previewBusy by viewModel.previewBusy.collectAsStateWithLifecycle()
	val previewMissing by viewModel.previewMissing.collectAsStateWithLifecycle()
	val lbBot = koinInject<LbBotManager>()
	val scope = rememberCoroutineScope()
	// The fills this device knows of; the box reads its own row out of them by release-group.
	val ledger by lbBot.ledger.collectAsState()

	// The library turns out to hold this release-group after all — only lb-bot's
	// index knows which Navidrome album that is, so this can only be answered once
	// the index row has been read (or added).
	// This page's own entry is replaced, found by its release-group: a request that lands while
	// the page is open (round 3) can resolve with another page pushed over it.
	LaunchedEffect(state.ownedAlbumId) {
		state.ownedAlbumId?.let { albumId ->
			val index = backStack.indexOfLast { (it as? Screen.ExternalAlbum)?.rgid == rgid }
			if (index >= 0) backStack[index] = Screen.CollectionDetail(albumId, "artist")
		}
	}

	// lb-bot's detail when it has answered; before that the index mirror's (read from Room
	// before any request leaves), and only then whatever the route carried.
	val heading = state.detail?.title?.ifBlank { null }
		?: state.mirrorTitle.ifBlank { null }
		?: title
	val artist = state.detail?.artist?.ifBlank { null }
		?: state.mirrorArtist.ifBlank { null }
		?: artistName

	// Resolved in the viewmodel — library first, MusicBrainz second, nothing third
	// — so both affordances below agree and neither has to re-derive it. Null means
	// the artist genuinely cannot be identified, which is the one case where the
	// name must stay plain text.
	val openArtist: (() -> Unit)? = when (val target = state.artistTarget) {
		is ArtistTarget.Library -> {
			{ backStack.add(Screen.ArtistDetail(target.artistId)) }
		}

		is ArtistTarget.External -> {
			{ backStack.add(Screen.ExternalArtist(target.artistMbid, target.name.ifBlank { artist })) }
		}

		null -> null
	}

	// This page and its artist sibling were the only content-bearing destinations
	// in the whole graph with no cover theming at all — reached straight from
	// fully-washed surfaces (the similar-albums row, the discography shelf, the
	// Fresh grid), so arriving here was an abrupt drop to the flat app scheme.
	//
	// It follows the DETAIL-screen pattern rather than `Washed`: `BrowsingAmbient`
	// takes no argument and seeds from the NOW-PLAYING cover, which would have
	// tinted this page by whatever song happens to be playing instead of by the
	// record on screen.
	//
	// Seeded from the Cover Art Archive URL, because an unowned release has no
	// Navidrome cover id — that is what `paletteUrl` exists for. If the art does
	// not resolve, `themed` stays false and the page is the app's own surface: an
	// unresolved cover is an absence, not a colour, and must never be fabricated.
	val appIsDark = rememberAppIsDark()
	val coverColors = rememberCoverColorScheme(
		coverArtId = null,
		isDark = appIsDark,
		paletteUrl = LbBotManager.caaCoverUrl(rgid).ifBlank { null }
	)
	val (washTop, _) = coverAmbientGradient(coverColors.seed, coverColors.isDark)
	val ambientTop = if (coverColors.themed) washTop else MaterialTheme.colorScheme.surface
	ForceSystemBars(coverColors.isDark)
	NavicTheme(coverColors.scheme, contentColor = onAmbientColor(ambientTop, coverColors.scheme)) {
	Box(Modifier.fillMaxSize()) {
	if (coverColors.themed) {
		// `coverArtId = null`, so this is the gradient wash WITHOUT the blurred
		// artwork layer behind it: `BlendBackground` draws through Coil from a
		// Navidrome cover id, and this release has none. The colour is still
		// genuinely the sleeve's — it is quantised from the same CAA image the
		// header shows — which is the part that matters. Teaching the blur layer
		// to take a URL as well is a separate change.
		CoverAmbientBackground(
			coverArtId = null,
			seed = coverColors.seed,
			isDark = coverColors.isDark,
			modifier = Modifier.fillMaxSize()
		)
	}
	val listState = rememberLazyListState()
	// The album page's rule: the title moves into the bar once the cover card has scrolled by.
	val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
	val preferenceManager = koinInject<PreferenceManager>()
	val platformContext = LocalPlatformContext.current
	val tracks = state.tracklist?.tracks.orEmpty()
	Scaffold(
		containerColor = if (coverColors.themed) Color.Transparent else ambientTop,
		topBar = {
			// The album page's bar: transparent over the cover card, the page's surface once
			// the list has scrolled under it.
			val barAlpha by animateFloatAsState(if (listState.canScrollBackward) 1f else 0f)
			NestedTopBar(colors = TopAppBarDefaults.topAppBarColors(
				containerColor = ambientTop.copy(alpha = barAlpha)
			), title = {
				AnimatedVisibility(scrolled, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
					Text(heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
				}
			})
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
		// The album page's layout: the cover card and its title over the wash, the buttons in
		// its heading row's capsules, then the tracks as its segmented rows.
		LazyColumn(
			state = listState,
			modifier = Modifier.fillMaxSize(),
			contentPadding = innerPadding + PaddingValues(bottom = 24.dp)
		) {
			item(key = "heading") {
				ExternalAlbumHeading(
					rgid = rgid,
					title = heading,
					artist = artist,
					fromLink = fromLink,
					onArtist = openArtist
				)
			}
			item(key = "buttons") {
				ExternalAlbumButtons(
					onViewArtist = openArtist,
					// Album-level, on the routes that exist (lb-bot's wishlist is per
					// release-group); only once the page knows where this album stands.
					wishlisted = state.request.wishlisted,
					onToggleWishlist = viewModel::toggleWishlist
				)
			}
			item(key = "request") {
				RequestBox(
					support = when {
						!lbBot.isConfigured -> Support.ABSENT
						!lbBot.supportsSourcePicker -> Support.NEEDS_HUB_RESTART
						else -> Support.YES
					},
					fill = ledger.firstOrNull { it.key == rgid },
					indexed = state.indexed,
					request = state.request,
					detail = state.detail,
					wishlistAvailable = lbBot.supportsWishlist && state.request.wishlisted != true,
					ownedAlbumId = state.ownedAlbumId,
					onFindSources = viewModel::findSources,
					onSelectEdition = viewModel::selectEdition,
					onSelectSource = viewModel::selectSource,
					onRequest = viewModel::request,
					onQuality = viewModel::setQuality,
					onAction = viewModel::fillAction,
					onOpenAlbum = { albumId -> backStack.add(Screen.CollectionDetail(albumId, "artist")) },
					// A stage of another height (a tall source list giving way to a short request)
					// left the box above the view with the page on the tracklist (seen on the AVD).
					// When the box's top is scrolled off, it is brought back as it changes.
					onStageKindChanged = {
						val first = listState.firstVisibleItemIndex
						if (first > REQUEST_ITEM || (first == REQUEST_ITEM && listState.firstVisibleItemScrollOffset > 0)) {
							scope.launch { listState.animateScrollToItem(REQUEST_ITEM) }
						}
					}
				)
			}
			// lb-bot's About, the only one an unowned album can have. No answer is no row and
			// no gap; an article that arrives later comes in under the box.
			collectionDetailScreenAboutRow(
				meta = state.meta,
				fallbackNotes = null,
				onOpen = viewModel::openAbout
			)
			// Not fatal, and worth saying: the album is still fully downloadable through the
			// picker above. What is lost is the index row, so this release keeps listing as
			// missing on the artist page until a rescan.
			if (state.indexAddFailed) {
				item(key = "index-failed") {
					Text(
						stringResource(Res.string.info_external_album_index_failed),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						textAlign = TextAlign.Center,
						modifier = Modifier.fillMaxWidth().padding(horizontal = 31.dp, vertical = 4.dp)
					)
				}
			}
			if (state.loading) {
				item(key = "loading") {
					Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), Alignment.Center) {
						ContainedLoadingIndicator(Modifier.size(48.dp))
					}
				}
			} else {
				// No `present` counting anywhere: presenceKnown is false here by definition,
				// so every row is just the canonical tracklist.
				itemsIndexed(tracks, key = { _, track -> "t:${track.position}:${track.title}" }) { index, track ->
					ExternalTrackRow(
						track = track,
						shapes = segmentedShapes(index = index, count = tracks.size),
						previewsAvailable = previewsAvailable,
						busy = previewBusy == track.position,
						anyBusy = previewBusy != null,
						missing = previewMissing == track.position,
						onPreview = { viewModel.preview(track.position, track.title) }
					)
				}
			}
		}
	}
	}  // Box
	}  // NavicTheme

	if (state.aboutOpen) {
		AboutSheet(
			title = heading,
			meta = state.meta,
			onDismissRequest = viewModel::dismissAbout,
			coverArtId = null,
			// Washed in the sleeve, as the page is: an unowned release has no Navidrome cover id.
			paletteUrl = LbBotManager.caaCoverUrl(rgid).ifBlank { null }
		)
	}
}

/**
 * The album page's heading (`CollectionDetailScreenHeadingRow`) for a release with no Navidrome
 * cover: the Cover Art Archive's front as the floating card, the title, the artist as a link
 * wherever there is somewhere for it to go, and where the album stands.
 */
@Composable
private fun ExternalAlbumHeading(
	rgid: String,
	title: String,
	artist: String,
	fromLink: Boolean,
	onArtist: (() -> Unit)?
) {
	val coverCardShape = ContinuousRoundedRectangle(18.dp)
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.padding(top = 24.dp, bottom = 6.dp),
		contentAlignment = Alignment.Center
	) {
		RemoteCoverArt(
			url = LbBotManager.caaCoverUrl(rgid),
			contentDescription = title,
			shape = coverCardShape,
			modifier = Modifier
				.size(212.dp)
				.dropShadow(coverCardShape, Shadow(radius = 30.dp, color = Color.Black, alpha = 0.5f))
		)
	}
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 31.dp)
			.padding(top = 10.dp, bottom = 8.dp),
		horizontalAlignment = Alignment.CenterHorizontally
	) {
		Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
		// The name itself is a control wherever the artist can be identified: a credit beside
		// an album is the thing a person reaches for.
		if (artist.isNotBlank()) {
			Text(
				artist,
				color = if (onArtist != null) MaterialTheme.colorScheme.primary
				else LocalContentColor.current.copy(alpha = 0.7f),
				style = MaterialTheme.typography.bodyMedium,
				fontFamily = defaultFont(grade = 100, round = 100f),
				modifier = if (onArtist != null) Modifier.clickable(onClick = onArtist) else Modifier
			)
		}
		Text(
			stringResource(Res.string.title_missing_album),
			color = LocalContentColor.current.copy(alpha = 0.7f),
			style = MaterialTheme.typography.bodySmall,
			fontFamily = defaultFont(grade = 100, round = 100f)
		)
		if (fromLink) {
			StatePill(
				Pill(FillText.Res(Res.string.request_found_from_link), PillTone.NEUTRAL),
				modifier = Modifier.padding(top = 8.dp)
			)
		}
	}
}

/**
 * The album page's heading buttons (`CollectionDetailScreenHeadingRowButtons`): a glass capsule
 * for the artist, kept as a button because a coloured name is only obvious once you have already
 * guessed, and the wishlist, lb-bot's per release-group (there is no artist-level one). The
 * artist's is left out when nothing identifies the artist, the wishlist's while the page does not
 * know where the album stands ([wishlisted] null: not read yet, or a hub without the routes).
 * Finding sources is the request box's, under these.
 */
@Composable
private fun ExternalAlbumButtons(
	onViewArtist: (() -> Unit)?,
	wishlisted: Boolean?,
	onToggleWishlist: () -> Unit
) {
	if (onViewArtist == null && wishlisted == null) return
	Row(
		modifier = Modifier.padding(horizontal = 31.dp, vertical = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp)
	) {
		val glassColors = ButtonDefaults.outlinedButtonColors(
			containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f)
		)
		onViewArtist?.let { open ->
			OutlinedButton(
				modifier = Modifier.weight(1f).height(44.dp),
				onClick = open,
				shape = ContinuousCapsule,
				colors = glassColors
			) {
				Icon(Icons.Outlined.Artist, null, modifier = Modifier.size(22.dp).padding(end = 4.dp))
				Text(
					stringResource(Res.string.action_view_artist),
					maxLines = 1,
					fontFamily = defaultFont(round = 100f)
				)
			}
		}
		wishlisted?.let { listed ->
			OutlinedButton(
				modifier = Modifier.weight(1f).height(44.dp),
				onClick = onToggleWishlist,
				shape = ContinuousCapsule,
				colors = glassColors
			) {
				Icon(
					if (listed) Icons.Filled.Favorite else Icons.Outlined.Favorite,
					null,
					modifier = Modifier.size(22.dp).padding(end = 4.dp)
				)
				Text(
					stringResource(if (listed) Res.string.request_on_wishlist else Res.string.title_wishlist),
					maxLines = 1,
					autoSize = TextAutoSize.StepBased(minFontSize = 1.sp, maxFontSize = 15.sp),
					fontFamily = defaultFont(round = 100f)
				)
			}
		}
	}
}

/**
 * One track of the canonical tracklist, as the album page's segmented row: its number, its
 * title, and a 30-second preview where the sidecar is there. "Not found" stays on the row that
 * asked, because an empty resolve is an answer about that track, not a failure of the page.
 */
@Composable
private fun ExternalTrackRow(
	track: LbTrack,
	shapes: ListItemShapes,
	previewsAvailable: Boolean,
	busy: Boolean,
	anyBusy: Boolean,
	missing: Boolean,
	onPreview: () -> Unit
) {
	SegmentedListItem(
		modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.5.dp),
		contentPadding = SongRowDefaults.ContentPadding,
		onClick = onPreview,
		enabled = previewsAvailable && !anyBusy,
		shapes = shapes,
		colors = ListItemDefaults.segmentedColors(
			containerColor = SongRowDefaults.containerColor(false),
			contentColor = SongRowDefaults.contentColor(false),
			disabledContainerColor = SongRowDefaults.containerColor(false),
			disabledContentColor = SongRowDefaults.contentColor(false),
			disabledLeadingContentColor = SongRowDefaults.supportingContentColor(false),
			disabledSupportingContentColor = SongRowDefaults.supportingContentColor(false),
			disabledTrailingContentColor = SongRowDefaults.supportingContentColor(false)
		),
		verticalAlignment = Alignment.CenterVertically,
		leadingContent = {
			Text(
				text = track.position.toString(),
				modifier = Modifier.width(25.dp),
				style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
				color = SongRowDefaults.supportingContentColor(false),
				maxLines = 1,
				textAlign = TextAlign.Center,
				autoSize = TextAutoSize.StepBased(6.sp, 13.sp)
			)
		},
		content = {
			Column {
				Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
				if (missing) {
					Text(stringResource(Res.string.preview_none_found), style = MaterialTheme.typography.bodySmall)
				}
			}
		},
		// Offered only when the sidecar is actually there. Absent previews render nothing at
		// all, the same rule the rest of the lb-bot layer follows.
		trailingContent = if (previewsAvailable) {
			{
				if (busy) {
					CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
				} else {
					Icon(
						Icons.Filled.Play,
						contentDescription = stringResource(Res.string.action_preview_track),
						modifier = Modifier.size(20.dp)
					)
				}
			}
		} else null
	)
}

/** The request box's place in the page's list: after the heading and its buttons. */
private const val REQUEST_ITEM = 2
