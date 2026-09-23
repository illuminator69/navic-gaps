package paige.navic.domain.models.settings

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class NavbarConfig(
	val tabs: List<NavbarTab>,
	val version: Int
) {
	companion object {
		const val KEY = "navbarConfig"
		// Deliberately NOT bumped for alpha59's STATISTICS tab. Upstream went 7 -> 8 because a
		// version bump is the only way it can introduce a tab; this fork has merged() instead,
		// which appends unknown ids while keeping the user's own order and visibility. Bumping
		// here would discard every existing install's arrangement to gain one row.
		const val VERSION = 7
		val default = NavbarConfig(
			tabs = listOf(
				NavbarTab(NavbarTab.Id.LIBRARY, true),
				NavbarTab(NavbarTab.Id.ALBUMS, true),
				NavbarTab(NavbarTab.Id.PLAYLISTS, true),
				NavbarTab(NavbarTab.Id.ARTISTS, true),
				NavbarTab(NavbarTab.Id.SEARCH, false),
				NavbarTab(NavbarTab.Id.GENRES, false),
				NavbarTab(NavbarTab.Id.SONGS, false),
				NavbarTab(NavbarTab.Id.RADIOS, false),
				NavbarTab(NavbarTab.Id.STATISTICS, false),
				// All three hidden by default now: they are the library home's top
				// buttons instead (`rememberOverviewButtons`), which is where they
				// stopped competing with Library/Albums/Playlists/Artists for a
				// five-slot bar. The ids stay defined and stay listed in the reorder
				// dialog, so any of them can be put back — removing them would make
				// `merged()`'s live filter strip them from every stored config
				// permanently.
				NavbarTab(NavbarTab.Id.FRESH, false),
				NavbarTab(NavbarTab.Id.DISCOVER, false),
				NavbarTab(NavbarTab.Id.MIXES, false)
			),
			version = VERSION
		)

		/**
		 * A stored config plus whatever tab ids it has never heard of, appended in
		 * their default order and visibility.
		 *
		 * The version check alone was not enough and bumping it is the wrong fix:
		 * `VERSION` mismatch discards the config outright, so every existing user
		 * would lose their own tab order and visibility to gain one row. Merging
		 * keeps their arrangement and still guarantees a newly added tab exists —
		 * which is the trap this whole class is easiest to get wrong on, since a tab
		 * absent from the persisted list is simply absent forever.
		 */
		fun merged(stored: NavbarConfig): NavbarConfig {
			val known = stored.tabs.mapTo(mutableSetOf()) { it.id }
			val missing = default.tabs.filterNot { it.id in known }
			// Drop ids this build no longer defines, so a downgrade-then-upgrade
			// can't leave a row nothing maps to.
			val live = stored.tabs.filter { tab ->
				default.tabs.any { it.id == tab.id }
			}
			return if (missing.isEmpty() && live.size == stored.tabs.size) stored
			else stored.copy(tabs = live + missing)
		}

		/** Tabs the home-buttons move takes off the bar, once, on an existing install. */
		val HOME_BUTTON_TABS = setOf(
			NavbarTab.Id.FRESH,
			NavbarTab.Id.DISCOVER,
			NavbarTab.Id.MIXES
		)

		/**
		 * Hide the three tabs that became library-home buttons — once, on a config
		 * written before that change.
		 *
		 * Flipping the defaults above is not enough on its own, because `merged()`
		 * deliberately preserves a stored tab's visibility: an install that already
		 * has Fresh and Discover on the bar would keep them there forever and never
		 * see the change. The alternative is bumping [VERSION], which discards the
		 * user's whole arrangement to move three flags — exactly what `merged()`
		 * exists to avoid.
		 *
		 * So this is a targeted migration: order untouched, every other tab's
		 * visibility untouched, and guarded by its own preference so a user who puts
		 * one of them back is not overruled on the next launch.
		 */
		fun withHomeButtonTabsHidden(stored: NavbarConfig): NavbarConfig = stored.copy(
			tabs = stored.tabs.map { tab ->
				if (tab.id in HOME_BUTTON_TABS && tab.visible) tab.copy(visible = false) else tab
			}
		)
	}
}
