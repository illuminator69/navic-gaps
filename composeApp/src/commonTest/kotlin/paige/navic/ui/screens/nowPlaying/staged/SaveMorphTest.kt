package paige.navic.ui.screens.nowPlaying.staged

import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Save pill that grows into its menu (2026-10-20, `SaveMorph.kt`): where the panel lands, and the
 * shape between the pill and the panel.
 */
class SaveMorphTest {

	private val pill = Rect(left = 500f, top = 1900f, right = 840f, bottom = 2020f)

	@Test
	fun thePanelIsCentredOnThePillAndFloatsOverItsNeighbours() {
		val panel = saveMenuTarget(pill, contentWidth = 600f, contentHeight = 300f, boundsWidth = 1344f, edge = 48f)
		assertEquals(pill.center.x, panel.center.x)
		assertEquals(pill.center.y, panel.center.y)
		assertEquals(600f, panel.width)
		assertEquals(300f, panel.height)
	}

	@Test
	fun thePanelIsNeverNarrowerThanThePill() {
		val panel = saveMenuTarget(pill, contentWidth = 200f, contentHeight = 300f, boundsWidth = 1344f, edge = 48f)
		assertEquals(pill.width, panel.width)
	}

	@Test
	fun thePanelStaysClearOfTheScreensEdges() {
		val atRight = Rect(1100f, 1900f, 1300f, 2020f)
		val panel = saveMenuTarget(atRight, contentWidth = 600f, contentHeight = 300f, boundsWidth = 1344f, edge = 48f)
		assertEquals(1344f - 48f, panel.right)
		val atLeft = Rect(40f, 1900f, 240f, 2020f)
		assertEquals(48f, saveMenuTarget(atLeft, 600f, 300f, 1344f, 48f).left)
	}

	@Test
	fun theShapeRunsFromThePillToThePanelAndOvershootsWithTheSpring() {
		val panel = Rect(372f, 1810f, 972f, 2110f)
		assertEquals(pill, morphRect(pill, panel, 0f))
		assertEquals(panel, morphRect(pill, panel, 1f))
		val half = morphRect(pill, panel, 0.5f)
		assertTrue(abs(half.width - (pill.width + panel.width) / 2) < 0.01f)
		// An expressive spring passes 1 a little: the panel swells past its size, then settles.
		assertTrue(morphRect(pill, panel, 1.05f).width > panel.width)
	}

	@Test
	fun theCornersRunFromTheCapsuleToThePanels() {
		assertEquals(60f, morphCorner(pillHeight = 120f, panelCorner = 72f, progress = 0f))
		assertEquals(72f, morphCorner(pillHeight = 120f, panelCorner = 72f, progress = 1f))
	}
}
