package paige.navic.ui.screens.wishlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_remove_from_wishlist
import navic.composeapp.generated.resources.info_wishlist_empty
import navic.composeapp.generated.resources.info_wishlist_empty_hint
import navic.composeapp.generated.resources.info_wishlist_unsupported
import navic.composeapp.generated.resources.title_wishlist
import navic.composeapp.generated.resources.wishlist_attempts
import navic.composeapp.generated.resources.wishlist_never_searched
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import paige.navic.di.LocalNavStack
import paige.navic.domain.manager.LbWishlistItem
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Delete
import paige.navic.icons.outlined.Download
import paige.navic.ui.components.common.ContentUnavailable
import paige.navic.ui.components.common.Form
import paige.navic.ui.components.common.FormRow
import paige.navic.ui.components.common.RemoteCoverArt
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.components.layouts.PullToRefreshBox
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.wishlist.viewmodels.WishlistViewModel

/**
 * The wishlist: what you asked for and nobody had.
 *
 * This is the one surface where lb-bot's `retryable: false` becomes an action
 * rather than a dead end. A `no_source` failure deliberately never auto-retries,
 * because re-running an identical ranked search against identical peers fails
 * identically and immediately — so the retry that does make sense is a slow one,
 * measured in hours, run by lb-bot rather than by a button here.
 *
 * Which is why this screen has no "retry" control. It shows what is still being
 * looked for and how hard, and lets you stop wanting it. A landing arrives as the
 * hub's ordinary `library` frame and removes the row by itself.
 */
@Composable
fun WishlistScreen() {
	val viewModel = koinViewModel<WishlistViewModel>()
	val state by viewModel.state.collectAsStateWithLifecycle()
	val backStack = LocalNavStack.current

	Scaffold(
		topBar = { NestedTopBar({ Text(stringResource(Res.string.title_wishlist)) }) },
		contentWindowInsets = WindowInsets.statusBars
	) { innerPadding ->
		PullToRefreshBox(
			modifier = Modifier.padding(top = innerPadding.calculateTopPadding()),
			finished = !state.loading,
			onRefresh = { viewModel.load() },
			key = state.items.size
		) {
			Column(
				Modifier
					.fillMaxSize()
					.verticalScroll(rememberScrollState())
					.padding(top = 8.dp, start = 16.dp, end = 16.dp, bottom = 32.dp)
			) {
				when {
					state.loading && state.items.isEmpty() -> Box(
						Modifier.fillMaxWidth().padding(vertical = 64.dp),
						contentAlignment = Alignment.Center
					) { CircularProgressIndicator() }

					// lb-bot absent or too old for the route. Says which, rather than
					// showing an empty list that looks like "you want nothing".
					!state.supported -> ContentUnavailable(
						modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
						icon = Icons.Outlined.Download,
						label = stringResource(Res.string.info_wishlist_unsupported)
					)

					state.items.isEmpty() -> ContentUnavailable(
						modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
						icon = Icons.Outlined.Download,
						label = stringResource(Res.string.info_wishlist_empty),
						description = stringResource(Res.string.info_wishlist_empty_hint)
					)

					else -> Form {
						state.items.forEach { item ->
							WishlistRow(
								item = item,
								onClick = {
									backStack.add(
										Screen.ExternalAlbum(
											rgid = item.rgid,
											artistName = item.artist,
											title = item.title
										)
									)
								},
								onRemove = { viewModel.remove(item.rgid) }
							)
						}
					}
				}
			}
		}
	}
}

@Composable
private fun WishlistRow(
	item: LbWishlistItem,
	onClick: () -> Unit,
	onRemove: () -> Unit
) {
	FormRow(onClick = onClick) {
		RemoteCoverArt(
			url = item.coverUrl,
			modifier = Modifier.size(44.dp).padding(end = 12.dp),
			contentDescription = null
		)
		Column(Modifier.weight(1f)) {
			Text(
				item.title.ifBlank { item.artist }.ifBlank { item.rgid },
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
			Text(
				item.artist,
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis
			)
			// How hard lb-bot has looked. "Still looking" and "looked forty times"
			// are different answers to "why have I not got this yet", and without
			// the count the row reads as though nothing is happening at all.
			Text(
				if (item.attempts > 0) {
					stringResource(Res.string.wishlist_attempts, item.attempts)
				} else {
					stringResource(Res.string.wishlist_never_searched)
				},
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
			// lb-bot's own words for the last attempt. It is additive, outside the
			// frozen contract and blank before the first re-search, so it renders
			// only when there is something to say — but when there is, it is the
			// difference between "still nobody has it" and a reason worth acting on.
			if (item.lastReason.isNotBlank()) {
				Text(
					item.lastReason,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis
				)
			}
		}
		IconButton(onClick = onRemove) {
			Icon(
				Icons.Outlined.Delete,
				contentDescription = stringResource(Res.string.action_remove_from_wishlist),
				modifier = Modifier.size(20.dp)
			)
		}
	}
}
