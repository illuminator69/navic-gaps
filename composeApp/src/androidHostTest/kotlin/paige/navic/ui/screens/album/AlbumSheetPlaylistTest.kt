package paige.navic.ui.screens.album

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Review finding 1: "Add to playlist" from an album's long-press sheet. The sheet calls the action
 * and then closes, which clears the selection; since row 4a the tile is then the list's row, with
 * no songs. So the dialog's songs are captured AT THE TAP and the dialog is drawn from them, never
 * from `album.songs` read after the sheet has gone (the dialog's view model is keyed on the songs).
 * The behaviour needs a device (watched on the AVD); this holds the shape.
 */
class AlbumSheetPlaylistTest {
	private fun src(path: String) = File("src/commonMain/kotlin/paige/navic/ui/screens/$path").readText()

	@Test
	fun theAlbumTilesCaptureTheSongsAtTheTap() {
		for (path in listOf("album/components/GridItem.kt", "album/components/ListItem.kt")) {
			val s = src(path)
			assertTrue("onAddAllToPlaylist = { playlistSongs = album.songs.toPersistentList() }" in s, path)
			assertTrue("playlistSongs?.let { songs ->\n\t\t\tPlaylistUpdateDialog(\n\t\t\t\tsongs = songs," in s, path)
			assertTrue("songs = album.songs" !in s, path)
		}
	}

	@Test
	fun searchsSheetsKeepTheirDialogOutsideTheSelection() {
		val s = src("search/SearchScreen.kt")
		// The dialog used to live inside `albumListSelection?.let` / `artistListSelection?.let`,
		// which the sheet's own dismissal removes in the same frame.
		assertTrue("playlistSongs?.let { songs ->\n\t\tPlaylistUpdateDialog(songs = songs, onDismissRequest = { playlistSongs = null })" in s)
		assertTrue("onAddAllToPlaylist = { onAddToPlaylist(album.songs.toPersistentList()) }" in s)
		assertTrue("playlistDialogShown" !in s)
	}

	@Test
	fun noAlbumSheetOffersADownloadBeforeItKnows() {
		for (path in listOf(
			"album/components/GridItem.kt", "album/components/ListItem.kt", "playlist/components/ListItem.kt",
			"playlist/components/GridItem.kt", "collection/components/MoreByArtistRow.kt",
			"collection/components/SimilarAlbumsRow.kt", "search/SearchScreen.kt"
		)) {
			val s = src(path)
			assertTrue(".collectAsState(initial = null)" in s, path)
			assertTrue("initial = DownloadStatus.NOT_DOWNLOADED" !in s, path)
			// Remembered: a new flow per recomposition was a new collection each time (finding 11).
			val read = s.substringBefore("getCollectionDownloadStatus(").takeLast(200)
			assertTrue("remember(" in read, "$path: $read")
		}
		val heading = src("collection/components/HeadingRowButtons.kt")
		assertTrue("remember(" in heading.substringBefore("getCollectionDownloadStatus(").takeLast(200))
	}
}
