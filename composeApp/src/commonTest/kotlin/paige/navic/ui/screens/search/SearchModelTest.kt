package paige.navic.ui.screens.search

import paige.navic.domain.manager.LbArtistCandidate
import paige.navic.domain.models.DomainAlbum
import paige.navic.domain.models.DomainArtist
import paige.navic.domain.models.DomainExplicitStatus
import paige.navic.domain.models.DomainPlaylist
import paige.navic.domain.models.DomainSong
import paige.navic.domain.models.DomainSongArtist
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/*
 * P3: what the Search page shows, as plain functions. Library results come first (the exact search,
 * then what only the typo-tolerant pass found), capped per section on All. The unowned half is a
 * lookup with four states, and "nothing" is only said once every source has answered and said so: a
 * lookup still out is ghost rows, and one that failed is a pill, never "No results". A MusicBrainz
 * artist the library holds is told by its MBID, never by its name, because two artists share names.
 */
class SearchModelTest {

	private fun artist(id: String, name: String = "Artist $id") = DomainArtist(id = id, name = name)

	private fun album(id: String, name: String = "Album $id") = DomainAlbum(
		id = id, name = name, artistName = "A", artistId = "a", year = null, coverArtId = null,
		genre = null, genres = emptyList(), songCount = 1, duration = null, createdAt = Instant.fromEpochSeconds(0),
		starredAt = null, lastPlayedAt = null, userRating = null, version = null, musicBrainzId = null,
		songs = emptyList()
	)

	private fun song(
		id: String,
		title: String = "Song $id",
		artist: String = "A",
		artistId: String = "a",
		album: String = "Album",
		albumId: String = "al"
	) = DomainSong(
		id = id, title = title, artistName = artist, artistId = artistId, albumTitle = album,
		albumId = albumId, parentId = null, comment = null, trackNumber = 1, discNumber = 1,
		isrc = emptyList(), year = 2020, genre = null, genres = emptyList(), moods = emptyList(),
		duration = 200.seconds, bpm = null, contributors = emptyList(), userRating = null,
		averageRating = null, bitRate = null, bitDepth = null, sampleRate = null,
		audioChannelCount = null, replayGain = null, fileSize = 0, fileExtension = null,
		mimeType = null, filePath = null, starredAt = null, coverArtId = "cover-$albumId", musicBrainzId = null,
		explicitStatus = DomainExplicitStatus.Unknown,
		artists = listOf(DomainSongArtist(artistId, artist)),
		albumArtists = listOf(DomainSongArtist(artistId, artist))
	)

	private fun playlist(id: String) = DomainPlaylist(
		id = id, name = "Playlist $id", owner = null, comment = null, coverArtId = null, songCount = 0,
		duration = 0.seconds, createdAt = Instant.fromEpochSeconds(0), modifiedAt = Instant.fromEpochSeconds(0),
		public = null, readOnly = null, allowedUsers = emptyList(), validUntil = null, songs = emptyList()
	)

	private val none = LibraryHits(emptyList(), emptyList(), emptyList(), emptyList())

	// ---- merge and caps --------------------------------------------------------------------- //

	@Test
	fun mergeKeepsExactFirstThenNewFuzzyInOrder() {
		val exact = LibraryHits(
			artists = listOf(artist("1"), artist("2"), artist("1")),
			albums = listOf(album("x")),
			songs = listOf(song("s1")),
			playlists = emptyList()
		)
		val fuzzy = LibraryHits(
			artists = listOf(artist("3"), artist("2"), artist("4")),
			albums = listOf(album("x"), album("y")),
			songs = listOf(song("s2"), song("s1")),
			playlists = listOf(playlist("p"))
		)
		val merged = mergeHits(exact, fuzzy)
		assertEquals(listOf("1", "2", "3", "4"), merged.artists.map { it.id })
		assertEquals(listOf("x", "y"), merged.albums.map { it.id })
		assertEquals(listOf("s1", "s2"), merged.songs.map { it.id })
		assertEquals(listOf("p"), merged.playlists.map { it.id })
	}

	@Test
	fun allIsCappedPerSectionAndACategoryShowsOnlyItsOwn() {
		val many = LibraryHits(
			artists = (1..60).map { artist("$it") },
			albums = (1..60).map { album("$it") },
			songs = (1..60).map { song("$it") },
			playlists = (1..60).map { playlist("$it") }
		)
		capped(many, SearchCategory.ALL).let {
			assertEquals(listOf(3, 4, 6, 3), listOf(it.artists.size, it.albums.size, it.songs.size, it.playlists.size))
		}
		capped(many, SearchCategory.SONGS).let {
			assertEquals(listOf(0, 0, 50, 0), listOf(it.artists.size, it.albums.size, it.songs.size, it.playlists.size))
		}
		capped(many, SearchCategory.ALBUMS).let {
			assertEquals(listOf(0, 50, 0, 0), listOf(it.artists.size, it.albums.size, it.songs.size, it.playlists.size))
		}
		capped(many, SearchCategory.ARTISTS).let {
			assertEquals(listOf(50, 0, 0, 0), listOf(it.artists.size, it.albums.size, it.songs.size, it.playlists.size))
		}
		capped(many, SearchCategory.PLAYLISTS).let {
			assertEquals(listOf(0, 0, 0, 50), listOf(it.artists.size, it.albums.size, it.songs.size, it.playlists.size))
		}
	}

	// ---- the unowned half ------------------------------------------------------------------- //

	@Test
	fun sameNameArtistsAreToldApartByMbid() {
		val a = LbArtistCandidate(mbid = "mbid-a", name = "Pale Kiosk", disambiguation = "UK band")
		val b = LbArtistCandidate(mbid = "mbid-b", name = "Pale Kiosk", disambiguation = "Finnish duo")
		assertEquals(
			listOf(ExternalArtist(a, "nd1"), ExternalArtist(b, null)),
			splitExternalArtists(listOf(a, b), mapOf("mbid-a" to "nd1"))
		)
	}

	@Test
	fun aBlankMbidIsNeverOwned() {
		val a = LbArtistCandidate(mbid = "", name = "Pale Kiosk")
		assertEquals(listOf(ExternalArtist(a, null)), splitExternalArtists(listOf(a), mapOf("" to "nd1")))
	}

	@Test
	fun theLookupIsAskedForThreeCharactersOrTwoCjk() {
		assertFalse(externalLookupWanted("ab"))
		assertTrue(externalLookupWanted("abc"))
		assertFalse(externalLookupWanted("a b"))
		assertTrue(externalLookupWanted("米津"))
		assertFalse(externalLookupWanted("米"))
		assertFalse(externalLookupWanted("  x "))
		assertFalse(externalLookupWanted(""))
	}

	// ---- phases ----------------------------------------------------------------------------- //

	private val hits = LibraryHits(listOf(artist("1")), emptyList(), emptyList(), emptyList())

	@Test
	fun blankIsBrowse() {
		assertEquals(SearchPhase.Browse, searchPhase("  ", true, true, hits, Lookup.Idle, Lookup.Idle))
		assertEquals(SearchPhase.Browse, searchPhase("", false, true, none, Lookup.Idle, Lookup.Idle))
	}

	@Test
	fun aLibraryHitIsResults() {
		assertEquals(SearchPhase.Results, searchPhase("kiosk", true, true, hits, Lookup.Looking, Lookup.Looking))
	}

	@Test
	fun nothingAnywhereIsNoResults() {
		assertEquals(
			SearchPhase.NoResults,
			searchPhase("zzqvx", true, true, none, Lookup.Done(emptyList<Any>()), Lookup.Done(emptyList<Any>()))
		)
	}

	@Test
	fun aLookupStillOutIsNeverNoResults() {
		assertEquals(SearchPhase.Results, searchPhase("zzqvx", true, true, none, Lookup.Looking, Lookup.Done(emptyList<Any>())))
		assertEquals(SearchPhase.Results, searchPhase("zzqvx", true, true, none, Lookup.Done(emptyList<Any>()), Lookup.Looking))
	}

	@Test
	fun aFailedLookupIsNeverNoResults() {
		assertEquals(SearchPhase.Results, searchPhase("zzqvx", true, true, none, Lookup.Failed, Lookup.Done(emptyList<Any>())))
		assertEquals(SearchPhase.Results, searchPhase("zzqvx", true, true, none, Lookup.Done(emptyList<Any>()), Lookup.Failed))
	}

	@Test
	fun anUnownedHitIsResults() {
		assertEquals(
			SearchPhase.Results,
			searchPhase("zzqvx", true, true, none, Lookup.Done(listOf("x")), Lookup.Done(emptyList<Any>()))
		)
	}

	@Test
	fun withoutLbBotNothingIsNoResultsAtOnce() {
		assertEquals(SearchPhase.NoResults, searchPhase("zzqvx", true, false, none, Lookup.Idle, Lookup.Idle))
	}

	@Test
	fun aQueryTooShortToLookUpIsNoResultsOnTheLibraryAlone() {
		assertEquals(SearchPhase.NoResults, searchPhase("zq", true, true, none, Lookup.Idle, Lookup.Idle))
	}

	@Test
	fun aLibrarySearchStillOutIsNeverNoResults() {
		assertEquals(
			SearchPhase.Results,
			searchPhase("zzqvx", true, false, none, Lookup.Idle, Lookup.Idle, librarySettled = false)
		)
		assertEquals(
			SearchPhase.Offline,
			searchPhase("zzqvx", false, true, none, Lookup.Idle, Lookup.Idle, librarySettled = false)
		)
	}

	@Test
	fun offlineIsSaidWithOrWithoutHits() {
		assertEquals(SearchPhase.Offline, searchPhase("kiosk", false, true, hits, Lookup.Idle, Lookup.Idle))
		assertEquals(SearchPhase.OfflineNone, searchPhase("kiosk", false, true, none, Lookup.Idle, Lookup.Idle))
		// a lookup left over from before the connection went never counts offline
		assertEquals(SearchPhase.OfflineNone, searchPhase("kiosk", false, true, none, Lookup.Looking, Lookup.Failed))
	}

	// ---- offline ---------------------------------------------------------------------------- //

	@Test
	fun offlineFindsDownloadedSongsAndWhatTheyBelongTo() {
		val kiosk = song("s1", title = "Low Tide Radio", artist = "Pale Kiosk", artistId = "pk", album = "Kiosk Hours", albumId = "kh")
		val kiosk2 = song("s3", title = "Hours", artist = "Pale Kiosk", artistId = "pk", album = "Second", albumId = "se")
		val other = song("s2", title = "X", artist = "Other", artistId = "o", album = "Y", albumId = "y")
		val result = offlineHits(listOf(kiosk, other, kiosk2), "kiosk")
		assertEquals(listOf("s1", "s3"), result.songs.map { it.id })
		assertEquals(listOf("pk"), result.artists.map { it.id })
		assertEquals(2, result.artists.single().albumCount)
		assertEquals("Pale Kiosk", result.artists.single().name)
		assertEquals(listOf("kh", "se"), result.albums.map { it.id })
		assertEquals("Kiosk Hours", result.albums.first().name)
		assertEquals(listOf("s1"), result.albums.first().songs.map { it.id })
		assertEquals(emptyList(), result.playlists)
	}

	@Test
	fun offlineFindsNothingNotDownloaded() {
		val other = song("s2", title = "X", artist = "Other", artistId = "o", album = "Y", albumId = "y")
		assertEquals(none, offlineHits(listOf(other), "kiosk"))
		assertEquals(none, offlineHits(emptyList(), "kiosk"))
		assertEquals(none, offlineHits(listOf(other), "  "))
	}

	@Test
	fun offlineIsTypoTolerantToo() {
		val kiosk = song("s1", title = "Low Tide Radio", artist = "Pale Kiosk", artistId = "pk", album = "Kiosk Hours", albumId = "kh")
		assertEquals(listOf("s1"), offlineHits(listOf(kiosk), "pale kiosc").songs.map { it.id })
	}
}
