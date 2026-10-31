package paige.navic.ui.screens.settings

import kotlin.test.Test
import kotlin.test.assertEquals

/** Worbus settings N4: units are written the way people read them. */
class SettingsFormatTest {

	@Test
	fun gainsAreInDecibelsWithACapitalB() {
		assertEquals("+1.5 dB", decibelsLabel(1.5f))
		assertEquals("-3.0 dB", decibelsLabel(-3f))
		assertEquals("0.0 dB", decibelsLabel(0f))
		assertEquals("+0.1 dB", decibelsLabel(0.14f))
	}

	@Test
	fun equaliserLimitsAreShownInDecibelsNotMillibels() {
		assertEquals("15 dB", millibelsLabel(1500f))
		assertEquals("-15 dB", millibelsLabel(-1500f))
		assertEquals("7.5 dB", millibelsLabel(750f))
	}

	@Test
	fun theScrobbleThresholdSnapsToTenPercent() {
		assertEquals(0.5f, snapToTenPercent(0.5f))
		assertEquals(0.6f, snapToTenPercent(0.56f))
		assertEquals(0.3f, snapToTenPercent(0.33f))
		assertEquals(1f, snapToTenPercent(0.97f))
		assertEquals(0f, snapToTenPercent(0.04f))
	}

	@Test
	fun theScrobbleLabelShowsTheStoredValueNotItsSnappedNeighbour() {
		// 0.37 counts a play at 37 %, so it must not read 40 % until the person moves the slider.
		assertEquals(37, scrobblePercentShown(0.37f))
		assertEquals(50, scrobblePercentShown(0.5f))
		assertEquals(0, scrobblePercentShown(-0.2f))
		assertEquals(100, scrobblePercentShown(1.4f))
	}
}
