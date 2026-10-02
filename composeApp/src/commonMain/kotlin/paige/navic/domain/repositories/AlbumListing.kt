package paige.navic.domain.repositories

/** Albums per `getAlbumList2` page. */
const val ALBUM_PAGE_SIZE = 500

/**
 * How many albums each page re-reads from the end of the one before it (B-037).
 *
 * The listing is paged by offset over a list Navidrome may be editing while we walk it. An
 * album removed from, or renamed out of, the part already read shifts everything after it back
 * by one, and the album that crosses the page boundary is never read. A full sync then prunes it
 * from Room as gone. Re-reading this many albums of the previous page absorbs up to this many
 * such shifts between two page calls; [listAllAlbums] drops the repeats.
 */
const val ALBUM_PAGE_OVERLAP = 50

/** A `getScanStatus` answer, reduced to what tells a still library from a moving one. */
data class ScanMark(val scanning: Boolean, val count: Int)

/**
 * The whole album list, and whether it can be trusted to be the whole library.
 *
 * [stable] is false when the library may have moved under the walk: a scan running at either
 * end of it, the file count changed between the two ends, or a status that could not be read.
 * An unstable listing is still good for upserting what it found. It is not good for pruning,
 * because an album can be missing from it without being missing from the server.
 */
data class AlbumListing<T>(val albums: List<T>, val stable: Boolean)

/**
 * Walk an offset-paged album list, bracketed by two scan-status reads.
 *
 * Shared by the full sync and the changed-album sweep (`DbRepository`), so both walk the list
 * the same way. Pages overlap by [overlap] and repeats are dropped by [id], keeping the first.
 * Stops on a short page, or on a full page that brought nothing new (a server that ignores the
 * offset), which also makes the listing unstable.
 */
suspend fun <T> listAllAlbums(
	page: suspend (size: Int, offset: Int) -> List<T>,
	id: (T) -> String,
	scanMark: suspend () -> ScanMark?,
	pageSize: Int = ALBUM_PAGE_SIZE,
	overlap: Int = ALBUM_PAGE_OVERLAP
): AlbumListing<T> {
	require(pageSize > 0 && overlap in 0 until pageSize) { "overlap must be smaller than the page" }
	val before = scanMark()
	val seen = HashSet<String>()
	val albums = mutableListOf<T>()
	var offset = 0
	var stalled = false
	while (true) {
		val batch = page(pageSize, offset)
		var added = 0
		for (album in batch) {
			if (seen.add(id(album))) {
				albums += album
				added++
			}
		}
		if (batch.size < pageSize) break
		if (added == 0) {
			stalled = true
			break
		}
		offset += pageSize - overlap
	}
	val after = scanMark()
	return AlbumListing(albums, stable = !stalled && listingWasStill(before, after))
}

/** Neither end of the walk saw a scan, and the library's file count did not move between them. */
fun listingWasStill(before: ScanMark?, after: ScanMark?): Boolean =
	before != null && after != null &&
		!before.scanning && !after.scanning &&
		before.count == after.count
