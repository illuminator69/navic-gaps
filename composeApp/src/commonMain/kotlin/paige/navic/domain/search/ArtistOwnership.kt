package paige.navic.domain.search

import paige.navic.data.database.dao.ArtistMbidRow
import paige.navic.domain.manager.LbArtistOverride

/**
 * P3: which of lb-bot's MusicBrainz artist hits the library already holds, by MBID and never by
 * name (two artists share names, and a name match would put a stranger in the library section).
 * A library artist's MBID is its EFFECTIVE one (CLAUDE.md §5): the user's override where there is
 * one, else Navidrome's tag. lb-bot's index mirror is the third source: a row keyed by the MBID
 * that names a Navidrome artist, for artists Navidrome has no tag for. Every answer is an artist
 * Room still lists ([held]), because the library page opens from Room. Map: MBID to Navidrome id.
 */
internal fun ownedArtistsByMbid(
	mbids: Collection<String>,
	overrides: Map<String, LbArtistOverride>,
	tagged: List<ArtistMbidRow>,
	mirrored: Map<String, String>,
	held: Set<String>
): Map<String, String> {
	val wanted = mbids.filter { it.isNotBlank() }.toSet()
	if (wanted.isEmpty()) return emptyMap()
	fun overriddenAway(ndId: String, mbid: String): Boolean {
		val picked = overrides[ndId]?.mbid?.takeIf { it.isNotBlank() } ?: return false
		return picked != mbid
	}
	val out = LinkedHashMap<String, String>()
	for ((ndId, override) in overrides) {
		val mbid = override.mbid
		if (mbid in wanted && ndId.isNotBlank() && ndId in held && mbid !in out) out[mbid] = ndId
	}
	for (row in tagged) {
		val mbid = row.musicBrainzId ?: continue
		if (mbid in wanted && mbid !in out && !overriddenAway(row.artistId, mbid)) out[mbid] = row.artistId
	}
	for ((mbid, ndId) in mirrored) {
		if (mbid in wanted && mbid !in out && ndId.isNotBlank() && ndId in held && !overriddenAway(ndId, mbid)) {
			out[mbid] = ndId
		}
	}
	return out
}
