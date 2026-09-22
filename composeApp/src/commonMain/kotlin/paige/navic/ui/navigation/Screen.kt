package paige.navic.ui.navigation

import androidx.compose.runtime.Immutable
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import paige.navic.domain.models.DomainAlbumListType
import paige.navic.domain.models.DomainArtistListType
import paige.navic.domain.models.DomainSongListType

@Immutable
@Serializable
sealed interface Screen : NavKey {

	// tabs
	@Immutable
	@Serializable
	data class Library(
		val nested: Boolean = false
	) : Screen

	@Immutable
	@Serializable
	data class Starred(
		val nested: Boolean = false,
		val listType: DomainArtistListType = DomainArtistListType.AlphabeticalByName
	) : Screen

	@Immutable
	@Serializable
	data class PlaylistList(
		val nested: Boolean = false
	) : Screen

	@Immutable
	@Serializable
	data class ArtistList(
		val nested: Boolean = false,
		val listType: DomainArtistListType = DomainArtistListType.AlphabeticalByName
	) : Screen

	@Immutable
	@Serializable
	data class AlbumList(
		val nested: Boolean = false,
		val listType: DomainAlbumListType = DomainAlbumListType.AlphabeticalByArtist
	) : Screen

	@Immutable
	@Serializable
	data class GenreList(
		val nested: Boolean = false
	) : Screen

	@Immutable
	@Serializable
	data class GenreDetail(
		val genreName: String
	) : Screen

	@Immutable
	@Serializable
	data class SongList(
		val nested: Boolean = false,
		// navi-connect: the songs list is also reachable scoped to one artist
		// ("see all tracks"), which upstream's key doesn't carry.
		val artistId: String? = null,
		val artistName: String? = null,
		val listType: DomainSongListType = DomainSongListType.FrequentlyPlayed
	) : Screen

	@Immutable
	@Serializable
	data class RadioList(
		val nested: Boolean = false
	) : Screen

	// misc
	@Immutable
	@Serializable
	data object Login : Screen

	@Immutable
	@Serializable
	data class ImageView(
		val coverArtId: String,
		val title: String,
		val sharedTransitionKey: String
	) : Screen

	@Immutable
	@Serializable
	data object NowPlaying : Screen

	@Immutable
	@Serializable
	data object Lyrics : Screen

	@Immutable
	@Serializable
	data object Queue : Screen

	@Immutable
	@Serializable
	data object PlaybackSpeed : Screen

	@Serializable
	data object SmartPlaylistEditor : Screen
	@Immutable
	@Serializable
	data class CollectionDetail(
		val collectionId: String,
		val tab: String
	) : Screen

	@Immutable
	@Serializable
	data class SongDetailSheet(val songId: String, val coverArtId: String? = null) : Screen

	@Immutable
	@Serializable
	data class SongDetailScreen(val songId: String, val coverArtId: String? = null) : Screen

	@Immutable
	@Serializable
	data class Search(
		val nested: Boolean = false
	) : Screen

	@Immutable
	@Serializable
	data object ShareList : Screen

	@Immutable
	@Serializable
	data object SavedQueues : Screen
	@Immutable
	@Serializable
	data class ArtistDetail(val artist: String) : Screen

	@Immutable
	@Serializable
	data class Statistics(val nested: Boolean = false) : Screen

	/**
	 * New releases from ListenBrainz, via lb-bot. A tab, hence [nested].
	 */
	@Immutable
	@Serializable
	data class Fresh(val nested: Boolean = false) : Screen

	/**
	 * The Discover screen: one place to go, instead of six places to know about.
	 *
	 * Fresh, "fans also like", the rediscovery set and mood search were each
	 * reachable from somewhere different and each invented its own empty state.
	 * The rows come from [paige.navic.ui.screens.discover.DISCOVER_ROWS], whose
	 * ids are duplicated in Feishin on purpose. A tab, hence [nested].
	 */
	@Immutable
	@Serializable
	data class Discover(val nested: Boolean = false) : Screen

	/**
	 * An artist the library does not have, keyed on their MusicBrainz id.
	 *
	 * **Deliberately not an overload of [ArtistDetail].** That one takes a Navidrome
	 * artist id and loads it from Room, throwing `UiState.Error` when the artist is
	 * not in the local DB — and keeping it strict is exactly what makes that error
	 * path correct. An lb-bot artist has no Navidrome row by definition, so it gets
	 * its own key rather than teaching the DB-backed screen to sometimes not need
	 * the DB.
	 */
	@Immutable
	@Serializable
	data class ExternalArtist(val artistMbid: String, val name: String = "") : Screen

	/**
	 * A release the library does not have, keyed on its MusicBrainz release-group id.
	 *
	 * Same reasoning as [ExternalArtist] against [CollectionDetail]: a release-group
	 * id is not a Navidrome album id and cannot be passed to a screen that reads one.
	 */
	@Immutable
	@Serializable
	data class ExternalAlbum(
		val rgid: String,
		val artistMbid: String = "",
		val artistName: String = "",
		val title: String = "",
		/**
		 * The Navidrome artist id, when whatever opened this page knew the artist
		 * is in the library. "View artist" always opened the *external* artist
		 * page, which for an owned artist is the worse of the two answers — and
		 * the tile this page was reached from already knew better.
		 */
		val artistId: String = ""
	) : Screen

	// settings
	@Immutable
	@Serializable
	sealed interface Settings : Screen {
		@Immutable
		@Serializable
		data object Root : Settings

		@Immutable
		@Serializable
		data object Appearance : Settings

		@Immutable
		@Serializable
		data object Playback : Settings

		@Immutable
		@Serializable
		data object Developer : Settings

		@Immutable
		@Serializable
		data object BottomAppBar : Settings

		@Immutable
		@Serializable
		data object NowPlaying : Settings

		@Immutable
		@Serializable
		data object About : Settings

		@Immutable
		@Serializable
		data object DataStorage : Settings

		@Immutable
		@Serializable
		data object DownloadCenter : Settings
		@Immutable
		@Serializable
		data object Fonts : Settings

		@Immutable
		@Serializable
		data object Themes : Settings

		@Immutable
		@Serializable
		data object Effects: Settings
		@Immutable
		@Serializable
		data object CustomHeaders : Settings

		@Immutable
		@Serializable
		data object StreamingQuality : Settings
		@Immutable
		@Serializable
		data object DownloadQuality : Settings

		@Immutable
		@Serializable
		data object Logs : Settings
		@Immutable
		@Serializable
		data object NaviConnect : Settings

		@Immutable
		@Serializable
		data object AppIcon : Settings

		@Immutable
		@Serializable
		data object Equaliser : Settings
	}
}
