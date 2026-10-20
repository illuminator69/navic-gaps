package paige.navic.ui.screens.artist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import paige.navic.di.LocalNavStack
import paige.navic.domain.models.DomainAlbum
import paige.navic.domain.models.displayName
import paige.navic.ui.navigation.Screen
import paige.navic.ui.screens.artist.viewmodels.ArtistDetailViewModel
import paige.navic.ui.screens.artist.viewmodels.DiscographyEntry
import paige.navic.ui.screens.artist.viewmodels.DiscographySection

/**
 * What a tap on an owned artist's discography row opens, on the artist page and on its discography
 * page alike: an album the library holds (partly owned included: filling its gaps is the album
 * page's box, not the tap); a just-landed one once Room has it, else the release's page, whose box
 * says what is happening; and a release the library lacks, its own page, where it is found, chosen
 * and requested (P4).
 */
@Composable
fun rememberDiscographyOpener(viewModel: ArtistDetailViewModel, artistId: String): (DiscographyEntry) -> Unit {
	val backStack = LocalNavStack.current
	val artistMbid by viewModel.artistMbid.collectAsStateWithLifecycle()
	val artistState by viewModel.artistState.collectAsStateWithLifecycle()
	val openRelease: (DiscographyEntry) -> Unit = { entry ->
		entry.release?.let { release ->
			backStack.add(
				Screen.ExternalAlbum(
					rgid = release.rgid,
					artistMbid = artistMbid.effective,
					artistName = artistState.data?.artist?.name.orEmpty(),
					title = release.title,
					artistId = artistId
				)
			)
		}
	}
	return { entry ->
		when {
			entry.album != null -> backStack.add(Screen.CollectionDetail(entry.album.id, "artist"))
			entry.pendingSync -> {
				val albumId = viewModel.openPendingSync(entry)
				if (albumId != null) backStack.add(Screen.CollectionDetail(albumId, "artist"))
				else openRelease(entry)
			}
			entry.release != null -> openRelease(entry)
		}
	}
}

/**
 * The library's own albums as a discography, for when lb-bot has none to give (off, or never
 * scanned): one section of albums, every one owned. The pills are left off then, since each would
 * say "Owned".
 */
fun ownedSections(albums: List<DomainAlbum>): List<DiscographySection> =
	if (albums.isEmpty()) emptyList()
	else listOf(
		DiscographySection(
			type = "album",
			entries = albums.map { album ->
				DiscographyEntry(
					key = album.id,
					title = album.displayName,
					year = album.year?.toString().orEmpty(),
					album = album
				)
			}
		)
	)
