package paige.navic.ui.screens.library

import paige.navic.data.database.entities.SavedQueueEntity
import paige.navic.domain.models.DomainAlbum
import paige.navic.domain.models.DomainPlaylist
import paige.navic.domain.models.Mix
import paige.navic.domain.models.MixKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/* The Library's Recents (P5, PLAN-navic-library-and-custom-radio Task B1). */
class LibraryModelTest {
	private fun at(ms: Long) = Instant.fromEpochMilliseconds(ms)

	private fun album(id: String, artistId: String, lastPlayed: Long?, name: String = "Album $id") = DomainAlbum(
		id = id, name = name, artistName = "Artist $artistId", artistId = artistId, year = null,
		coverArtId = "al-$id", genre = null, genres = emptyList(), songCount = 10, duration = 40.minutes,
		createdAt = at(0), starredAt = null, lastPlayedAt = lastPlayed?.let(::at), playCount = 0,
		userRating = null, version = null, musicBrainzId = null, songs = emptyList(), isExternal = false
	)

	private fun playlist(id: String, modified: Long, name: String = "Playlist $id") = DomainPlaylist(
		id = id, name = name, owner = null, comment = null, coverArtId = "pl-$id", songCount = 3,
		duration = 10.minutes, createdAt = at(0), modifiedAt = at(modified), public = null, readOnly = null,
		allowedUsers = emptyList(), validUntil = null, songs = emptyList()
	)

	private fun queue(id: String, updated: Long) =
		SavedQueueEntity(id = id, name = "Queue $id", queueJson = "[]", updatedAt = updated)

	private fun mix(id: String, played: Long, updated: Long = 0, seedIds: List<String> = emptyList()) =
		Mix(id = id, name = "Mix $id", kind = MixKind.ARTIST, seedId = "a", seedIds = seedIds,
			lastPlayedAt = played, updatedAt = updated)

	private fun recents(
		liked: Int? = null,
		mixes: List<Mix> = emptyList(),
		queues: List<SavedQueueEntity> = emptyList(),
		playlists: List<DomainPlaylist> = emptyList(),
		albums: List<DomainAlbum> = emptyList(),
		limit: Int = 100
	) = recentsEntries(liked, mixes, queues, playlists, albums, limit = limit)

	@Test
	fun likedIsFirstWhateverItsAgeAndAbsentWhenNotKnown() {
		val entries = recents(liked = 412, albums = listOf(album("x", "a", 9_000)))
		assertEquals(EntryKind.Liked, entries.first().kind)
		assertNull(entries.first().recentAt)
		assertTrue(recents(liked = null, albums = listOf(album("x", "a", 9_000))).none { it.kind == EntryKind.Liked })
	}

	@Test
	fun theRestAreNewestFirstWithTiesByTitle() {
		val entries = recents(
			albums = listOf(album("b", "z", 500, name = "Beta"), album("a", "y", 500, name = "Alpha")),
			playlists = listOf(playlist("p", 900)),
			queues = listOf(queue("q", 100))
		)
		// Artists take their newest album's clock: y and z at 500, sorted by title with the albums
		// ("Alpha", "Artist y", "Artist z", "Beta").
		assertEquals(
			listOf("pl:p", "al:a", "ar:y", "ar:z", "al:b", "sq:q"),
			entries.map { it.key }
		)
	}

	@Test
	fun anEntryWithNoClockIsLeftOut() {
		val entries = recents(
			albums = listOf(album("never", "n", null)),
			mixes = listOf(mix("m", played = 0, updated = 0))
		)
		assertTrue(entries.isEmpty())
	}

	@Test
	fun eachKindKeepsItsOwnClock() {
		val entries = recents(
			mixes = listOf(mix("played", played = 700, updated = 100), mix("saved", played = 0, updated = 300)),
			queues = listOf(queue("q", 500)),
			playlists = listOf(playlist("p", 600)),
			albums = listOf(album("al", "ar", 400))
		)
		val clock = entries.associate { it.key to it.recentAt }
		assertEquals(700L, clock["mx:played"])
		assertEquals(300L, clock["mx:saved"]) // never played: when it was last changed
		assertEquals(500L, clock["sq:q"])
		assertEquals(600L, clock["pl:p"])
		assertEquals(400L, clock["al:al"])
		assertEquals(400L, clock["ar:ar"])
	}

	@Test
	fun anArtistAppearsOnlyWithAPlayedAlbumAndTakesTheNewest() {
		val albums = listOf(album("1", "ar", 100), album("2", "ar", 800), album("3", "quiet", null))
		assertEquals(800L, artistRecentAt("ar", albums))
		assertNull(artistRecentAt("quiet", albums))
		val entries = recents(albums = albums)
		assertEquals(1, entries.count { it.key == "ar:ar" })
		assertTrue(entries.none { it.key == "ar:quiet" })
		assertEquals(EntryKind.Artist, entries.first { it.key == "ar:ar" }.kind)
	}

	@Test
	fun aMixOfSeveralArtistsIsACustomRadio() {
		val entries = recents(mixes = listOf(mix("one", 5), mix("many", 6, seedIds = listOf("a", "b"))))
		assertEquals(EntryKind.CustomRadio, entries.first { it.key == "mx:many" }.kind)
		assertEquals(EntryKind.Mix, entries.first { it.key == "mx:one" }.kind)
	}

	@Test
	fun theListIsCutAfterOrdering() {
		val albums = (1..30).map { album("$it", "a$it", it * 10L) }
		val entries = recents(liked = 1, albums = albums, limit = 5)
		// Liked, then the five newest of everything, which are albums and their artists.
		assertEquals(6, entries.size)
		assertEquals(listOf("al:30", "ar:a30", "al:29", "ar:a29", "al:28"), entries.drop(1).map { it.key })
	}

	@Test
	fun twoEntriesWithOneKeyCollapse() {
		val entries = recents(playlists = listOf(playlist("p", 100), playlist("p", 200)))
		assertEquals(1, entries.size)
		assertEquals(200L, entries.single().recentAt)
	}
}
