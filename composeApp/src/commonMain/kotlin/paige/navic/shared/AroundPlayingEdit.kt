package paige.navic.shared

/**
 * The media-item edits that turn the player's queue into a new one around the playing song, for
 * a hub `queueChanged` (S-035), touching no more than changed.
 *
 * The playing item is never removed or re-added: that is a re-prepare, a gap in the music. What
 * the new queue shares with the old one right after the playing song stays too, so an edit that
 * cuts after the NEXT song (the hub's cut with under 20 s left, when that song may already be
 * loading for a gapless start) leaves the next item alone; and what the two share at their ends
 * stays. Only the run between is removed and the new run put in its place, and the same before
 * the playing song. Apply the tail first: its indices are all after the playing item, so the head
 * edit after it still finds the old head where it was.
 */
data class AroundPlayingEdit(
	/** Old-queue index where the tail edit happens (after the playing song). */
	val tailAt: Int,
	/** How many old items go from [tailAt]. */
	val tailRemove: Int,
	/** New-queue indices put in at [tailAt], in order. */
	val tailInsert: IntRange,
	/** Old-queue index where the head edit happens (before the playing song). */
	val headAt: Int,
	val headRemove: Int,
	val headInsert: IntRange
) {
	/** Nothing to do: the queues already agree. */
	val isEmpty: Boolean get() = tailRemove == 0 && tailInsert.isEmpty() && headRemove == 0 && headInsert.isEmpty()
}

/**
 * The edit from [oldIds] playing [oldIndex] to [newIds] playing [newIndex], or null when the
 * playing song is not the same one (that is a load, not an edit).
 */
fun aroundPlayingEdit(oldIds: List<String>, oldIndex: Int, newIds: List<String>, newIndex: Int): AroundPlayingEdit? {
	if (oldIndex !in oldIds.indices || newIndex !in newIds.indices) return null
	if (oldIds[oldIndex] != newIds[newIndex]) return null
	val (tailSame, tailEnd) = sharedEnds(
		oldIds.subList(oldIndex + 1, oldIds.size),
		newIds.subList(newIndex + 1, newIds.size)
	)
	val (headSame, headEnd) = sharedEnds(oldIds.subList(0, oldIndex), newIds.subList(0, newIndex))
	return AroundPlayingEdit(
		tailAt = oldIndex + 1 + tailSame,
		tailRemove = (oldIds.size - tailEnd) - (oldIndex + 1 + tailSame),
		tailInsert = (newIndex + 1 + tailSame) until (newIds.size - tailEnd),
		headAt = headSame,
		headRemove = (oldIndex - headEnd) - headSame,
		headInsert = headSame until (newIndex - headEnd)
	)
}

/** How many items [a] and [b] share at their start, and then at their end (never overlapping). */
private fun sharedEnds(a: List<String>, b: List<String>): Pair<Int, Int> {
	val most = minOf(a.size, b.size)
	var start = 0
	while (start < most && a[start] == b[start]) start++
	var end = 0
	while (end < most - start && a[a.size - 1 - end] == b[b.size - 1 - end]) end++
	return start to end
}
