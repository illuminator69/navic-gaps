package paige.navic.ui.components.sheets

import paige.navic.domain.models.DomainExplicitStatus
import paige.navic.domain.models.DomainPlaylist
import paige.navic.domain.models.DomainSong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The player's "Save to playlist" sheet (P2 notes, `AddToPlaylistViewModel.kt`): which rows, in which order, in which state. */
class PlaylistChoicesTest {

	private fun song(id: String) = DomainSong(
		id = id, title = "Song $id", artistName = "Artist", artistId = "ar", albumTitle = "Album",
		albumId = "al", parentId = null, comment = null, trackNumber = 1, discNumber = 1,
		isrc = emptyList(), year = null, genre = null, genres = emptyList(), moods = emptyList(),
		duration = 200.seconds, bpm = null, contributors = emptyList(), userRating = null,
		averageRating = null, bitRate = null, bitDepth = null, sampleRate = null,
		audioChannelCount = null, replayGain = null, fileSize = 0, fileExtension = null,
		mimeType = null, filePath = null, starredAt = null, coverArtId = null, musicBrainzId = null,
		explicitStatus = DomainExplicitStatus.Unknown, artists = emptyList(), albumArtists = emptyList()
	)

	private fun playlist(id: String, name: String, songs: List<String> = emptyList(), readOnly: Boolean? = false) = DomainPlaylist(
		id = id, name = name, owner = "me", comment = null, coverArtId = null, songCount = songs.size,
		duration = 0.seconds, createdAt = Instant.fromEpochSeconds(0), modifiedAt = Instant.fromEpochSeconds(0),
		public = false, readOnly = readOnly, allowedUsers = emptyList(), validUntil = null, songs = songs.map { song(it) }
	)

	private val all = listOf(
		playlist("p1", "rock"),
		playlist("p2", "Ambient", songs = listOf("s9")),
		playlist("p3", "smart", readOnly = true),
		playlist("p4", "Chill")
	)

	@Test
	fun editablePlaylistsByNameAndWhetherTheyHoldTheSong() {
		val rows = playlistChoices(all, songId = "s9", createdHere = emptyList())
		assertEquals(listOf("Ambient", "Chill", "rock"), rows.map { it.playlist.name })
		assertEquals(listOf(true, false, false), rows.map { it.holdsSong })
	}

	@Test
	fun playlistsMadeFromTheSheetComeFirstNewestFirst() {
		val rows = playlistChoices(all, songId = "s9", createdHere = listOf("p1", "p4"))
		assertEquals(listOf("Chill", "rock", "Ambient"), rows.map { it.playlist.name })
	}

	@Test
	fun whatTheSheetDidWinsOverTheCacheAndBusyRowsAreMarked() {
		val rows = playlistChoices(all, songId = "s9", createdHere = emptyList(), holding = mapOf("p2" to false, "p4" to true), busy = setOf("p1"))
		assertEquals(listOf(false, true, false), rows.map { it.holdsSong })
		assertEquals(listOf(false, false, true), rows.map { it.busy })
	}
}
