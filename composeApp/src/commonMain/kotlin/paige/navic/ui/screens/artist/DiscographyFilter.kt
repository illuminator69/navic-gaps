package paige.navic.ui.screens.artist

import paige.navic.ui.screens.artist.viewmodels.DiscographyEntry
import paige.navic.ui.screens.artist.viewmodels.DiscographySection

/*
 * navi-connect P4, round 2: the discography's filters, as the owner's mockups draw them. A row of
 * type chips with counts ("All 13", "Albums 5", "EPs 3"), an ownership menu (All, Owned, Partly
 * owned, Missing) and a sort, in place of every section stacked down the artist page. Pure, so the
 * counts and the order are tests (`DiscographyFilterTest`).
 */

/** Where the library stands on one release, as the ownership menu asks it. */
enum class Ownership { ALL, OWNED, PARTLY, MISSING }

enum class DiscographySort { MOST_PLAYED, NEWEST, OLDEST, TITLE }

data class DiscographyFilter(
	/** One release type (`album`, `ep`, …), or null for all of them. */
	val type: String? = null,
	val ownership: Ownership = Ownership.ALL,
	val sort: DiscographySort = DiscographySort.MOST_PLAYED
)

/** One type chip: null [type] is "All". */
data class TypeChip(val type: String?, val count: Int)

/**
 * What the list shows. [flat] when an ownership filter is on: one list with each row naming its
 * type, under a heading that counts the matches ("4 missing of 13"), as the mockup's filtered page
 * reads. Otherwise one section per type, in lb-bot's type order.
 */
data class DiscographyView(
	val sections: List<DiscographySection>,
	val flat: Boolean,
	val matched: Int,
	val total: Int
)

/** Each entry's release type (its section's), for a list that is not split by type. */
fun entryTypes(sections: List<DiscographySection>): Map<String, String> =
	sections.flatMap { section -> section.entries.map { it.key to section.type } }.toMap()

/** Owned covers a release lb-bot has placed and Navidrome has not indexed yet ("Added, syncing"). */
fun ownershipOf(entry: DiscographyEntry): Ownership = when {
	entry.gapGroupId != null -> Ownership.PARTLY
	entry.owned || entry.pendingSync -> Ownership.OWNED
	else -> Ownership.MISSING
}

/** "All N" first, then one chip per type that has releases, in the sections' order. */
fun typeChips(sections: List<DiscographySection>): List<TypeChip> {
	val withReleases = sections.filter { it.entries.isNotEmpty() }
	return listOf(TypeChip(null, withReleases.sumOf { it.entries.size })) +
		withReleases.map { TypeChip(it.type, it.entries.size) }
}

fun discographyView(sections: List<DiscographySection>, filter: DiscographyFilter): DiscographyView {
	val total = sections.sumOf { it.entries.size }
	// A type that has gone (a rescan changed the sections) shows everything rather than nothing.
	val typed = sections.filter { it.entries.isNotEmpty() }
		.filter { filter.type == null || it.type == filter.type }
		.ifEmpty { sections.filter { it.entries.isNotEmpty() } }
	val comparator = comparatorFor(filter.sort)
	if (filter.ownership == Ownership.ALL) {
		val sorted = typed.map { section -> section.copy(entries = section.entries.sortedWith(comparator)) }
		return DiscographyView(sorted, flat = false, matched = sorted.sumOf { it.entries.size }, total = total)
	}
	val matches = typed.flatMap { it.entries }
		.filter { ownershipOf(it) == filter.ownership }
		.sortedWith(comparator)
	return DiscographyView(
		sections = if (matches.isEmpty()) emptyList() else listOf(DiscographySection("", matches)),
		flat = true,
		matched = matches.size,
		total = total
	)
}

/**
 * The few releases the artist page shows above "See discography": the ones the library plays most,
 * then what has just landed, then the newest albums it lacks. Plays are Navidrome's, so a release
 * the library does not hold has none and cannot outrank one it plays.
 */
fun popularReleases(sections: List<DiscographySection>, limit: Int = 4): List<DiscographyEntry> {
	val all = sections.flatMap { it.entries }
	val owned = all.filter { it.album != null }
		.sortedWith(compareByDescending<DiscographyEntry> { it.album?.playCount ?: 0 }.then(newestFirst))
	val syncing = all.filter { it.pendingSync }.sortedWith(newestFirst)
	val missingAlbums = sections.filter { it.type == "album" }
		.flatMap { it.entries }
		.filter { ownershipOf(it) == Ownership.MISSING }
		.sortedWith(newestFirst)
	return (owned + syncing + missingAlbums).distinctBy { it.key }.take(limit)
}

/** Years are strings ("2019", sometimes blank); a blank one sorts after every real year. */
private fun yearOf(entry: DiscographyEntry): Int? = entry.year.take(4).toIntOrNull()

private val newestFirst: Comparator<DiscographyEntry> =
	compareBy<DiscographyEntry> { yearOf(it) == null }.thenByDescending { yearOf(it) ?: 0 }

private val oldestFirst: Comparator<DiscographyEntry> =
	compareBy<DiscographyEntry> { yearOf(it) == null }.thenBy { yearOf(it) ?: 0 }

private fun comparatorFor(sort: DiscographySort): Comparator<DiscographyEntry> = when (sort) {
	// Plays first; a release with none (or none to have: not in the library) falls back to newest.
	DiscographySort.MOST_PLAYED ->
		compareByDescending<DiscographyEntry> { it.album?.playCount ?: 0 }.then(newestFirst)
	DiscographySort.NEWEST -> newestFirst
	DiscographySort.OLDEST -> oldestFirst
	DiscographySort.TITLE -> compareBy { it.title.lowercase() }
}
