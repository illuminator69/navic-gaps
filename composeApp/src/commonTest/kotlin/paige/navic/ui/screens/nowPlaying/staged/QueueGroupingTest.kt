package paige.navic.ui.screens.nowPlaying.staged

import paige.navic.domain.manager.StationProvenance
import paige.navic.domain.models.SavedQueueSource
import paige.navic.ui.screens.nowPlaying.staged.QueueListItem.Header
import paige.navic.ui.screens.nowPlaying.staged.QueueListItem.Row
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The staged queue's rows (P2, `QueueGrouping.kt`): grouped by who put them there, keyed by the
 * mirror's stable uids, the peek row's "up next" in play order, and the "playing from" words.
 */
class QueueGroupingTest {

	private fun ids(n: Int) = (0 until n).map { "s$it" }
	private fun uids(n: Int) = (0 until n).map { 100L + it }

	private val station = StationProvenance(
		queueId = "q_1",
		stationId = "st_1",
		name = "Paper Lanterns radio",
		character = "balanced",
		served = listOf("s3", "s4", "s5")
	)

	@Test
	fun aQueueNoStationIsBoundToIsNowPlayingThenUpNext() {
		val items = queueListItems(ids(5), uids(5), currentIndex = 1, bound = null)
		assertEquals(
			listOf(
				Row(0, 100L, QueueGroup.Played),
				Header(QueueGroup.Playing, 101L),
				Row(1, 101L, QueueGroup.Playing),
				Header(QueueGroup.Next, 102L),
				Row(2, 102L, QueueGroup.Next),
				Row(3, 103L, QueueGroup.Next),
				Row(4, 104L, QueueGroup.Next)
			),
			items
		)
	}

	@Test
	fun aStationQueueIsQueuedByYouThenAutoplayOneHeaderPerRun() {
		val items = queueListItems(ids(6), uids(6), currentIndex = 0, bound = station)
		assertEquals(
			listOf(
				Header(QueueGroup.Playing, 100L),
				Row(0, 100L, QueueGroup.Playing),
				Header(QueueGroup.Queued, 101L),
				Row(1, 101L, QueueGroup.Queued),
				Row(2, 102L, QueueGroup.Queued),
				Header(QueueGroup.Autoplay, 103L),
				Row(3, 103L, QueueGroup.Autoplay),
				Row(4, 104L, QueueGroup.Autoplay),
				Row(5, 105L, QueueGroup.Autoplay)
			),
			items
		)
	}

	@Test
	fun aSongQueuedAfterTheAutoplayRunGetsASecondQueuedHeader() {
		val songs = listOf("s0", "s1", "s3", "s4", "s5", "s2")
		val items = queueListItems(songs, uids(6), currentIndex = 0, bound = station)
		assertEquals(
			listOf(
				Header(QueueGroup.Playing, 100L),
				Row(0, 100L, QueueGroup.Playing),
				Header(QueueGroup.Queued, 101L),
				Row(1, 101L, QueueGroup.Queued),
				Header(QueueGroup.Autoplay, 102L),
				Row(2, 102L, QueueGroup.Autoplay),
				Row(3, 103L, QueueGroup.Autoplay),
				Row(4, 104L, QueueGroup.Autoplay),
				Header(QueueGroup.Queued, 105L),
				Row(5, 105L, QueueGroup.Queued)
			),
			items
		)
	}

	@Test
	fun membershipInServedDecidesTheGroupNotWhetherTheStationRuns() {
		// Off kept s3 because it was nearly playing; s1 and s2 are the user's.
		val stopped = station.copy(stopped = true, served = listOf("s3"))
		val items = queueListItems(ids(4), uids(4), currentIndex = 0, bound = stopped)
		assertEquals(
			listOf(
				Header(QueueGroup.Playing, 100L),
				Row(0, 100L, QueueGroup.Playing),
				Header(QueueGroup.Queued, 101L),
				Row(1, 101L, QueueGroup.Queued),
				Row(2, 102L, QueueGroup.Queued),
				Header(QueueGroup.Autoplay, 103L),
				Row(3, 103L, QueueGroup.Autoplay)
			),
			items
		)
	}

	@Test
	fun oneSongIsNowPlayingOnlyAndAnEmptyQueueIsNothing() {
		assertEquals(
			listOf(Header(QueueGroup.Playing, 100L), Row(0, 100L, QueueGroup.Playing)),
			queueListItems(listOf("s0"), listOf(100L), currentIndex = 0, bound = station)
		)
		assertEquals(emptyList(), queueListItems(emptyList(), emptyList(), currentIndex = -1, bound = null))
	}

	@Test
	fun theSameSongTwiceIsTwoRowsWithTheirOwnKeys() {
		val items = queueListItems(listOf("s0", "s7", "s7"), listOf(5L, 9L, 12L), currentIndex = 0, bound = null)
		val rows = items.filterIsInstance<Row>()
		assertEquals(listOf(5L, 9L, 12L), rows.map { it.uid })
		assertEquals(listOf(1, 2), rows.filter { it.group == QueueGroup.Next }.map { it.index })
	}

	@Test
	fun nothingPlayingIsAllUpcoming() {
		// A queue whose index is not known yet (the first frame of a restore): no "Now playing".
		val items = queueListItems(ids(2), uids(2), currentIndex = -1, bound = null)
		assertEquals(listOf(Header(QueueGroup.Next, 100L), Row(0, 100L, QueueGroup.Next), Row(1, 101L, QueueGroup.Next)), items)
	}

	@Test
	fun thePeekRowIsTheNextSongInPlayOrder() {
		assertEquals(3, peekRow(ids(5), currentIndex = 2, order = null))
		assertNull(peekRow(ids(5), currentIndex = 4, order = null))
		assertEquals(1, peekRow(ids(5), currentIndex = 3, order = listOf(0, 3, 1, 2, 4)))
		assertNull(peekRow(ids(5), currentIndex = 4, order = listOf(0, 3, 1, 2, 4)))
		// An order for another queue (a frame behind) is not trusted: queue order.
		assertEquals(3, peekRow(ids(5), currentIndex = 2, order = listOf(0, 1, 2)))
		assertNull(peekRow(emptyList(), currentIndex = -1, order = null))
	}

	@Test
	fun playingFromNamesTheSourceAndOnlySaysWhatItKnows() {
		assertEquals(PlayingFrom("album", "Quiet Engines"), playingFromText(SavedQueueSource.ALBUM, null, "Quiet Engines"))
		assertEquals(PlayingFrom("playlist", "Late"), playingFromText(SavedQueueSource.PLAYLIST, null, "Late"))
		// A radio's name already says what it is.
		assertEquals(PlayingFrom(null, "Paper Lanterns radio"), playingFromText(SavedQueueSource.RADIO, "Paper Lanterns radio", null))
		// Nothing known: the header says nothing rather than a guess.
		assertNull(playingFromText(SavedQueueSource.MANUAL, null, null))
		assertNull(playingFromText(SavedQueueSource.ALBUM, " ", ""))
		// A name the user gave wins.
		assertEquals(PlayingFrom("album", "Sunday"), playingFromText(SavedQueueSource.ALBUM, "Sunday", "Quiet Engines"))
		assertEquals(PlayingFrom(null, "Search: rain"), playingFromText(SavedQueueSource.MOOD_FLOW, null, "Search: rain"))
	}
	@Test
	fun queuedByYouOnlyStandsBesideAnAutoplayGroup() {
		// A station whose songs this device never saw added (a transfer), or all taken out by Off:
		// nothing upcoming is the station's, so the rest is one "Up next", never "Queued by you".
		val unseen = station.copy(served = emptyList())
		assertEquals(
			listOf(Header(QueueGroup.Playing, 100L), Row(0, 100L, QueueGroup.Playing), Header(QueueGroup.Next, 101L), Row(1, 101L, QueueGroup.Next)),
			queueListItems(ids(2), uids(2), currentIndex = 0, bound = unseen)
		)
		// A served song already played does not make one either.
		val playedOnly = station.copy(served = listOf("s0"))
		assertEquals(
			listOf(Row(0, 100L, QueueGroup.Played), Header(QueueGroup.Playing, 101L), Row(1, 101L, QueueGroup.Playing), Header(QueueGroup.Next, 102L), Row(2, 102L, QueueGroup.Next)),
			queueListItems(ids(3), uids(3), currentIndex = 1, bound = playedOnly)
		)
	}

	@Test
	fun eachRowKnowsItsPlaceInItsRunForItsShape() {
		val items = queueListItems(ids(6), uids(6), currentIndex = 1, bound = station.copy(served = listOf("s4", "s5")))
		// s0 played | Playing s1 | Queued s2 s3 | Autoplay s4 s5
		assertEquals(
			mapOf(100L to (0 to 1), 101L to (0 to 1), 102L to (0 to 2), 103L to (1 to 2), 104L to (0 to 2), 105L to (1 to 2)),
			rowRunPositions(items)
		)
	}
}
