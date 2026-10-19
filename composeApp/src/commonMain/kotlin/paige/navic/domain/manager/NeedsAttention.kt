package paige.navic.domain.manager

/**
 * navi-connect P1: how many of this device's own lb-bot requests need a person, for the dot on the
 * header avatar and the badge on the menu's "Needs attention" row.
 *
 * A settled fill whose outcome is a failure, or one the gap picker is waiting on a choice for. A
 * running fill is not an alarm, a cancel is the user's own doing, a "gave up" is lb-bot forgetting a
 * row, and a done one needs nothing. A dismissed row is gone from the ledger, so it is not counted
 * either.
 *
 * It reads only the ledger, which a failed poll leaves alone (CLAUDE.md §5: a failed poll is not an
 * answer), so the dot does not flicker with lb-bot's reachability. The hub's wider count (every
 * review lb-bot is holding) comes with P6.
 */
fun needsAttentionCount(ledger: List<LbFillEntry>): Int = ledger.count {
	it.settled && (it.outcome == LbBotManager.OUTCOME_FAILED || it.outcome == LbBotManager.OUTCOME_NEEDS_PICK)
}
