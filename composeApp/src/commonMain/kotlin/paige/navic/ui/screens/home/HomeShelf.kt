package paige.navic.ui.screens.home

import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.NavKey
import org.koin.compose.koinInject
import paige.navic.di.LocalNavStack
import paige.navic.domain.manager.PreferenceManager
import paige.navic.ui.util.ARRIVAL_STAGGERED
import paige.navic.ui.util.ArrivalGate
import paige.navic.ui.util.arrive
import paige.navic.ui.util.placeholderBrush

/*
 * navi-connect P1: a shelf on Home: a header and a sideways row of 150 dp cards, each of which arrives
 * the first time the page shows it (`Arrival.kt`).
 */

/** A card's width on a shelf: `ArtCarouselItem`'s and the library home's. */
internal val ShelfCardWidth = 150.dp

/**
 * A shelf of [items]. The first shelf on the page (`shelfIndex == 0`) staggers its first five cards,
 * a left-to-right sweep; every later shelf arrives together, 150 ms in. [itemContent] is handed the
 * arrival modifier for its card and must apply it to the card's outermost node.
 */

/** A tile's lazy key and arrival key on a Home shelf: per shelf, so an album on two shelves is two tiles (audit L1-09). */
fun shelfKey(shelf: String, id: String): String = "$shelf:$id"

@Composable
fun <T> HomeShelf(
	title: String,
	items: List<T>,
	key: (T) -> Any,
	gate: ArrivalGate,
	shelfIndex: Int,
	modifier: Modifier = Modifier,
	moreLabel: String? = null,
	more: NavKey? = null,
	/** "See all" that is not a page push (Your mixes opens the Library tab on its Mixes chip). */
	onMore: (() -> Unit)? = null,
	itemContent: @Composable (item: T, arrival: Modifier) -> Unit
) {
	Column(modifier) {
		HomeShelfHeader(title, moreLabel, more, onMore)
		// The row is as tall as the cards in view, so scrolling past two-line titles changes its
		// height: it eases, as `ArtCarousel`'s does (B-134), instead of moving everything under it
		// at once (P6). Same modifier, same spec, same place before the padding.
		LazyRow(
			modifier = Modifier
				.fillMaxWidth()
				.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())
				.padding(top = 8.dp, bottom = 16.dp),
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			contentPadding = PaddingValues(horizontal = 16.dp)
		) {
			itemsIndexed(items, key = { _, item -> key(item) }) { index, item ->
				val arrival = Modifier
					.width(ShelfCardWidth)
					.arrive(gate, key(item), if (shelfIndex == 0) index else ARRIVAL_STAGGERED)
				itemContent(item, arrival)
			}
		}
	}
	// Once the first frame is up, every key the shelf holds has arrived: the ones composed in it have
	// played their arrival, and the ones still off screen must appear at once when they are scrolled
	// in, not arrive on their own.
	val keys = remember(items) { items.map(key) }
	LaunchedEffect(keys) {
		withFrameNanos { }
		gate.claimAll(keys)
	}
}

@Composable
internal fun HomeShelfHeader(title: String, moreLabel: String?, more: NavKey?, onMore: (() -> Unit)? = null) {
	val backStack = LocalNavStack.current
	Row(
		modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.SpaceBetween
	) {
		Text(
			text = title,
			style = MaterialTheme.typography.titleMediumEmphasized,
			fontWeight = FontWeight(600),
			modifier = Modifier.heightIn(min = 32.dp).semantics { heading() }
		)
		if ((more != null || onMore != null) && moreLabel != null) {
			Text(
				text = moreLabel,
				fontSize = 12.sp,
				color = MaterialTheme.colorScheme.primary,
				// 48 dp to touch and a button to TalkBack (it was a 32 dp bare text, audit L1-12).
				modifier = Modifier
					.minimumInteractiveComponentSize()
					.clickable(role = Role.Button, onClick = onMore ?: dropUnlessResumed { more?.let { backStack.add(it) } })
					.wrapContentHeight()
			)
		}
	}
}

/**
 * A shelf's loading state: [tiles] tinted boxes of the cards' final size (a cover and two text lines),
 * so that nothing jumps when the real cards arrive in their place. Each box is the tint of a name
 * made from [seed], as a real cover's is.
 */
@Composable
fun HomeShelfPlaceholder(title: String, tiles: Int, seed: String, modifier: Modifier = Modifier) {
	val shape = koinInject<PreferenceManager>().coverArtShape.shape
	val line = MaterialTheme.colorScheme.surfaceContainerHigh
	Column(modifier) {
		HomeShelfHeader(title, null, null)
		LazyRow(
			modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			contentPadding = PaddingValues(horizontal = 16.dp),
			userScrollEnabled = false
		) {
			items(tiles) { index ->
				Column(Modifier.width(ShelfCardWidth)) {
					Box(
						Modifier
							.fillMaxWidth()
							.aspectRatio(1f)
							.clip(shape)
							.background(placeholderBrush("$seed-$index"))
					)
					Spacer(Modifier.height(10.dp))
					Box(Modifier.width(100.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(line))
					Spacer(Modifier.height(6.dp))
					Box(Modifier.width(64.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(line))
				}
			}
		}
	}
}
