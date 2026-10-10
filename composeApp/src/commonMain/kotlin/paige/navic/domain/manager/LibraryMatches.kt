package paige.navic.domain.manager

/*
 * "Library Matches" (I-026): the rules of the page that took over from the "lb-bot data" section
 * of the navi-connect settings — which of its four sections it opens on, what each section's
 * chip says, and what a row draws for its picture and whether it is a link. Kept free of Compose
 * and Room so they can be run in a test (`LibraryMatchesTest`). The sentences the rows say are
 * where they were, in `LbBotData.kt` and `MatchesToCheck.kt`.
 *
 * Feishin's Library Matches page is the twin: the same four sections under the same names.
 */

/** The page's sections, in the order they are offered. */
enum class MatchesSection { TO_CHECK, ARTISTS, ALBUMS, MERGES }

/**
 * The sections on offer. **Artists is always one**: the override list is what the page is gated
 * on. The other three ride the same body and each may be absent from it — an lb-bot that
 * predates the list, or a hub that does not carry its route — and an absent list is no section,
 * not an empty one.
 */
internal fun matchesSections(toCheck: Boolean, albums: Boolean, merges: Boolean): List<MatchesSection> =
	listOfNotNull(
		MatchesSection.TO_CHECK.takeIf { toCheck },
		MatchesSection.ARTISTS,
		MatchesSection.ALBUMS.takeIf { albums },
		MatchesSection.MERGES.takeIf { merges }
	)

/**
 * The section shown. The user's own choice while it is still on offer; otherwise the first with
 * something to do — **To check when it has rows, else Artists**.
 */
internal fun matchesSectionToShow(
	chosen: MatchesSection?,
	offered: List<MatchesSection>,
	toCheckCount: Int
): MatchesSection = when {
	chosen != null && chosen in offered -> chosen
	toCheckCount > 0 && MatchesSection.TO_CHECK in offered -> MatchesSection.TO_CHECK
	else -> MatchesSection.ARTISTS
}

/** A section's chip: its name, and its count once the list has been loaded ([count] non-null). */
internal fun matchesSectionLabel(section: MatchesSection, count: Int?): String {
	val name = when (section) {
		MatchesSection.TO_CHECK -> "To check"
		MatchesSection.ARTISTS -> "Artists"
		MatchesSection.ALBUMS -> "Albums"
		MatchesSection.MERGES -> "Merged names"
	}
	return if (count == null) name else "$name ($count)"
}

// ----- what a row draws ---------------------------------------------------------------------------

/**
 * The picture beside a row or on a card, decided from the ids the row already carries — never a
 * request per row to learn an image.
 */
sealed interface LbArt {
	/**
	 * Navidrome's own art for something the library holds. [coverArtId] null: the library holds
	 * it and it has no picture, which the art component draws as its placeholder.
	 */
	data class Library(val coverArtId: String?, val isArtist: Boolean) : LbArt

	/** A public image URL: the cover lb-bot was given, or the Cover Art Archive's front. */
	data class Remote(val url: String) : LbArt

	/** Nothing names a picture. */
	data class Placeholder(val isArtist: Boolean) : LbArt
}

/**
 * Whether the library holds [id] — the keys of [held] are the ids Room has, each with its cover
 * art id. **An id is only a link when its page exists.**
 */
internal fun heldInLibrary(id: String, held: Map<String, String?>): Boolean =
	id.isNotBlank() && held.containsKey(id)

/** A library artist's picture: Navidrome's when Room holds the artist, the placeholder when it is gone. */
internal fun libraryArtistArt(ndArtistId: String, held: Map<String, String?>): LbArt =
	if (heldInLibrary(ndArtistId, held)) LbArt.Library(held[ndArtistId], isArtist = true)
	else LbArt.Placeholder(isArtist = true)

/**
 * An album override row's picture.
 * - The library holds [albumId]: that album's cover.
 * - [albumId] is not known yet ("" — Navidrome has not indexed the retagged album): the cover
 *   lb-bot was given for it ([coverUrl]) when there is one, else the placeholder.
 * - [albumId] is named and the library does not hold it — gone, or not synced yet: the
 *   placeholder, and the row is not a link.
 */
internal fun matchAlbumArt(albumId: String, coverUrl: String, held: Map<String, String?>): LbArt = when {
	heldInLibrary(albumId, held) -> LbArt.Library(held[albumId], isArtist = false)
	albumId.isBlank() && coverUrl.isNotBlank() -> LbArt.Remote(coverUrl)
	else -> LbArt.Placeholder(isArtist = false)
}
