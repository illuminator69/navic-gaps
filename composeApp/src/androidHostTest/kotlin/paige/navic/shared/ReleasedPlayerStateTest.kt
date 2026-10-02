package paige.navic.shared

import paige.navic.domain.models.DomainExplicitStatus
import paige.navic.domain.models.DomainSong
import paige.navic.ui.core.PlayerUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/*
 * Q-040 fix round 1: while the controller is released, hub-driven changes must land in the UI
 * state, and the reconnect must restore THAT, not a snapshot frozen at swipe time. Otherwise a
 * `do:play` revived the old queue and the next publish sent it back over the session.
 */
class ReleasedPlayerStateTest {
	private fun song(id: String) = DomainSong(
		id = id, title = id, artistName = null, artistId = "ar", albumTitle = null, albumId = null,
		parentId = null, comment = null, trackNumber = null, discNumber = null, isrc = emptyList(),
		year = null, genre = null, genres = emptyList(), moods = emptyList(), duration = 3.minutes,
		bpm = null, contributors = emptyList(), userRating = null, averageRating = null,
		bitRate = null, bitDepth = null, sampleRate = null, audioChannelCount = null,
		replayGain = null, fileSize = 0L, fileExtension = null, mimeType = null, filePath = null,
		starredAt = null, coverArtId = null, musicBrainzId = null,
		explicitStatus = DomainExplicitStatus.Unknown, artists = emptyList(), albumArtists = emptyList()
	)

	private val a = song("a")
	private val b = song("b")
	private val c = song("c")

	/** The local state the moment a swipe released the controller (the release marks it paused). */
	private val atSwipe = PlayerUiState(
		queue = listOf(a, b), currentSong = b, currentIndex = 1, isPaused = true, progress = 0.4f
	)

	@Test
	fun aQueueEditWhileReleasedIsWhatTheReconnectRestores() {
		val edited = atSwipe.withQueueWhileReleased(listOf(c, b), 1)
		val restored = restoreOnConnect(edited)
		assertEquals(listOf(c, b), restored?.queue)
		assertEquals(1, restored?.currentIndex)
		// Same track playing: the position is kept.
		assertEquals(0.4f, restored?.progress)
	}

	@Test
	fun anEditThatMovesTheCurrentTrackStartsItFromZero() {
		val edited = atSwipe.withQueueWhileReleased(listOf(c, a), 0)
		assertEquals(c, edited.currentSong)
		assertEquals(0f, edited.progress)
		assertTrue(edited.isPaused)
	}

	@Test
	fun aClearWhileReleasedLeavesNothingToRestore() {
		val cleared = atSwipe.copy(queue = emptyList(), currentSong = null, currentIndex = -1, progress = 0f)
		assertNull(restoreOnConnect(cleared))
	}

	@Test
	fun repeatAndShuffleSetWhileReleasedSurviveTheRestore() {
		val restored = restoreOnConnect(atSwipe.copy(isShuffleEnabled = true, repeatMode = 2))
		assertEquals(true, restored?.isShuffleEnabled)
		assertEquals(2, restored?.repeatMode)
	}

	@Test
	fun theRestoreIsAlwaysPaused() {
		val restored = restoreOnConnect(atSwipe.copy(isPaused = false, isLoading = true))
		assertEquals(true, restored?.isPaused)
		assertEquals(false, restored?.isLoading)
	}
}
