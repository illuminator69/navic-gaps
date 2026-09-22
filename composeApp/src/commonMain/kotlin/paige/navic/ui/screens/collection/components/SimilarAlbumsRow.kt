package paige.navic.ui.screens.collection.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.dropUnlessResumed
import paige.navic.di.LocalNavStack
import paige.navic.domain.manager.LbSimilarAlbum
import paige.navic.domain.manager.LbSimilarAlbums
import paige.navic.ui.components.common.RemoteCoverArt
import paige.navic.ui.navigation.Screen

/**
 * "Similar albums" — the first shelf this screen has ever had.
 *
 * lb-bot's `/lb/album/similar` route, its two-source similarity engine
 * (ListenBrainz, cross-checked with Last.fm), the hub whitelist and the 24h
 * cache have all been shipped since the Fresh work and consumed by no client:
 * it was step 5 of DESIGN-lbbot-client-integration.md, left unticked.
 *
 * Every row is a record the library **already holds**, so this is rediscovery
 * rather than a shopping list, and it is not a download surface.
 *
 * `because` is rendered, always. The rule this stack follows everywhere is that
 * a recommendation names the thing that justifies it; an unattributed shelf is
 * indistinguishable from a popularity chart.
 *
 * Absent rather than empty when lb-bot is not there (§7): no hub, an unindexed
 * library, or no similar artist in it all end at "render nothing".
 */
fun LazyListScope.collectionDetailScreenSimilarAlbumsRow(similar: LbSimilarAlbums?) {
	val albums = similar?.albums.orEmpty()
	if (albums.isEmpty()) return
	item {
		Column(Modifier.fillMaxWidth()) {
			Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
				Text(
					"Similar albums",
					style = MaterialTheme.typography.titleMediumEmphasized,
					fontWeight = FontWeight(600),
					color = MaterialTheme.colorScheme.primary,
					modifier = Modifier.padding(top = 12.dp)
				)
				similar?.because?.takeIf { it.isNotBlank() }?.let { because ->
					Text(
						"Because you're listening to $because",
						fontSize = 12.sp,
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
				}
			}
			LazyRow(
				modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp),
				horizontalArrangement = Arrangement.spacedBy(12.dp),
				contentPadding = PaddingValues(horizontal = 16.dp)
			) {
				items(albums, key = { it.rgid }) { album -> SimilarAlbumTile(album) }
			}
		}
	}
}

@Composable
private fun SimilarAlbumTile(album: LbSimilarAlbum) {
	val backStack = LocalNavStack.current
	Column(Modifier.width(150.dp)) {
		// Cover Art Archive, like every other release-group-keyed tile here:
		// these rows carry no Navidrome album id, so there is no local art to
		// ask for. RemoteCoverArt falls back rather than showing a broken image.
		RemoteCoverArt(
			url = album.coverUrl,
			contentDescription = album.title,
			// Straight to the library album. Every row here is a record the
			// library already holds, so the external screen was always the wrong
			// destination — it was used only because the row carried no Navidrome
			// album id, and its redirect cannot fire when lb-bot's index row has
			// no id either. The result was a fully-downloaded album opening its
			// own download page. lb-bot sends `albumId` now; the external screen
			// stays as the fallback for the row it genuinely cannot resolve.
			onClick = dropUnlessResumed {
				backStack.add(
					if (album.albumId.isNotBlank()) {
						Screen.CollectionDetail(album.albumId, "similar")
					} else {
						Screen.ExternalAlbum(
							rgid = album.rgid,
							artistName = album.artist,
							title = album.title,
							// Known to be in the library — lb-bot only picks
							// these rows from artists it found there — so "view
							// artist" lands on the real artist page.
							artistId = album.artistId
						)
					}
				)
			},
			modifier = Modifier.fillMaxWidth()
		)
		Text(
			album.title,
			style = MaterialTheme.typography.bodyMedium,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.padding(top = 6.dp)
		)
		Text(
			album.artist,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis
		)
	}
}
