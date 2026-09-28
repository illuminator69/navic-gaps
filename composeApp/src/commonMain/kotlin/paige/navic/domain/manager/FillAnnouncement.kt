package paige.navic.domain.manager

import io.ktor.http.encodeURLParameter

/*
 * B-026 (Navic half): what to say about a fill that just settled, and where its
 * "Open in lb-bot" goes. Read by the snackbar (App.kt) and the system notification
 * (androidApp's FillNotifier), and kept apart from both so the rule is host-tested.
 *
 * Mirrors Feishin's `announcementKindFor` / `announcementLinkPathFor`
 * (fill-announce-logic.ts) so the two clients say the same thing. A settled `failed`
 * does not always mean nothing arrived. Two roads reach it with the files already
 * sitting in lb-bot, waiting to be filed by hand (PROTOCOL §15.2's needs_match row,
 * rulings R10/R15/R17):
 *   - a gap whose placement stalled — applyGapSummary writes it as the ledger state
 *     `needs_match`;
 *   - an album fill lb-bot itself reports as `needs_match`.
 * Both read "Downloaded X — needs sorting out in lb-bot", not "Couldn't get X": the
 * second sends the user looking for a retry that would only refetch files already on
 * disk.
 */

/** Which settled-row announcement to make. */
enum class FillAnnouncement { DONE, NEEDS_SORTING, COULDNT_GET }

/**
 * `null` for every outcome not worth interrupting for: `needs_pick` is the picker waiting
 * on the user and `cancelled` is something they just did — both announce themselves — and
 * `gave_up` means we stopped looking, not that anything happened.
 */
fun fillAnnouncementFor(event: LbFillEvent): FillAnnouncement? = when {
	event.outcome == LbBotManager.OUTCOME_DONE -> FillAnnouncement.DONE
	event.outcome != LbBotManager.OUTCOME_FAILED -> null
	event.state == "needs_match" -> FillAnnouncement.NEEDS_SORTING
	else -> FillAnnouncement.COULDNT_GET
}

/**
 * The path, relative to lb-bot's `webUrl`, that "Open in lb-bot" should open for a
 * [FillAnnouncement.NEEDS_SORTING] row; `""` when no link is possible.
 *
 * A gap's key IS the review group lb-bot's Fill-gaps page is keyed on, so it gets that
 * album's page on the gaps route. An album fill does not: its key is a release-group
 * MBID, and the id lb-bot hands back at `needs_match` is an import-record id (`rec…` /
 * `ag…`), not a review group — the gaps route answers "Group not found" for it. So it
 * goes to the placement queue (the downloads route), which is never row-specific but is
 * always the right screen.
 */
fun fillAnnouncementLinkPath(event: LbFillEvent): String =
	if (event.isGap) {
		if (event.key.isBlank()) "" else "#/gaps/" + event.key.encodeURLParameter()
	} else {
		"#/downloads"
	}
