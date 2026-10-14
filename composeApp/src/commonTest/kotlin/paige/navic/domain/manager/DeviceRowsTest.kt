package paige.navic.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * The rules in DeviceRows.kt and the volume arithmetic of HubLocalDevice.kt: what a device's
 * row draws and says, where it stands in the picker, and how a hub level and the phone's own
 * volume steps are turned into each other.
 */
class DeviceRowsTest {
	private fun device(
		id: String,
		name: String = id,
		online: Boolean = true,
		isActive: Boolean = false,
		reachable: Boolean? = null,
		kind: String? = null
	) = HubDevice(
		id = id, name = name, platform = "android", online = online, isActive = isActive,
		volume = 100, reachable = reachable, kind = kind
	)

	// --- kind ------------------------------------------------------------------------------

	@Test
	fun everyKnownKindHasItsOwnIconAndTheRestTheGenericOne() {
		assertEquals(DeviceKindIcon.COMPUTER, deviceKindIcon("computer"))
		assertEquals(DeviceKindIcon.PHONE, deviceKindIcon("phone"))
		assertEquals(DeviceKindIcon.TABLET, deviceKindIcon("tablet"))
		assertEquals(DeviceKindIcon.SPEAKER, deviceKindIcon("speaker"))
		assertEquals(DeviceKindIcon.TV, deviceKindIcon("tv"))
		assertEquals(DeviceKindIcon.GROUP, deviceKindIcon("group"))
		// A device that said nothing, and a kind a newer client invented.
		assertEquals(DeviceKindIcon.GENERIC, deviceKindIcon(null))
		assertEquals(DeviceKindIcon.GENERIC, deviceKindIcon("watch"))
		assertEquals(DeviceKindIcon.GENERIC, deviceKindIcon(""))
	}

	@Test
	fun aScreenSixHundredDpAcrossItsNarrowSideIsATablet() {
		assertEquals("phone", phoneOrTablet(411))
		assertEquals("phone", phoneOrTablet(599))
		assertEquals("tablet", phoneOrTablet(600))
		assertEquals("tablet", phoneOrTablet(800))
		// The system gave no width.
		assertEquals("phone", phoneOrTablet(0))
	}

	// --- what a row says ---------------------------------------------------------------------

	@Test
	fun theActiveDeviceSaysPausedWhileItHoldsThePausedSession() {
		val active = device("pc", isActive = true)
		assertEquals(DeviceRowStatus.PLAYING, deviceRowStatus(active, "phone", sessionPlaying = true))
		assertEquals(DeviceRowStatus.PAUSED, deviceRowStatus(active, "phone", sessionPlaying = false))
		assertEquals("paused", deviceRowStatus(active, "phone", sessionPlaying = false).label)
		// Also when it is this very device.
		assertEquals(DeviceRowStatus.PAUSED, deviceRowStatus(active, "pc", sessionPlaying = false))
	}

	@Test
	fun aRowThatIsNotActiveSaysWhatCanBeDoneWithIt() {
		assertEquals(DeviceRowStatus.THIS_DEVICE, deviceRowStatus(device("phone"), "phone", true))
		assertEquals(DeviceRowStatus.AVAILABLE, deviceRowStatus(device("pc"), "phone", true))
		// A bridge whose speaker is gone: its socket is up, the hardware is not.
		assertEquals(
			DeviceRowStatus.NOT_RESPONDING,
			deviceRowStatus(device("cast-tv", reachable = false), "phone", true)
		)
		assertEquals(DeviceRowStatus.AVAILABLE, deviceRowStatus(device("cast-tv", reachable = true), "phone", true))
		assertEquals(DeviceRowStatus.OFFLINE, deviceRowStatus(device("pc", online = false), "phone", true))
		// Whether the session plays is the active device's business only.
		assertEquals(DeviceRowStatus.AVAILABLE, deviceRowStatus(device("pc"), "phone", sessionPlaying = false))
	}

	@Test
	fun theOtherTwoPlacesUseTheSameWord() {
		assertEquals("playing", activeDeviceWord(true))
		assertEquals("paused", activeDeviceWord(false))
		assertEquals("Playing on Kitchen", remoteSessionCue("Kitchen", sessionPlaying = true))
		assertEquals("Paused on Kitchen", remoteSessionCue("Kitchen", sessionPlaying = false))
	}

	// --- order -----------------------------------------------------------------------------

	@Test
	fun thePickerPutsThePlayingDeviceFirstThenThisOneThenTheRestByName() {
		val rows = listOf(
			device("d1", name = "zebra"),
			device("d2", name = "Attic"),
			device("me", name = "My phone"),
			device("d3", name = "Kitchen", isActive = true),
			device("d4", name = "bedroom")
		)
		assertEquals(
			listOf("Kitchen", "My phone", "Attic", "bedroom", "zebra"),
			pickerOrder(rows, myDeviceId = "me").map { it.name }
		)
	}

	@Test
	fun whatCannotTakeTheSessionComesLastNotRespondingBeforeOffline() {
		val rows = listOf(
			device("off", name = "Aardvark", online = false),
			device("gone", name = "Basement TV", reachable = false),
			device("ok", name = "Zoo")
		)
		assertEquals(listOf("ok", "gone", "off"), pickerOrder(rows, myDeviceId = "me").map { it.id })
	}

	@Test
	fun thisDeviceLeadsWhenItIsTheOnePlayingAndNamesakesKeepTheirOrder() {
		val rows = listOf(
			device("b", name = "Speaker"),
			device("a", name = "speaker"),
			device("me", name = "Phone", isActive = true)
		)
		val ordered = pickerOrder(rows, myDeviceId = "me").map { it.id }
		assertEquals(listOf("me", "a", "b"), ordered)
		// The same answer whatever order the hub listed them in.
		assertEquals(ordered, pickerOrder(rows.reversed(), myDeviceId = "me").map { it.id })
	}

	// --- the phone's volume steps ------------------------------------------------------------

	@Test
	fun aHubLevelLandsOnTheNearestStepOfTheStream() {
		// 15 steps, the usual phone.
		assertEquals(0, streamIndexForLevel(0, minIndex = 0, maxIndex = 15))
		assertEquals(15, streamIndexForLevel(100, minIndex = 0, maxIndex = 15))
		assertEquals(6, streamIndexForLevel(37, minIndex = 0, maxIndex = 15))
		assertEquals(8, streamIndexForLevel(50, minIndex = 0, maxIndex = 15))
		// A level from outside the range is the nearest end.
		assertEquals(15, streamIndexForLevel(250, minIndex = 0, maxIndex = 15))
		assertEquals(0, streamIndexForLevel(-5, minIndex = 0, maxIndex = 15))
		// A stream whose lowest step is not 0.
		assertEquals(1, streamIndexForLevel(0, minIndex = 1, maxIndex = 7))
		assertEquals(4, streamIndexForLevel(50, minIndex = 1, maxIndex = 7))
	}

	@Test
	fun aStepIsReportedAsTheLevelThatSetsItAgain() {
		assertEquals(0, levelForStreamIndex(0, minIndex = 0, maxIndex = 15))
		assertEquals(100, levelForStreamIndex(15, minIndex = 0, maxIndex = 15))
		// Told 37, the phone sets step 6 and says 40.
		assertEquals(40, levelForStreamIndex(6, minIndex = 0, maxIndex = 15))
		for ((min, max) in listOf(0 to 15, 0 to 25, 1 to 7, 0 to 30, 0 to 100, 0 to 1)) {
			for (index in min..max) {
				val level = levelForStreamIndex(index, min, max)
				assertEquals(index, streamIndexForLevel(level, min, max), "step $index of $min..$max, level $level")
			}
		}
	}

	@Test
	fun aStreamWithNoRangeHasOneStepAndIsFull() {
		assertEquals(3, streamIndexForLevel(40, minIndex = 3, maxIndex = 3))
		assertEquals(100, levelForStreamIndex(3, minIndex = 3, maxIndex = 3))
	}

	@Test
	fun everyOtherDeviceKeepsItsSliderInItsOwnRowWherePlaybackIs() {
		val idle = device(id = "desk").copy(caps = setOf("receiver", CAP_IDLE_VOLUME))
		val playing = device(id = "desk", isActive = true)
		// The playing device: on any hub, since it is the hub's default target.
		assertTrue(rowVolumeOffered(playing, myDeviceId = "phone", hubSetsRowVolumes = false))
		// Idle: only where the hub reads a target and the device takes a volume while idle.
		assertTrue(rowVolumeOffered(idle, myDeviceId = "phone", hubSetsRowVolumes = true))
		assertFalse(rowVolumeOffered(idle, myDeviceId = "phone", hubSetsRowVolumes = false))
		assertFalse(rowVolumeOffered(device(id = "desk"), myDeviceId = "phone", hubSetsRowVolumes = true))
		// Never this device's own row, and never a device that cannot hear it.
		assertFalse(rowVolumeOffered(playing, myDeviceId = "desk", hubSetsRowVolumes = true))
		assertFalse(rowVolumeOffered(device(id = "desk", isActive = true, online = false), myDeviceId = "phone", hubSetsRowVolumes = true))
	}
}
