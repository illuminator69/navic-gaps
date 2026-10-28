package paige.navic.ui.a11y

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** M7 (audit L4a-11): the queue's handle and the mini header's device button have names. */
class NamedControlsTest {
	private val root = File("src/commonMain/kotlin/paige/navic/ui/screens/nowPlaying/staged")

	@Test
	fun theQueueHandleIsNamed() {
		assertTrue("Res.string.staged_collapse_queue" in File(root, "StagedQueueLayer.kt").readText())
	}

	@Test
	fun theMiniHeadersDeviceButtonIsNamed() {
		val text = File(root, "StagedNowPlaying.kt").readText()
		assertTrue(!Regex("""Icons\.Outlined\.Devices, contentDescription = null""").containsMatchIn(text))
	}
}
