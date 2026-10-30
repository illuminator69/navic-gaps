package paige.navic.util

import paige.navic.domain.models.DomainExplicitStatus
import paige.navic.domain.models.DomainSong
import paige.navic.domain.repositories.creditedArtistIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * The artists' Downloaded filter's credits (P5 round 3). The songs list's orders are SQL since row 4a
 * and are held by `SongAndAlbumListSqlTest` (androidHostTest), on a real SQLite.
 */
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
	fun aDownloadedSongCreditsItsArtistItsArtistsAndItsAlbumsArtists() {
		assertEquals(
			setOf("ar1", "ar2", "ar3"),
			creditedArtistIds("ar1", listOf("ar1", "ar2"), listOf("ar3", ""))
		)
		assertEquals(setOf("ar2"), creditedArtistIds("", listOf("ar2"), emptyList()))
	}
}
