package paige.navic.domain.manager.cast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/*
 * What a cast bridge tells the hub about its speaker besides the name: the `kind` read from
 * the TXT record's `ca` capability bits, and the receiver's volume from RECEIVER_STATUS.
 */
class CastTraitsTest {
	@Test
	fun aGroupIsAGroupWhateverElseItCanDo() {
		// Audio out and multizone group: a speaker group.
		assertEquals("group", castKind("36"))
		assertEquals("group", castKind("32"))
		// The group bit wins over a picture too.
		assertEquals("group", castKind("37"))
	}

	@Test
	fun aPictureMakesATvAndSoundAloneASpeaker() {
		// Video out, audio out and more: a Chromecast, a TV with Cast built in.
		assertEquals("tv", castKind("5"))
		assertEquals("tv", castKind("201221"))
		assertEquals("tv", castKind("1"))
		// Audio out with no picture: a Nest speaker.
		assertEquals("speaker", castKind("4"))
		assertEquals("speaker", castKind("199172"))
	}

	@Test
	fun aRecordThatSaysNothingUsableGivesNoKind() {
		assertNull(castKind(null))
		assertNull(castKind(""))
		assertNull(castKind("speaker"))
		// Neither a picture, nor sound, nor a group.
		assertNull(castKind("0"))
		assertNull(castKind("2"))
		// Padded, as a TXT value can be.
		assertEquals("speaker", castKind(" 4 "))
	}

	@Test
	fun theReceiversLevelIsReportedOutOfAHundredAndMutedIsNought() {
		assertEquals(35, receiverVolumeLevel(0.35, muted = false))
		assertEquals(100, receiverVolumeLevel(1.0, muted = null))
		assertEquals(0, receiverVolumeLevel(0.0, muted = false))
		// 0.2899999… as a speaker sends it.
		assertEquals(29, receiverVolumeLevel(0.28999999165534973, muted = false))
		assertEquals(0, receiverVolumeLevel(0.6, muted = true))
		// Muted with no level is still silent; no level and not muted says nothing.
		assertEquals(0, receiverVolumeLevel(null, muted = true))
		assertNull(receiverVolumeLevel(null, muted = false))
		assertNull(receiverVolumeLevel(null, muted = null))
		// Never outside the hub's range.
		assertEquals(100, receiverVolumeLevel(1.4, muted = false))
	}
}
