package paige.navic.data.database.relations

import androidx.room3.Embedded
import androidx.room3.Relation
import paige.navic.data.database.entities.LbIndexArtistEntity
import paige.navic.data.database.entities.LbIndexReleaseEntity

/**
 * One mirrored artist with all of its release-group rows.
 *
 * Room fetches a `@Relation` without an ORDER BY, so [releases] arrive in no particular order —
 * read [ordered] (by `position`, i.e. lb-bot's own `year, title` order) wherever order matters.
 */
data class LbIndexArtistWithReleases(
	@Embedded val artist: LbIndexArtistEntity,
	@Relation(
		parentColumns = ["artistKey"],
		entityColumns = ["artistKey"]
	)
	val releases: List<LbIndexReleaseEntity>
) {
	val ordered: List<LbIndexReleaseEntity> get() = releases.sortedBy { it.position }
}
