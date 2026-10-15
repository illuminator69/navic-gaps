package paige.navic.ui.screens.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_remove_from_wishlist
import navic.composeapp.generated.resources.info_wishlist_empty
import navic.composeapp.generated.resources.info_wishlist_empty_hint
import navic.composeapp.generated.resources.info_wishlist_unsupported
import navic.composeapp.generated.resources.wishlist_attempts
import navic.composeapp.generated.resources.wishlist_never_searched
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.domain.manager.LbWishlistItem
import paige.navic.domain.manager.PreferenceManager
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Delete
import paige.navic.icons.outlined.Download
import paige.navic.ui.components.common.ContentUnavailable
import paige.navic.ui.components.common.RemoteCoverArt
import paige.navic.ui.components.common.SegmentedListItem
import paige.navic.ui.components.common.SegmentedListItemDefaults.segmentedShapes
import paige.navic.ui.components.common.SongRowDefaults
import paige.navic.ui.components.common.SwipeToDismissBox
import paige.navic.ui.screens.downloads.viewmodels.WishlistUi

/**
 * The wishlist: what you asked for and nobody had.
 *
 * This is the one surface where lb-bot's `retryable: false` becomes an action
 * rather than a dead end. A `no_source` failure deliberately never auto-retries,
 * because re-running an identical ranked search against identical peers fails
 * identically and immediately — so the retry that does make sense is a slow one,
 * measured in hours, run by lb-bot rather than by a button here.
 *
 * Which is why this list has no "retry" control. It shows what is still being
 * looked for and how hard, and lets you stop wanting it: a swipe, as a song leaves
 * the queue. A landing arrives as the hub's ordinary `library` frame and removes
 * the row by itself.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun LazyListScope.wishlistSection(
	state: WishlistUi,
	onOpen: (LbWishlistItem) -> Unit,
	onRemove: (LbWishlistItem) -> Unit
) {
	when {
		state.loading && state.items.isEmpty() -> item(key = "loading") {
			Box(
				Modifier
					.fillMaxWidth()
					.padding(vertical = 64.dp),
				contentAlignment = Alignment.Center
			) { ContainedLoadingIndicator() }
		}

		// lb-bot absent or too old for the route. Says which, rather than
		// showing an empty list that looks like "you want nothing".
		!state.supported -> item(key = "unsupported") {
			ContentUnavailable(
				modifier = Modifier
					.fillMaxWidth()
					.padding(vertical = 64.dp),
				icon = Icons.Outlined.Download,
				label = stringResource(Res.string.info_wishlist_unsupported)
			)
		}

		state.items.isEmpty() -> item(key = "empty") {
			ContentUnavailable(
				modifier = Modifier
					.fillMaxWidth()
					.padding(vertical = 64.dp),
				icon = Icons.Outlined.Download,
				label = stringResource(Res.string.info_wishlist_empty),
				description = stringResource(Res.string.info_wishlist_empty_hint)
			)
		}

		else -> itemsIndexed(state.items, key = { _, item -> item.rgid }) { index, item ->
			WishlistRow(
				item = item,
				index = index,
				count = state.items.size,
				onClick = { onOpen(item) },
				onRemove = { onRemove(item) },
				modifier = Modifier.animateItem()
			)
		}
	}
}

@Composable
private fun WishlistRow(
	item: LbWishlistItem,
	index: Int,
	count: Int,
	onClick: () -> Unit,
	onRemove: () -> Unit,
	modifier: Modifier = Modifier
) {
	val preferenceManager = koinInject<PreferenceManager>()
	val dismissState = rememberSwipeToDismissBoxState()
	val scope = rememberCoroutineScope()
	val shapes: ListItemShapes = segmentedShapes(
		index = index,
		count = count,
		dismissDirection = dismissState.dismissDirection
	)

	SwipeToDismissBox(
		modifier = modifier,
		state = dismissState,
		onDismiss = {
			onRemove()
			// The row goes when lb-bot's answer drops it from the list. A remove that did not
			// take leaves the list as it was, and the row slides back rather than vanishing
			// until the next refresh brings it back.
			scope.launch { dismissState.reset() }
		},
		backgroundContent = {
			Box(
				modifier = Modifier
					.fillMaxSize()
					.clip(shapes.shape)
					.background(
						if (dismissState.dismissDirection == SwipeToDismissBoxValue.Settled)
							Color.Transparent
						else MaterialTheme.colorScheme.errorContainer
					)
					.padding(horizontal = 20.dp)
			) {
				Icon(
					imageVector = Icons.Outlined.Delete,
					contentDescription = stringResource(Res.string.action_remove_from_wishlist),
					tint = MaterialTheme.colorScheme.onErrorContainer,
					modifier = Modifier.align(
						when (dismissState.dismissDirection) {
							SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
							else -> Alignment.CenterEnd
						}
					)
				)
			}
		}
	) {
		SegmentedListItem(
			onClick = onClick,
			shapes = shapes,
			colors = downloadsRowColors(),
			contentPadding = SongRowDefaults.ContentPadding,
			leadingContent = {
				RemoteCoverArt(
					url = item.coverUrl,
					modifier = Modifier.size(50.dp),
					shape = preferenceManager.coverArtShape.decreasedShape
				)
			},
			content = {
				Text(
					item.title.ifBlank { item.artist }.ifBlank { item.rgid },
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
			},
			supportingContent = {
				Column {
					Text(item.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
					// How hard lb-bot has looked. "Still looking" and "looked forty times"
					// are different answers to "why have I not got this yet", and without
					// the count the row reads as though nothing is happening at all.
					Text(
						if (item.attempts > 0) {
							stringResource(Res.string.wishlist_attempts, item.attempts)
						} else {
							stringResource(Res.string.wishlist_never_searched)
						},
						style = MaterialTheme.typography.bodySmall
					)
					// lb-bot's own words for the last attempt. It is additive, outside the
					// frozen contract and blank before the first re-search, so it renders
					// only when there is something to say — but when there is, it is the
					// difference between "still nobody has it" and a reason worth acting on.
					if (item.lastReason.isNotBlank()) {
						Text(
							item.lastReason,
							style = MaterialTheme.typography.bodySmall,
							maxLines = 2,
							overflow = TextOverflow.Ellipsis
						)
					}
				}
			}
		)
	}
}
