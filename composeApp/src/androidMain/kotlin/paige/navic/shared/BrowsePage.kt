package paige.navic.shared

/**
 * A media3 browse request's `page` / `pageSize` as a `LIMIT` and an `OFFSET`, or null for a page
 * that holds nothing (a negative page, a size of 0 or less, an offset past what SQLite takes).
 * Android Auto asks with a real size; a browser that wants everything sends `Int.MAX_VALUE`,
 * which is a single page from 0.
 */
internal fun browsePage(page: Int, pageSize: Int): Pair<Int, Int>? {
	if (page < 0 || pageSize <= 0) return null
	val offset = page.toLong() * pageSize
	if (offset > Int.MAX_VALUE) return null
	return pageSize to offset.toInt()
}
