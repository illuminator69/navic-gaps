package paige.navic.ui.components.common

/*
 * navi-connect P4: one pill for one state, everywhere a release, a request or a gap track says where
 * it stands (the artist page's rows, the request box, Fill gaps). The words are the acquisition
 * vocabulary's (`FillVocabulary.kt`, PROTOCOL §15.2); this file adds only how loud each one is, as
 * the Vocab board draws it. Pure: no Compose, so the table is checked by `StatePillsTest`.
 */

/**
 * How a pill reads. RUN is something happening (it carries a spinner), OK and GOOD are in the
 * library (GOOD once lb-bot has checked), WARN wants the user, BAD failed, MUTE is over or absent,
 * NEUTRAL is a state with no weight either way.
 */
enum class PillTone { NEUTRAL, RUN, OK, GOOD, WARN, BAD, MUTE }

/** A label and its tone. The label is always drawn, so a state is never colour alone. */
data class Pill(val label: FillText, val tone: PillTone)

/** A fill's `state` (or a settled row's `outcome`), PROTOCOL §15.2, as a tone. */
fun fillPillTone(state: String): PillTone = when (state) {
	"searching", "queued", "downloading", "placing" -> PillTone.RUN
	"placed" -> PillTone.OK
	"verified" -> PillTone.GOOD
	"failed" -> PillTone.BAD
	"cancelled", "gave_up" -> PillTone.MUTE
	"needs_pick", "needs_match" -> PillTone.WARN
	else -> PillTone.NEUTRAL
}

/** A gap track's `state` (`gapTrackStateLabel`'s tokens) as a tone. */
fun gapTrackTone(state: String): PillTone = when (state) {
	"missing" -> PillTone.MUTE
	"picked", "queued" -> PillTone.NEUTRAL
	"downloading" -> PillTone.RUN
	"downloaded" -> PillTone.GOOD
	"failed" -> PillTone.BAD
	"cancelled", "skipped" -> PillTone.MUTE
	"done" -> PillTone.OK
	else -> PillTone.NEUTRAL
}
