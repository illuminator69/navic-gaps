package paige.navic.domain.models.settings

import kotlinx.serialization.Serializable

@Serializable
data class NavbarTab(
	val id: Id,
	val visible: Boolean
) {
	@Serializable
	enum class Id {
		LIBRARY,
		ALBUMS,
		PLAYLISTS,
		ARTISTS,
		SEARCH,
		GENRES,
		SONGS,
		RADIOS,
		STATISTICS,

		/**
		 * New releases from ListenBrainz, via lb-bot.
		 *
		 * Unlike every id above it, this one is conditional: lb-bot is optional
		 * infrastructure, and the tab is filtered out of the bar entirely when it
		 * isn't reachable (see BottomBar). Enum entries are serialized by *name*, so
		 * appending here is safe for a persisted config — but a config written before
		 * this entry existed simply has no row for it, which is what NavbarConfig's
		 * merge on load exists to fix.
		 */
		FRESH
	}
}
