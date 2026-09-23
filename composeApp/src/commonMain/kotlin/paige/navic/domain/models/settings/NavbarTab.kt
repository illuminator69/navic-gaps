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
		FRESH,

		/**
		 * The Discover screen. Conditional like [FRESH], but on a wider gate: it
		 * also carries AudioMuse mood search and the rediscovery set, so it is
		 * shown when *anything* feeds it rather than when lb-bot specifically is
		 * up (see `rememberVisibleNavigationTabs`).
		 */
		DISCOVER,

		/**
		 * "Mixed for You" — the stored regenerating recipes.
		 *
		 * Conditional on the hub rather than on lb-bot or AudioMuse, because the
		 * recipes themselves live hub-side: with no hub there is nowhere for a mix
		 * to exist, so the tab leads to a list that can only ever be empty.
		 * Appended, and [paige.navic.domain.models.settings.NavbarConfig.VERSION] is
		 * NOT bumped for it — `merged()` is what introduces a tab here.
		 */
		MIXES
	}
}
