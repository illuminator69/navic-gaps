package paige.navic.ui.navigation

import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope

/**
 * navi-connect: material's list-detail strategy, with one scene per list page rather than one
 * per scene key.
 *
 * Material keys a two-pane scene by its `sceneKey` alone, and every library page is a
 * `listPane("root")`. With room for two panes (landscape, a tablet) the library home and the
 * page a home button opens (Discover, Fresh, Random, Recently added, Mixed for You) are then the
 * same scene to NavDisplay: the push only swaps what the list pane draws, so NavDisplay has no
 * scene change to animate and the page appeared at once, where in portrait (one pane, so one
 * scene per entry) it slides in.
 *
 * Keyed by the list page as well, a push or pop between list pages is a scene change NavDisplay
 * animates as it does in portrait, while a detail opening beside its list (an album from Random)
 * stays inside one scene and keeps the scaffold's own pane motion. Everything but the key is
 * material's scene, unchanged, but for the entries it claims (see [ListPageScene]). A list page is
 * an entry whose metadata carries [listPage] beside its `listPane(...)` (material's own marker is
 * internal to it).
 */
class ListPageSceneStrategy<T : Any>(
	private val listDetail: SceneStrategy<T>
) : SceneStrategy<T> {
	override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
		val scene = with(listDetail) { calculateScene(entries) } ?: return null
		val listPage = scene.entries.lastOrNull { it.metadata[LIST_PAGE_KEY] == true } ?: return scene
		return ListPageScene(scene, listPage)
	}

	companion object {
		private const val LIST_PAGE_KEY = "paige.navic.listPage"

		/** Marks an entry as a list page of its own, for [ListPageSceneStrategy]'s scene key. */
		fun listPage(): Map<String, Any> = mapOf(LIST_PAGE_KEY to true)
	}
}

private class ListPageScene<T : Any>(
	scene: Scene<T>,
	listPage: NavEntry<T>
) : Scene<T> by scene {
	override val key: Any = scene.key to listPage.contentKey

	// From the list page on. Material's scene also lists the list pages under it, which it does not
	// draw; NavDisplay then took the page being left (the home) out of the scene being left at the
	// push's first frame, so for a frame neither scene drew anything and the window's surface
	// flashed white.
	override val entries: List<NavEntry<T>> =
		scene.entries.subList(scene.entries.lastIndexOf(listPage), scene.entries.size)
}
