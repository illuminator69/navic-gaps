package paige.navic.shared

import paige.navic.domain.models.DomainExplicitStatus
import paige.navic.domain.models.DomainSong
import paige.navic.ui.core.PlayerUiState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/*
 * Undoing a swipe in the queue went silent for about a second (you, 2026-10-16): the undo loaded
 * the snapshot whole, which re-prepares the playing song. Where the edit left the playing song
 * alone, the queue is now put back around it instead (`undoKeepsPlayingSong`).
 */
class QueueUndoTest {
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

	private fun snap(kind: QueueUndoKind, vararg ids: String, index: Int, session: String? = "q1") =
		QueueUndoSnapshot(
			id = 1, kind = kind, queue = ids.map { song(it) }, index = index, positionMs = 80_000,
			wasPaused = false, savedQueueId = session, savedQueueKind = "manual"
		)

	private fun now(vararg ids: String, playing: String?, session: String? = "q1") = PlayerUiState(
		queue = ids.map { song(it) },
		currentSong = playing?.let { song(it) },
		currentIndex = playing?.let { ids.indexOf(it) } ?: -1,
		savedQueueId = session
	)

	@Test
	fun anotherRowRemovedOrMovedIsPutBackAroundThePlayingSong() {
		// "c" swiped away below the playing "b", and "a" swiped away above it.
		assertTrue(undoKeepsPlayingSong(snap(QueueUndoKind.REMOVE, "a", "b", "c", index = 1), now("a", "b", playing = "b")))
		assertTrue(undoKeepsPlayingSong(snap(QueueUndoKind.REMOVE, "a", "b", "c", index = 1), now("b", "c", playing = "b")))
		// A row dragged elsewhere.
		assertTrue(undoKeepsPlayingSong(snap(QueueUndoKind.MOVE, "a", "b", "c", index = 1), now("b", "a", "c", playing = "b")))
	}

	@Test
	fun thePlayingSongGoneOrAnotherSessionLoadsTheSnapshotWhole() {
		// The playing song itself was removed: the next one plays now.
		assertFalse(undoKeepsPlayingSong(snap(QueueUndoKind.REMOVE, "a", "b", "c", index = 1), now("a", "c", playing = "c")))
		// Cleared: nothing plays.
		assertFalse(undoKeepsPlayingSong(snap(QueueUndoKind.CLEAR, "a", "b", index = 0), now(playing = null)))
		// Replaced by another queue that happens to play the same song: another session.
		assertFalse(undoKeepsPlayingSong(snap(QueueUndoKind.REPLACE, "a", "b", index = 1), now("b", "x", playing = "b", session = "q2")))
	}
}
