package paige.navic.ui.screens.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.because_discover_fresh
import navic.composeapp.generated.resources.because_discover_mood
import navic.composeapp.generated.resources.because_discover_rediscovery
import navic.composeapp.generated.resources.because_discover_similar_artists
import navic.composeapp.generated.resources.because_discover_similar_artists_generic
import navic.composeapp.generated.resources.info_discover_empty
import navic.composeapp.generated.resources.label_in_library
import navic.composeapp.generated.resources.label_not_in_library
import navic.composeapp.generated.resources.title_discover
import navic.composeapp.generated.resources.title_discover_fresh
import navic.composeapp.generated.resources.title_discover_mood
import navic.composeapp.generated.resources.title_discover_rediscovery
import navic.composeapp.generated.resources.title_discover_similar_artists
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.di.LocalBottomBarScrollManager
import paige.navic.di.LocalNavStack
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.PreferenceManager
import paige.navic.domain.models.settings.BottomBarVisibilityMode
import paige.navic.ui.components.common.RemoteCoverArt
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.components.layouts.PullToRefreshBox
import paige.navic.ui.components.layouts.RootBottomBar
import paige.navic.ui.components.layouts.RootTopBar
import paige.navic.ui.components.layouts.horizontalSection
import paige.navic.ui.core.UiState
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.discover.viewmodels.DiscoverViewModel

/**
 * Discover: one place to go, instead of six places to know about.
 *
 * Before this, the discovery features were all here and none of them was
 * somewhere you went — Fresh was a tab, "fans also like" did not exist, the
 * rediscovery set sat unlabelled in the playlist list, and mood search was a
 * sheet at the bottom of Search. Each had its own empty state, so lb-bot down,
 * AudioMuse cold and Navidrome fine all looked different.
 *
 * The rows come from [DISCOVER_ROWS], whose ids are duplicated in Feishin on
 * purpose; see that file. Every row is a [horizontalSection] carrying a reason
 * line, and `horizontalSection` hides itself when its data is empty — so a row
 * with nothing to say renders nothing, rather than a heading over a gap.
 *
 * **Theming:** this is a root tab, so `App.kt` wraps it in `Washed`. It must not
 * re-enter `NavicTheme` and must never extract per-row cover colours — one
 * shared ambient, seeded from the now-playing cover like every other browsing
 * surface. Mixed rows with per-tile palettes read as noise, which is the same
 * rule the queue follows.
 */
@Composable
fun DiscoverScreen(nested: Boolean = false) {
	val viewModel = koinViewModel<DiscoverViewModel>()
	val preferenceManager = koinInject<PreferenceManager>()
	val backStack = LocalNavStack.current
	val state by viewModel.state.collectAsStateWithLifecycle()
	val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

	// `stringResource` is composable and a LazyGridScope builder lambda is not, so
	// every reason line is resolved up here rather than at the row that uses it.
	val becauseFresh = stringResource(Res.string.because_discover_fresh)
	val becauseSimilar = if (state.similarSeed.isNotBlank()) {
		stringResource(Res.string.because_discover_similar_artists, state.similarSeed)
	} else {
		stringResource(Res.string.because_discover_similar_artists_generic)
	}
	val becauseRediscovery = stringResource(Res.string.because_discover_rediscovery)
	val becauseMood = stringResource(Res.string.because_discover_mood)
	val titleMood = stringResource(Res.string.title_discover_mood)
	val emptyText = stringResource(Res.string.info_discover_empty)

	Scaffold(
		topBar = {
			val title = @Composable { Text(stringResource(Res.string.title_discover)) }
			if (!nested) RootTopBar(title, scrollBehavior) else NestedTopBar(title)
		},
		bottomBar = {
			val scrollManager = LocalBottomBarScrollManager.current
			if (!nested ||
				preferenceManager.bottomBarVisibilityMode == BottomBarVisibilityMode.AllScreens
			) {
				RootBottomBar(scrolled = scrollManager.isTriggered)
			}
		}
	) { innerPadding ->
		PullToRefreshBox(
			modifier = Modifier.padding(top = innerPadding.calculateTopPadding()),
			finished = !state.loading,
			onRefresh = { viewModel.load() },
			key = state.supported
		) {
			LazyVerticalGrid(
				columns = GridCells.Fixed(2),
				contentPadding = PaddingValues(
					bottom = innerPadding.calculateBottomPadding() + 24.dp
				),
				verticalArrangement = Arrangement.spacedBy(4.dp)
			) {
				// Every row hides itself when it has nothing to say, so "no rows
				// at all" is a real state and needs a sentence rather than a
				// blank page. It is what a server with no lb-bot, no AudioMuse
				// and no rediscovery set looks like — a configuration answer,
				// not a fault.
				val anyContent = state.fresh.isNotEmpty() ||
					state.similarArtists.isNotEmpty() ||
					state.rediscovery.isNotEmpty() ||
					DiscoverRowId.MOOD in state.supported
				if (!state.loading && !anyContent) {
					item(span = { GridItemSpan(maxLineSpan) }) {
						Text(
							emptyText,
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.padding(16.dp)
						)
					}
				}

				DISCOVER_ROWS.forEach { row ->
					if (row.id !in state.supported) return@forEach
					when (row.id) {
						DiscoverRowId.FRESH -> horizontalSection(
							seeAll = true,
							title = Res.string.title_discover_fresh,
							destination = Screen.Fresh(nested = true),
							state = UiState.Success(state.fresh),
							key = { it.releaseGroupMbid.ifBlank { it.releaseName + it.artist } },
							because = becauseFresh
						) { release ->
							DiscoverAlbumTile(
								title = release.releaseName,
								subtitle = release.artist,
								coverUrl = release.coverUrl
									.ifBlank { LbBotManager.caaCoverUrl(release.releaseGroupMbid) },
								owned = release.releaseOwned,
								modifier = Modifier.animateItem().width(150.dp),
								onClick = {
									// An owned release opens the library album
									// directly: `releaseAlbumId` exists so a row
									// badged "in library" need not route through
									// the virtual page and wait for a redirect
									// that cannot fire for an album lb-bot filled
									// itself.
									val albumId = release.releaseAlbumId
									if (release.releaseOwned && albumId.isNotBlank()) {
										backStack.add(Screen.CollectionDetail(albumId, "discover"))
									} else {
										backStack.add(
											Screen.ExternalAlbum(
												rgid = release.releaseGroupMbid,
												artistMbid = release.artistMbids.firstOrNull() ?: "",
												artistName = release.artist,
												title = release.releaseName,
												artistId = if (release.artistOwned) {
													release.artistId
												} else ""
											)
										)
									}
								}
							)
						}

						DiscoverRowId.SIMILAR_ARTISTS -> horizontalSection(
							seeAll = false,
							title = Res.string.title_discover_similar_artists,
							destination = null,
							state = UiState.Success(
								// Someone with neither a library id nor an MBID
								// has nowhere to go; that is a name, not a row.
								state.similarArtists.filter {
									it.artistId.isNotBlank() || it.mbid.isNotBlank()
								}
							),
							key = { it.mbid.ifBlank { it.name } },
							because = becauseSimilar
						) { artist ->
							DiscoverTextTile(
								title = artist.name,
								subtitle = stringResource(
									if (artist.owned) {
										Res.string.label_in_library
									} else {
										Res.string.label_not_in_library
									}
								),
								modifier = Modifier.animateItem().width(150.dp),
								onClick = {
									// Owned goes to the real artist page; unowned
									// to the virtual `mb:` one, which renders
									// their discography from lb-bot and is where
									// a fill starts.
									if (artist.artistId.isNotBlank()) {
										backStack.add(Screen.ArtistDetail(artist.artistId))
									} else {
										backStack.add(
											Screen.ExternalArtist(artist.mbid, artist.name)
										)
									}
								}
							)
						}

						DiscoverRowId.REDISCOVERY -> horizontalSection(
							seeAll = false,
							title = Res.string.title_discover_rediscovery,
							destination = null,
							state = UiState.Success(state.rediscovery),
							key = { it.playlistId },
							because = becauseRediscovery
						) { playlist ->
							DiscoverTextTile(
								title = (playlist.name ?: "")
									.removePrefix(
										paige.navic.domain.manager.RediscoveryPlaylists.PREFIX
									),
								// The definition's own one-line description,
								// written into the Navidrome playlist comment
								// when it was created and never surfaced
								// anywhere until now. A ready-made reason line,
								// per playlist.
								subtitle = playlist.comment.orEmpty(),
								modifier = Modifier.animateItem().width(150.dp),
								onClick = {
									backStack.add(
										Screen.CollectionDetail(playlist.playlistId, "discover")
									)
								}
							)
						}

						DiscoverRowId.MOOD -> item(span = { GridItemSpan(maxLineSpan) }) {
							// Not a list of results but an entry point: the CLAP
							// search is reachable only from the bottom of the
							// Search screen today, which means only people who
							// already know it exists ever find it.
							Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
								Text(
									titleMood,
									style = MaterialTheme.typography.titleMediumEmphasized
								)
								Text(
									becauseMood,
									style = MaterialTheme.typography.bodyMedium,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
									modifier = Modifier
										.padding(top = 2.dp)
										.clickable { backStack.add(Screen.Search()) }
								)
							}
						}
					}
				}
			}
		}
	}
}

/** A cover tile. Unowned art is dimmed, matching the missing-album tiles on the
 *  artist page — the same visual grammar for the same fact. */
@Composable
private fun DiscoverAlbumTile(
	title: String,
	subtitle: String,
	coverUrl: String,
	owned: Boolean,
	modifier: Modifier = Modifier,
	onClick: () -> Unit
) {
	Column(modifier.fillMaxWidth()) {
		RemoteCoverArt(
			url = coverUrl,
			contentDescription = title,
			onClick = onClick,
			modifier = Modifier.fillMaxWidth()
		)
		Text(
			title,
			style = MaterialTheme.typography.bodyMedium,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.padding(top = 6.dp)
		)
		Text(
			subtitle,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis
		)
		if (!owned) {
			Text(
				stringResource(Res.string.label_not_in_library),
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1
			)
		}
	}
}

/** A tile with no artwork behind it — an artist lb-bot named, or a smart
 *  playlist. No fabricated placeholder art: an absent cover is an absence. */
@Composable
private fun DiscoverTextTile(
	title: String,
	subtitle: String,
	modifier: Modifier = Modifier,
	onClick: () -> Unit
) {
	Column(
		modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(vertical = 8.dp)
	) {
		Text(
			title,
			style = MaterialTheme.typography.bodyMedium,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis
		)
		if (subtitle.isNotBlank()) {
			Text(
				subtitle,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.padding(top = 2.dp)
			)
		}
	}
}
