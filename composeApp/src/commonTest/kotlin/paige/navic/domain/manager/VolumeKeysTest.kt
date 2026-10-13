package paige.navic.domain.manager

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VolumeKeysTest {
	@Test
	fun theButtonsAreThePlayingDevicesOnlyWhereNothingElseCanMeanThem() {
		// Navic in front: it draws the level itself.
		assertTrue(volumeKeysGoRemote(appOnScreen = true, phoneLocked = false))
		// Locked, in a pocket: no other app in front, nothing local to turn down.
		assertTrue(volumeKeysGoRemote(appOnScreen = false, phoneLocked = true))
		assertTrue(volumeKeysGoRemote(appOnScreen = true, phoneLocked = true))
		// Another app in front: the phone's own volume, with the system's slider.
		assertFalse(volumeKeysGoRemote(appOnScreen = false, phoneLocked = false))
	}
}
