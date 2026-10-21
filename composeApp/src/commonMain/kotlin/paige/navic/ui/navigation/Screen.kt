package paige.navic.ui.navigation

import androidx.compose.runtime.Immutable
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import paige.navic.domain.models.DomainAlbumListType
import paige.navic.domain.models.DomainArtistListType
import paige.navic.domain.models.DomainSongListType
import paige.navic.ui.screens.downloads.DownloadsSection

@Immutable
@Serializable
sealed interface Screen : NavKey {

	// tabs
	/**
	 * Home (P1): where the app opens: resume, Recently added, Fresh. A root page of its own, not
	 * the Library tab, which stays as the third button of the bar.
	 */
	@Immutable
	@Serializable
	data object Home : Screen

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
	/**
	 * The smart-playlist rule editor. A `data class` rather than a `data object`
	 * since it gained an edit mode: with no id it creates, with one it loads that
	 * playlist's criteria from Navidrome and rewrites them in place.
	 */
	data class SmartPlaylistEditor(val playlistId: String? = null) : Screen
	@Immutable
	@Serializable
	data class CollectionDetail(
		val collectionId: String,
		val tab: String,
		/** Opened to fill its gaps (the album sheet's "Fill gaps"): the gaps box starts unfolded. */
		val openGaps: Boolean = false
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

	/**
	 * "Mixed for You": the list of stored recipes.
	 *
	 * Deliberately not an overload of [SavedQueues], even though the two screens
	 * look alike. A saved queue is a result and a mix is a recipe; one screen
	 * serving both would have to answer "how many tracks" for a thing that has no
	 * tracks until it is played. See [paige.navic.domain.models.Mix].
	 */
	@Immutable
	@Serializable
	data class MixList(val nested: Boolean = false) : Screen

	/**
	 * Downloads (I-039): what this device holds offline, what lb-bot is getting from Soulseek,
	 * and the wishlist (releases nobody was sharing, which lb-bot keeps looking for), one chip
	 * each on a library page. [section] is the chip it opens on.
	 *
	 * The wishlist stays its own chip rather than a fourth group under the others: they answer
	 * "what is happening now", and a wishlist entry is explicitly the opposite, something that
	 * is not happening, on a timescale of hours.
	 */
	@Immutable
	@Serializable
	data class Downloads(val section: DownloadsSection = DownloadsSection.Device) : Screen

	/**
	 * Library Matches (I-026): which MusicBrainz artist and release the library's artists and
	 * albums are, as lb-bot holds it — the artists to check, the artist overrides, the albums it
	 * retagged and the names it merged. A pushed page, reached from the library home; it was the
	 * "lb-bot data" section of the navi-connect settings.
	 */
	@Immutable
	@Serializable
	data object LibraryMatches : Screen
	@Immutable
	@Serializable
	data class ArtistDetail(val artist: String) : Screen

	/**
	 * The custom radio (P5): pick artists, tune the feel, Done saves it under Mixes and starts it.
	 * Opened by the New sheet and the Mixes page's "+".
	 */
	@Immutable
	@Serializable
	data object CustomRadio : Screen

	/**
	 * A mix's page (P5 round 2): its songs, built on opening, as a playlist's page shows them.
	 * [mixId] is a hub mix's id, or `YOUR_MIX_ID` for Your mix.
	 */
	@Immutable
	@Serializable
	data class MixDetail(val mixId: String) : Screen

	/**
	 * A Home mood tile's mix page (P7): about 50 of the library's songs drawn from the tile's list on
	 * each opening. Ids are the hub's catalogue slugs (PROTOCOL §18.5).
	 */
	@Immutable
	@Serializable
	data class MoodMix(val moodId: String, val variantId: String) : Screen

	/**
	 * An owned artist's whole discography behind chips (P4 round 2): the artist page shows a few
	 * releases and "See discography" opens this.
	 */
	@Immutable
	@Serializable
	data class ArtistDiscography(val artist: String) : Screen

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

	/** Deezer's chart through lb-bot, a page of fifty narrowed by genre (P3, Search's browse card). */
	@Immutable
	@Serializable
	data object Charts : Screen

	/** Deezer's editorial selections through lb-bot, as [Charts] (P3, Search's browse card). */
	@Immutable
	@Serializable
	data object EditorsPicks : Screen

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
		val artistId: String = "",
		/** Opened from a pasted link (P4): the heading says "Found from your link". */
		val fromLink: Boolean = false
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

		/** What is still waiting to be sent to the server, row by row (I-020). */
		@Immutable
		@Serializable
		data object PendingActions : Settings
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
