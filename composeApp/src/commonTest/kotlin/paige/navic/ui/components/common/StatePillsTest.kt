package paige.navic.ui.components.common

import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * P4: how loud each state is, from the Vocab board. A fill's states and outcomes (PROTOCOL §15.2)
 * and a gap track's states each have one tone; a token this build does not know is NEUTRAL, never
 * a colour that claims something.
 */
class StatePillsTest {
	@Test
	fun fillStatesHaveTheBoardsTones() {
		listOf("searching", "queued", "downloading", "placing").forEach {
			assertEquals(PillTone.RUN, fillPillTone(it), it)
		}
		assertEquals(PillTone.OK, fillPillTone("placed"))
		assertEquals(PillTone.GOOD, fillPillTone("verified"))
		assertEquals(PillTone.BAD, fillPillTone("failed"))
		assertEquals(PillTone.MUTE, fillPillTone("cancelled"))
		assertEquals(PillTone.MUTE, fillPillTone("gave_up"))
		assertEquals(PillTone.WARN, fillPillTone("needs_pick"))
		assertEquals(PillTone.WARN, fillPillTone("needs_match"))
	}

	@Test
	fun anUnknownFillStateIsNeutral() {
		assertEquals(PillTone.NEUTRAL, fillPillTone("unknown"))
		assertEquals(PillTone.NEUTRAL, fillPillTone("something_new"))
	}

	@Test
	fun gapTrackStatesHaveTheBoardsTones() {
		assertEquals(PillTone.MUTE, gapTrackTone("missing"))
		assertEquals(PillTone.NEUTRAL, gapTrackTone("picked"))
		assertEquals(PillTone.NEUTRAL, gapTrackTone("queued"))
		assertEquals(PillTone.RUN, gapTrackTone("downloading"))
		assertEquals(PillTone.GOOD, gapTrackTone("downloaded"))
		assertEquals(PillTone.BAD, gapTrackTone("failed"))
		assertEquals(PillTone.MUTE, gapTrackTone("cancelled"))
		assertEquals(PillTone.MUTE, gapTrackTone("skipped"))
		assertEquals(PillTone.OK, gapTrackTone("done"))
		assertEquals(PillTone.NEUTRAL, gapTrackTone("something_new"))
	}
}
