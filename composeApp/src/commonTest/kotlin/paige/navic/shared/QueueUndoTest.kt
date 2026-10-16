package paige.navic.shared

import paige.navic.domain.models.DomainExplicitStatus
import paige.navic.domain.models.DomainSong
import paige.navic.ui.core.PlayerUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/*
 * Undoing a swipe in the queue went silent for about a second (you, 2026-10-16): the undo loaded
 * the snapshot whole, which re-prepares the playing song, and when the queue was playing on
 * another device the hub turned it into a `load` there. Where the edit left the playing song
 * alone (`undoKeepsPlayingSong`), undo now makes only the one edit that reverses it
 * (`queueUndoEdit`): the removed song put back, or the moved one moved back.
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

	private fun ids(vararg ids: String) = ids.map { song(it) }

	@Test
	fun aRemovedSongIsPutBackWhereItWas() {
		assertEquals(QueueUndoEdit.Insert(3, song("d")), queueUndoEdit(ids("a", "b", "c", "d", "e"), ids("a", "b", "c", "e")))
		assertEquals(QueueUndoEdit.Insert(0, song("a")), queueUndoEdit(ids("a", "b", "c"), ids("b", "c")))
		// The last song.
		assertEquals(QueueUndoEdit.Insert(2, song("c")), queueUndoEdit(ids("a", "b", "c"), ids("a", "b")))
		// A song the queue holds twice: either copy put back gives the same queue.
		assertEquals(ids("a", "b", "b", "c"), (queueUndoEdit(ids("a", "b", "b", "c"), ids("a", "b", "c")) as QueueUndoEdit.Insert).let { e ->
			ids("a", "b", "c").toMutableList().apply { add(e.index, e.song) }
		})
	}

	@Test
	fun aMovedSongIsMovedBack() {
		// "b" dragged down below "d": moved back up.
		assertEquals(QueueUndoEdit.Move(3, 1), queueUndoEdit(ids("a", "b", "c", "d", "e"), ids("a", "c", "d", "b", "e")))
		// "d" dragged up above "b": moved back down.
		assertEquals(QueueUndoEdit.Move(1, 3), queueUndoEdit(ids("a", "b", "c", "d", "e"), ids("a", "d", "b", "c", "e")))
		// Neighbours swapped.
		assertEquals(QueueUndoEdit.Move(1, 0), queueUndoEdit(ids("a", "b", "c"), ids("b", "a", "c")))
	}

	@Test
	fun moreThanOneEditIsNoSingleEdit() {
		assertNull(queueUndoEdit(ids("a", "b", "c", "d"), ids("a", "c")))
		assertNull(queueUndoEdit(ids("a", "b", "c", "d"), ids("d", "c", "b", "a")))
		assertNull(queueUndoEdit(ids("a", "b", "c"), ids("a", "b", "c")))
		assertNull(queueUndoEdit(ids("a", "b", "c"), ids("a", "x", "c")))
	}
}
