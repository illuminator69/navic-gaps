package paige.navic.util

import kotlinx.collections.immutable.persistentListOf
import paige.navic.domain.models.DomainExplicitStatus
import paige.navic.domain.models.DomainSong
import paige.navic.domain.models.DomainSongListType
import paige.navic.domain.repositories.creditedArtistIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The songs list's orders and the artists' Downloaded filter's credits (P5 round 3). */
class SongSortTest {
	private fun song(id: String, album: String?, plays: Int = 0) = DomainSong(
		id = id, title = "Song $id", artistName = "A", artistId = "ar1", albumTitle = "Album",
		albumId = album, parentId = null, comment = null, trackNumber = 1, discNumber = 1,
		isrc = emptyList(), year = null, genre = null, genres = emptyList(), moods = emptyList(),
		duration = 200.seconds, bpm = null, contributors = emptyList(), userRating = null,
		averageRating = null, bitRate = null, bitDepth = null, sampleRate = null,
		audioChannelCount = null, replayGain = null, fileSize = 0, fileExtension = null,
		mimeType = null, filePath = null, starredAt = null, coverArtId = null, musicBrainzId = null,
		explicitStatus = DomainExplicitStatus.Unknown, artists = emptyList(), albumArtists = emptyList(),
		playCount = plays
	)

	@Test
	fun recentlyAddedIsByTheAlbumsAddedTimeNewestFirst() {
		val songs = persistentListOf(song("1", "old"), song("2", "new"), song("3", "mid"))
		val added = mapOf(
			"old" to Instant.fromEpochMilliseconds(1_000),
			"mid" to Instant.fromEpochMilliseconds(2_000),
			"new" to Instant.fromEpochMilliseconds(3_000)
		)
		assertEquals(listOf("2", "3", "1"), songs.sortedByListType(DomainSongListType.Newest, added).map { it.id })
	}

	@Test
	fun aSongWithNoKnownAlbumGoesLast() {
		val songs = persistentListOf(song("1", null), song("2", "gone"), song("3", "a"))
		val added = mapOf("a" to Instant.fromEpochMilliseconds(1_000))
		assertEquals("3", songs.sortedByListType(DomainSongListType.Newest, added).first().id)
	}

	@Test
	fun theOtherOrdersNeedNoAlbums() {
		val songs = persistentListOf(song("1", "a", plays = 1), song("2", "a", plays = 9))
		assertEquals(listOf("2", "1"), songs.sortedByListType(DomainSongListType.FrequentlyPlayed).map { it.id })
	}

	@Test
	fun aDownloadedSongCreditsItsArtistItsArtistsAndItsAlbumsArtists() {
		assertEquals(
			setOf("ar1", "ar2", "ar3"),
			creditedArtistIds("ar1", listOf("ar1", "ar2"), listOf("ar3", ""))
		)
		assertEquals(setOf("ar2"), creditedArtistIds("", listOf("ar2"), emptyList()))
	}
}
