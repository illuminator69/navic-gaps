package paige.navic.domain.repositories

import kotlinx.serialization.json.Json
import paige.navic.domain.models.DomainExplicitStatus
import paige.navic.domain.models.DomainSong
import paige.navic.ui.core.PlayerUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/*
 * Q-049: what a launch reads back and what it does with it. "Nothing persisted" and "no longer
 * decodes" both have to come out as an answer — the launch used to wait for a state that did
 * decode, and on a fresh install that wait never ended and nothing was ever persisted behind it.
 */
class PlayerStateRepositoryTest {
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

	private val playing = PlayerUiState(
		queue = listOf(song("a"), song("b"), song("c")),
		currentSong = song("b"),
		currentIndex = 1,
		isPaused = false,
		isLoading = true,
		progress = 0.4f,
		savedQueueId = "q1"
	)

	@Test
	fun nothingPersistedIsAnAnswer() {
		val decoded = decodePlayerState(null)
		assertTrue(decoded.isSuccess)
		assertNull(decoded.getOrThrow())
	}

	@Test
	fun aStateThatNoLongerDecodesIsAFailureNotAWait() {
		assertTrue(decodePlayerState("not json at all").isFailure)
		// Well-formed, but not this build's shape.
		assertTrue(decodePlayerState("""{"queue": "not a list"}""").isFailure)
	}

	@Test
	fun whatWasPersistedComesBackAsItWas() {
		assertEquals(playing, decodePlayerState(Json.encodeToString(playing)).getOrThrow())
	}

	@Test
	fun aFieldFromANewerBuildIsIgnored() {
		assertEquals(
			PlayerUiState(currentIndex = 2),
			decodePlayerState("""{"currentIndex": 2, "addedLater": true}""").getOrThrow()
		)
	}

	@Test
	fun aSavedQueueIsRestoredPausedIntoAnEmptyPlayer() {
		assertEquals(
			playing.copy(isPaused = true, isLoading = false),
			stateToRestore(playing, current = PlayerUiState())
		)
	}

	@Test
	fun nothingSavedRestoresNothing() {
		assertNull(stateToRestore(null, current = PlayerUiState()))
	}

	@Test
	fun aQueueAlreadyStartedIsNotReplaced() {
		val started = PlayerUiState(queue = listOf(song("x")), currentIndex = 0)
		assertNull(stateToRestore(playing, current = started))
	}
}
