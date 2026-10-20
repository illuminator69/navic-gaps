package paige.navic.ui.screens.artist

import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.info_pending_sync
import navic.composeapp.generated.resources.lbbot_fill_gave_up
import navic.composeapp.generated.resources.lbbot_fill_needs_match
import navic.composeapp.generated.resources.lbbot_fill_needs_pick
import navic.composeapp.generated.resources.lbbot_fill_placed_unindexed
import navic.composeapp.generated.resources.lbbot_fill_verified
import navic.composeapp.generated.resources.release_missing
import navic.composeapp.generated.resources.release_owned
import navic.composeapp.generated.resources.release_partly_owned
import paige.navic.domain.manager.LbFillEntry
import paige.navic.ui.components.common.FillText
import paige.navic.ui.components.common.Pill
import paige.navic.ui.components.common.PillTone
import paige.navic.ui.components.common.failureHeadline
import paige.navic.ui.components.common.fillPillTone
import paige.navic.ui.components.common.runningHeadline
import paige.navic.ui.screens.artist.viewmodels.DiscographyEntry

/*
 * navi-connect P4: what one discography row says about itself (`ReleaseRows`). Derived from the
 * index row, Room and this device's fill ledger, never from a count the page made:
 *
 * - `Owned` iff Room holds the album (and the index has no gap for it);
 * - `Partly owned` iff the index row is `incomplete` with a group id;
 * - `Added, syncing` iff the index says present and Room has none yet;
 * - `Missing` otherwise.
 *
 * A fill that is running is the news and beats all four. A settled one says what became of it,
 * except where it adds nothing: a `done` fill on an album Room holds, and a `cancelled` one (the
 * release is simply as it was). The caller resolves the ledger row: by `rgid`, or for a partly
 * owned row by its gap's group id, which is a gap fill's key.
 */
fun releaseState(entry: DiscographyEntry, fill: LbFillEntry?): Pill {
	val base = baseState(entry)
	if (fill == null) return base
	if (!fill.settled) {
		return Pill(runningHeadline(fill.state, fill.done, fill.total, fill.verifyGaveUp), fillPillTone(fill.state))
	}
	if (base.tone == PillTone.OK) return base
	return when (fill.outcome) {
		"cancelled" -> base
		// The index flips to present on placement and Room follows on its next sync, so a done
		// fill reads as the base state unless the index has not caught up.
		"done" -> if (base.tone != PillTone.MUTE) base else Pill(
			FillText.Res(if (fill.verifyGaveUp) Res.string.lbbot_fill_placed_unindexed else Res.string.lbbot_fill_verified),
			if (fill.verifyGaveUp) PillTone.OK else PillTone.GOOD
		)
		"gave_up" -> Pill(FillText.Res(Res.string.lbbot_fill_gave_up), PillTone.MUTE)
		"needs_pick" -> Pill(FillText.Res(Res.string.lbbot_fill_needs_pick), PillTone.WARN)
		else -> if (fill.state == "needs_match") {
			Pill(FillText.Res(Res.string.lbbot_fill_needs_match), PillTone.WARN)
		} else {
			Pill(FillText.Res(failureHeadline(fill.failureKind)), PillTone.BAD)
		}
	}
}

private fun baseState(entry: DiscographyEntry): Pill = when {
	entry.gapGroupId != null -> Pill(FillText.Res(Res.string.release_partly_owned), PillTone.WARN)
	entry.owned -> Pill(FillText.Res(Res.string.release_owned), PillTone.OK)
	entry.pendingSync -> Pill(FillText.Res(Res.string.info_pending_sync), PillTone.RUN)
	else -> Pill(FillText.Res(Res.string.release_missing), PillTone.MUTE)
}
