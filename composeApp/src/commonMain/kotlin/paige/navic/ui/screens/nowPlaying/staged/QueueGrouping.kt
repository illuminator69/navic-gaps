package paige.navic.ui.screens.nowPlaying.staged

import paige.navic.domain.manager.StationProvenance
import paige.navic.domain.models.SavedQueueSource

/*
 * navi-connect P2: the staged queue's rows grouped by who put them there, the peek row and the
 * "playing from" words, as pure functions (`QueueGroupingTest`).
 *
 * - "Autoplay": the song is in the bound station's served list (what this device saw it add). That
 *   membership decides, never whether the station still runs: a song Off kept because it was nearly
 *   playing is still the station's, and its header says the station is off.
 * - "Queued by you": a song on a bound station's queue that the station did not add. Only next to
 *   an autoplay group: an album's songs were never "queued by you".
 * - "Up next": a queue no station is bound to (an album, a playlist, a hand-made queue).
 *
 * Groups are runs in queue order, not a partition: a song the user adds after the autoplay run gets
 * a second "Queued by you" header there. Rows are keyed by the mirror's stable uid (a queue can hold
 * one song twice) and headers by their first row's uid, so a regroup does not replay animations.
 */

enum class QueueGroup { Played, Playing, Queued, Autoplay, Next }

sealed interface QueueListItem {
	/** A section label before a run of [group]; [key] is the run's first row's uid. */
	data class Header(val group: QueueGroup, val key: Long) : QueueListItem

	/** The queue's row [index], keyed by [uid]. */
	data class Row(val index: Int, val uid: Long, val group: QueueGroup) : QueueListItem
}

/**
 * The list the layer draws, in queue order: songs already played (no header), "Now playing", then
 * runs of "Queued by you" and "Autoplay" for a bound station's queue, or one "Up next" otherwise.
 * [songIds] and [uids] are the mirror's, index for index.
 */
fun queueListItems(
	songIds: List<String>,
	uids: List<Long>,
	currentIndex: Int,
	bound: StationProvenance?
): List<QueueListItem> {
	val count = minOf(songIds.size, uids.size)
	if (count == 0) return emptyList()
	val served = bound?.served?.toHashSet()
	val playing = currentIndex.takeIf { it in 0 until count }
	val items = ArrayList<QueueListItem>(count + 4)
	var previous: QueueGroup? = null
	for (i in 0 until count) {
		val group = when {
			playing != null && i < playing -> QueueGroup.Played
			i == playing -> QueueGroup.Playing
			served == null -> QueueGroup.Next
			songIds[i] in served -> QueueGroup.Autoplay
			else -> QueueGroup.Queued
		}
		if (group != QueueGroup.Played && group != previous) items += QueueListItem.Header(group, uids[i])
		items += QueueListItem.Row(i, uids[i], group)
		previous = group
	}
	return items
}

/**
 * The queue index of the song after the playing one in play order ([order], the shuffle order, or
 * null for queue order), or null at the end. An order that does not describe this queue (a frame
 * behind it) is not trusted.
 */
fun peekRow(songIds: List<String>, currentIndex: Int, order: List<Int>?): Int? {
	if (currentIndex !in songIds.indices) return null
	if (order != null && order.size == songIds.size && currentIndex in order) {
		return order.getOrNull(order.indexOf(currentIndex) + 1)
	}
	return (currentIndex + 1).takeIf { it < songIds.size }
}

/** The header's "Playing from": [kind] ("album", "playlist") when the name alone does not say it. */
data class PlayingFrom(val kind: String?, val name: String)

/**
 * What the queue is playing from: the name the user gave it ([name]) or the one it was born with
 * ([sourceName]), and the kind for an album or a playlist. A radio, a mix or a search is named for
 * what it is already ("Paper Lanterns radio", "Search: rain"). Null when nothing is known: the header
 * then says nothing rather than a guess.
 */
fun playingFromText(kind: String?, name: String?, sourceName: String?): PlayingFrom? {
	val shown = name?.takeIf { it.isNotBlank() } ?: sourceName?.takeIf { it.isNotBlank() } ?: return null
	val shownKind = when (kind) {
		SavedQueueSource.ALBUM, SavedQueueSource.PLAYLIST -> kind
		else -> null
	}
	return PlayingFrom(shownKind, shown)
}
