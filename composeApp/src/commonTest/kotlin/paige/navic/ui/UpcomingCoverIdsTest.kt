package paige.navic.ui

import paige.navic.domain.models.DomainExplicitStatus
import paige.navic.domain.models.DomainSong
import paige.navic.util.CoverPlaceholder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** Whose colours the prefetcher works out ahead of a song change ([upcomingCoverArtIds]). */
class UpcomingCoverIdsTest {
	private fun song(id: String, cover: String?) = DomainSong(
		id = id, title = "Song $id", artistName = "Artist", artistId = "ar", albumTitle = "Album",
		albumId = "al", parentId = null, comment = null, trackNumber = 1, discNumber = 1,
		isrc = emptyList(), year = null, genre = null, genres = emptyList(), moods = emptyList(),
		duration = 200.seconds, bpm = null, contributors = emptyList(), userRating = null,
		averageRating = null, bitRate = null, bitDepth = null, sampleRate = null,
		audioChannelCount = null, replayGain = null, fileSize = 0, fileExtension = null,
		mimeType = null, filePath = null, starredAt = null, coverArtId = cover, musicBrainzId = null,
		explicitStatus = DomainExplicitStatus.Unknown, artists = emptyList(), albumArtists = emptyList()
	)

	@AfterTest
	fun forgetThePlaceholder() = CoverPlaceholder.learn(null, 0, 0)

	@Test
	fun takesTheNextSongsInOrder() {
		val queue = listOf(song("1", "c1"), song("2", "c2"), song("3", "c3"), song("4", "c4"))
		assertEquals(listOf("c2", "c3"), upcomingCoverArtIds(queue, currentIndex = 0, count = 2))
	}

	@Test
	fun skipsBlankPlaceholderAndRepeatedIds() {
		CoverPlaceholder.learn("ph", 10, 1)
		val queue = listOf(
			song("1", "c1"), song("2", null), song("3", ""), song("4", "ar-x_ph"),
			song("5", "c5"), song("6", "c5"), song("7", "c7")
		)
		assertEquals(listOf("c5", "c7"), upcomingCoverArtIds(queue, currentIndex = 0, count = 3))
	}

	@Test
	fun stopsAtTheCount() {
		val queue = (1..10).map { song("$it", "c$it") }
		assertEquals(listOf("c4", "c5", "c6"), upcomingCoverArtIds(queue, currentIndex = 2, count = 3))
	}

	@Test
	fun anEmptyTailGivesNothing() {
		val queue = listOf(song("1", "c1"), song("2", "c2"))
		assertEquals(emptyList(), upcomingCoverArtIds(queue, currentIndex = 1, count = 3))
		assertEquals(emptyList(), upcomingCoverArtIds(queue, currentIndex = -1, count = 3))
	}

	// Audit L4a-08: the prefetch followed queue order, so under shuffle it missed the next songs.
	@Test
	fun followsPlayOrderUnderShuffle() {
		val queue = listOf(song("1", "c1"), song("2", "c2"), song("3", "c3"), song("4", "c4"))
		assertEquals(listOf("c4", "c2"), upcomingCoverArtIds(queue, currentIndex = 0, order = listOf(2, 0, 3, 1), count = 2))
	}

	@Test
	fun anOrderThatDoesNotDescribeTheQueueIsIgnored() {
		val queue = listOf(song("1", "c1"), song("2", "c2"), song("3", "c3"))
		assertEquals(listOf("c2", "c3"), upcomingCoverArtIds(queue, currentIndex = 0, order = listOf(1, 0), count = 2))
	}
}
