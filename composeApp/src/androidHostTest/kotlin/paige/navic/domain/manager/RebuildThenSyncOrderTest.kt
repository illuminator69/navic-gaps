package paige.navic.domain.manager

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Worbus settings N6: Rebuild database stops the periodic sync, wipes, and only then starts the pull.
 * Before, the pull was started beside the wipe and could read a library that was still being emptied.
 */
class RebuildThenSyncOrderTest {

	@Test
	fun theSyncStartsAfterTheWipeHasFinished() = runBlocking {
		val events = mutableListOf<String>()
		rebuildLibrary(
			stopSync = { events += "stop" },
			wipe = { events += "wipe starts"; delay(50); events += "wipe done" },
			sync = { events += "sync" }
		)
		assertEquals(listOf("stop", "wipe starts", "wipe done", "sync"), events)
	}

	@Test
	fun aWipeThatTakesLongerStillComesFirst() = runBlocking {
		val events = mutableListOf<String>()
		rebuildLibrary(
			stopSync = { events += "stop" },
			wipe = { delay(200); events += "wipe done" },
			sync = { events += "sync" }
		)
		assertEquals("sync", events.last())
		assertEquals(3, events.size)
	}
}
