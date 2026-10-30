package paige.navic.ui.screens.queue

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Row 11 (the idea of upstream ecde4c76): only the current song's queue row scrolls its text. */
class QueueMarqueeTest {
	@Test
	fun onlyTheCurrentRowScrolls() {
		val item = File("src/commonMain/kotlin/paige/navic/ui/screens/queue/components/Item.kt").readText()
		val marquees = Regex("""MarqueeText\(([^)]*)\)""").findAll(item).map { it.groupValues[1] }.toList()
		assertEquals(2, marquees.size, marquees.toString())
		marquees.forEach { assertTrue("scrolls = isCurrentTrack" in it, it) }
	}

	@Test
	fun aStillLineIsTheReducedMotionOne() {
		val marquee = File("src/commonMain/kotlin/paige/navic/ui/components/common/Marquee.kt").readText()
		assertTrue("if (scrolls && preferenceManager.marqueeSpeed != MarqueeSpeed.Disabled && !rememberReducedMotion())" in marquee)
	}
}
