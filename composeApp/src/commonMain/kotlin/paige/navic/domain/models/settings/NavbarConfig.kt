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
				// Visible by default, and safe to be: the bar filters this tab out
				// entirely when lb-bot isn't reachable, which is the state most
				// installs are in. So it costs a slot only where it does something.
				NavbarTab(NavbarTab.Id.FRESH, true),
				// Same reasoning as FRESH: the bar drops it when nothing feeds it,
				// so being visible by default costs a slot only where it earns one.
				NavbarTab(NavbarTab.Id.DISCOVER, true)
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
	}
}
