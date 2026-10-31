package paige.navic.ui.screens.settings

import paige.navic.di.PlatformType
import paige.navic.domain.models.settings.StreamingQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Worbus settings N2: Wi-Fi and mobile data each get one choice (a preset or Custom...), over keys that
 * were a switch and four text fields. The "advanced" flag is ONE key the player reads for both
 * networks, so a choice made for one network must not change what the other streams at.
 */
class QualityChoiceMappingTest {
	private val android = PlatformType.Android
	private val wifi = StreamNetwork.Wifi
	private val cellular = StreamNetwork.Cellular
	private val preset = StreamChoice::Preset

	@Test
	fun aPresetWithTheFlagOffWritesOnlyThatPreset() {
		val after = StreamingKeys().choose(wifi, preset(StreamingQuality.High), android)
		assertEquals(StreamingKeys(presetWifi = StreamingQuality.High), after)
		assertEquals(preset(StreamingQuality.High), after.choice(wifi, android))
		assertEquals(preset(StreamingQuality.Lossless), after.choice(cellular, android))
	}

	@Test
	fun customSetsTheFlagAndTheNetworksOwnKeys() {
		val after = StreamingKeys().choose(wifi, StreamChoice.Custom(128, "opus"), android)
		assertTrue(after.advanced)
		assertEquals(128, after.bitrateWifi)
		assertEquals("opus", after.formatWifi)
		// 128 kbps Opus is the Medium preset's own request: it reads back as that preset.
		assertEquals(preset(StreamingQuality.Medium), after.choice(wifi, android))
	}

	@Test
	fun aCustomChoiceThatIsNoPresetReadsBackAsCustom() {
		val after = StreamingKeys().choose(wifi, StreamChoice.Custom(320, "mp3"), android)
		assertEquals(StreamChoice.Custom(320, "mp3"), after.choice(wifi, android))
	}

	@Test
	fun turningTheFlagOnKeepsWhatTheOtherNetworkStreamsAt() {
		// Mobile data was Low (80 kbps Opus). Wi-Fi going Custom must not send mobile data to Original.
		val before = StreamingKeys(presetCellular = StreamingQuality.Low)
		val after = before.choose(wifi, StreamChoice.Custom(320, "mp3"), android)
		assertTrue(after.advanced)
		assertEquals(80, after.bitrateCellular)
		assertEquals("opus", after.formatCellular)
		assertEquals(preset(StreamingQuality.Low), after.choice(cellular, android))
		assertEquals(StreamChoice.Custom(320, "mp3"), after.choice(wifi, android))
	}

	@Test
	fun aPresetForOneNetworkLeavesTheOtherCustomNetworkAlone() {
		val custom = StreamingKeys().choose(wifi, StreamChoice.Custom(320, "mp3"), android)
		val after = custom.choose(cellular, preset(StreamingQuality.Medium), android)
		// The flag stays on: Wi-Fi is still custom. Mobile data is Medium's own request.
		assertTrue(after.advanced)
		assertEquals(StreamChoice.Custom(320, "mp3"), after.choice(wifi, android))
		assertEquals(preset(StreamingQuality.Medium), after.choice(cellular, android))
		assertEquals(128, after.bitrateCellular)
		assertEquals("opus", after.formatCellular)
	}

	@Test
	fun theFlagClearsOnceNeitherNetworkNeedsIt() {
		val custom = StreamingKeys().choose(wifi, StreamChoice.Custom(320, "mp3"), android)
		val after = custom.choose(wifi, preset(StreamingQuality.High), android)
		assertFalse(after.advanced)
		assertEquals(StreamingQuality.High, after.presetWifi)
		assertEquals(StreamingQuality.Lossless, after.presetCellular)
		assertEquals(preset(StreamingQuality.High), after.choice(wifi, android))
		assertEquals(preset(StreamingQuality.Lossless), after.choice(cellular, android))
	}

	@Test
	fun aStoredFormatThatIsNotOfferedShowsAsCustomAndIsLeftAlone() {
		val stored = StreamingKeys(advanced = true, bitrateWifi = 0, formatWifi = "ogg", bitrateCellular = 0, formatCellular = "")
		assertEquals(StreamChoice.Custom(0, "ogg"), stored.choice(wifi, android))
		assertNull(StreamFormat.of("ogg"))
		// Setting the other network does not touch it.
		val after = stored.choose(cellular, preset(StreamingQuality.Low), android)
		assertEquals("ogg", after.formatWifi)
		assertEquals(0, after.bitrateWifi)
		assertEquals(StreamChoice.Custom(0, "ogg"), after.choice(wifi, android))
	}

	@Test
	fun aValueTypedWithAnotherCaseStillReadsAsTheFormat() {
		val stored = StreamingKeys(advanced = true, bitrateWifi = 128, formatWifi = "Opus")
		assertEquals(preset(StreamingQuality.Medium), stored.choice(wifi, android))
		assertEquals(StreamFormat.Opus, StreamFormat.of("Opus"))
	}

	@Test
	fun aPresetDoesNotRewriteTheOtherNetworksStoredCaseOrSpacing() {
		// Mobile data holds "Opus" (Medium's request, typed with another case) and " aac " for Wi-Fi.
		for ((stored, quality) in listOf("Opus" to StreamingQuality.Medium, " OPUS " to StreamingQuality.Medium)) {
			val before = StreamingKeys(advanced = true, bitrateWifi = 320, formatWifi = "mp3", bitrateCellular = 128, formatCellular = stored)
			assertEquals(preset(quality), before.choice(cellular, android))
			val after = before.choose(wifi, preset(StreamingQuality.High), android)
			// The flag stays on, so mobile data keeps sending exactly what is stored.
			assertTrue(after.advanced, "the flag was cleared for \"$stored\"")
			assertEquals(stored, after.formatCellular)
			assertEquals(128, after.bitrateCellular)
			assertEquals(preset(StreamingQuality.High), after.choice(wifi, android))
			assertEquals(preset(quality), after.choice(cellular, android))
		}
	}

	@Test
	fun theFlagStillClearsWhenTheOtherNetworkIsStoredExactly() {
		val before = StreamingKeys(advanced = true, bitrateWifi = 320, formatWifi = "mp3", bitrateCellular = 128, formatCellular = "opus")
		val after = before.choose(wifi, preset(StreamingQuality.High), android)
		assertFalse(after.advanced)
		assertEquals(StreamingQuality.High, after.presetWifi)
		assertEquals(StreamingQuality.Medium, after.presetCellular)
	}

	@Test
	fun theFlagOffIgnoresStoredCustomValues() {
		val stored = StreamingKeys(presetWifi = StreamingQuality.High, bitrateWifi = 320, formatWifi = "mp3")
		assertEquals(preset(StreamingQuality.High), stored.choice(wifi, android))
	}

	@Test
	fun iosPresetsAreItsOwnRequests() {
		val ios = PlatformType.IOS
		val after = StreamingKeys().choose(wifi, StreamChoice.Custom(160, "aac"), ios)
		assertEquals(preset(StreamingQuality.Medium), after.choice(wifi, ios))
		assertTrue(StreamFormat.Opus !in streamFormatsFor(ios))
		assertTrue(StreamFormat.Opus in streamFormatsFor(android))
	}
}
