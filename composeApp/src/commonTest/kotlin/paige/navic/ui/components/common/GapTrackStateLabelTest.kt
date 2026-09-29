package paige.navic.ui.components.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/*
 * Q-037: the gap sheet printed lb-bot's raw lowercase track-state tokens. Every token lb-bot can
 * send (`_TRACK_STATE_BY_DECISION`'s values, plus `missing`) must have words.
 */
class GapTrackStateLabelTest {
	private val lbBotTokens = listOf(
		"missing", "picked", "queued", "downloading", "downloaded",
		"failed", "cancelled", "skipped", "done"
	)

	@Test
	fun everyTokenLbBotSendsHasALabel() {
		lbBotTokens.forEach { assertNotNull(gapTrackStateLabel(it), it) }
	}

	@Test
	fun noTwoTokensShareALabel() {
		val labels = lbBotTokens.map { gapTrackStateLabel(it)!!.key }
		assertEquals(labels.size, labels.toSet().size)
	}

	@Test
	fun anUnknownTokenHasNone() {
		assertNull(gapTrackStateLabel("something_new"))
	}
}
