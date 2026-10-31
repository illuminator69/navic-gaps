package paige.navic.domain.manager

import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Worbus settings N2: the stream format is part of what re-points a playing queue. Before, only the
 * bitrate was: a format picked in Settings waited for the next queue change.
 */
class CustomFormatAppliesTest {

	private fun prefs() = MemorySettings().let { PreferenceManager(it, testSecrets(it)) }

	/** Collects the config flow, applies [change], and returns how many configs were emitted. */
	private fun emissionsAfter(change: (PreferenceManager) -> Unit): Int {
		val prefs = prefs()
		val seen = CopyOnWriteArrayList<List<Any?>>()
		val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		try {
			return runBlocking {
				streamConfigChanges(
					preferenceManager = prefs,
					downloadedSongs = MutableStateFlow(emptyMap()),
					isCellular = MutableStateFlow(false),
					isOnline = MutableStateFlow(true)
				).onEach { seen += it }.launchIn(scope)
				delay(300)
				assertEquals(1, seen.size, "the first config is emitted once")
				change(prefs)
				Snapshot.sendApplyNotifications()
				delay(300)
				seen.size
			}
		} finally {
			scope.cancel()
		}
	}

	@Test
	fun aBitrateChangeReEmitsTheConfig() {
		assertEquals(2, emissionsAfter { it.customMaxBitrateWifi = 128 })
	}

	@Test
	fun aWifiFormatChangeReEmitsTheConfig() {
		assertEquals(2, emissionsAfter { it.customFormatWifi = "opus" })
	}

	@Test
	fun aCellularFormatChangeReEmitsTheConfig() {
		assertEquals(2, emissionsAfter { it.customFormatCellular = "mp3" })
	}

	@Test
	fun aChangeNothingStreamsFromDoesNotReEmit() {
		assertEquals(1, emissionsAfter { it.gaplessPlayback = !it.gaplessPlayback })
	}
}
