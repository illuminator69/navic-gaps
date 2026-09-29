package paige.navic.ui.components.common

import androidx.compose.runtime.Composable
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.lbbot_fail_format
import navic.composeapp.generated.resources.lbbot_fail_musicbrainz
import navic.composeapp.generated.resources.lbbot_fail_no_source
import navic.composeapp.generated.resources.lbbot_fail_placement
import navic.composeapp.generated.resources.lbbot_fail_transfer
import navic.composeapp.generated.resources.lbbot_fill_attempts
import navic.composeapp.generated.resources.lbbot_fill_bytes
import navic.composeapp.generated.resources.lbbot_fill_cancelled
import navic.composeapp.generated.resources.lbbot_fill_downloading
import navic.composeapp.generated.resources.lbbot_fill_downloading_plain
import navic.composeapp.generated.resources.lbbot_fill_failed
import navic.composeapp.generated.resources.lbbot_fill_failed_files
import navic.composeapp.generated.resources.lbbot_fill_gave_up
import navic.composeapp.generated.resources.lbbot_fill_needs_match
import navic.composeapp.generated.resources.lbbot_fill_needs_pick
import navic.composeapp.generated.resources.lbbot_fill_needs_pick_hint
import navic.composeapp.generated.resources.lbbot_fill_no_retry
import navic.composeapp.generated.resources.lbbot_fill_placed
import navic.composeapp.generated.resources.lbbot_fill_placed_unindexed
import navic.composeapp.generated.resources.lbbot_fill_placing
import navic.composeapp.generated.resources.lbbot_fill_queued
import navic.composeapp.generated.resources.lbbot_fill_retry_in
import navic.composeapp.generated.resources.lbbot_fill_searching
import navic.composeapp.generated.resources.lbbot_fill_speed
import navic.composeapp.generated.resources.lbbot_fill_unreachable
import navic.composeapp.generated.resources.lbbot_fill_verified
import navic.composeapp.generated.resources.lbbot_fill_wishlist_hint
import navic.composeapp.generated.resources.lbbot_track_added
import navic.composeapp.generated.resources.lbbot_track_downloaded
import navic.composeapp.generated.resources.lbbot_track_failed
import navic.composeapp.generated.resources.lbbot_track_missing
import navic.composeapp.generated.resources.lbbot_track_picked
import navic.composeapp.generated.resources.lbbot_track_queued
import navic.composeapp.generated.resources.lbbot_track_skipped
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import paige.navic.domain.manager.LbFillEntry

/**
 * The acquisition vocabulary, PROTOCOL.md §15.2.
 *
 * One state renders the same words and offers the same buttons everywhere a fill
 * is shown — the Download Center, the missing-album sheet, the discography tile —
 * and in Feishin, which implements the same table in `fill-vocabulary.ts`. There
 * is no shared build between the two clients, so the table in PROTOCOL is the
 * contract and this file is Navic's copy of it. Change one, change all three.
 *
 * Pure: the same row and the same clock give the same answer, which is what makes
 * the table checkable against the protocol by reading rather than by driving the UI.
 */

sealed interface FillText {
	data class Res(val res: StringResource, val args: List<Any> = emptyList()) : FillText
	data class Raw(val text: String) : FillText
}

@Composable
fun FillText.resolve(): String = when (this) {
	is FillText.Raw -> text
	is FillText.Res -> if (args.isEmpty()) stringResource(res) else stringResource(res, *args.toTypedArray())
}

enum class FillButton { CANCEL, ALLOW_MP3, RETRY, TRY_ANOTHER, WISHLIST, OPEN_ALBUM, DISMISS }

enum class FillProgressKind { NONE, INDETERMINATE, DETERMINATE }

data class FillPresentation(
	val headline: FillText,
	val sublines: List<FillText>,
	/** One sentence saying why no action is offered, or what the wishlist is for. */
	val explain: FillText?,
	val progress: FillProgressKind,
	val percent: Int,
	val buttons: List<FillButton>
)

/** How many consecutive unanswered polls before a row says it can't reach lb-bot. */
const val UNREACHABLE_AFTER_ERRORS = 2

fun formatBytes(bytes: Long): String {
	if (bytes <= 0L) return "0 MB"
	val mb = bytes / (1024.0 * 1024.0)
	if (mb >= 1024) return "${((mb / 1024) * 10).toLong() / 10.0} GB"
	return if (mb < 10) "${(mb * 10).toLong() / 10.0} MB" else "${mb.toLong()} MB"
}

private fun seconds(ms: Long): Long = (ms.coerceAtLeast(0L) + 500) / 1000

/** The failure in a few words, from lb-bot's `failureKind`. Its own sentence stays
 *  below, verbatim — it carries the evidence. */
fun failureHeadline(kind: String): StringResource = when (kind) {
	"no_source" -> Res.string.lbbot_fail_no_source
	"format_rejected" -> Res.string.lbbot_fail_format
	"transfer_failed" -> Res.string.lbbot_fail_transfer
	"placement_failed" -> Res.string.lbbot_fail_placement
	"mb_unavailable" -> Res.string.lbbot_fail_musicbrainz
	else -> Res.string.lbbot_fill_failed
}

/** Headline for a fill that is still running, by lb-bot state. */
fun runningHeadline(state: String, done: Int, total: Int, verifyGaveUp: Boolean = false): FillText = when (state) {
	"downloading" -> if (total > 0) FillText.Res(Res.string.lbbot_fill_downloading, listOf(done, total))
		else FillText.Res(Res.string.lbbot_fill_downloading_plain)
	"queued" -> FillText.Res(Res.string.lbbot_fill_queued)
	"placing" -> FillText.Res(Res.string.lbbot_fill_placing)
	"placed" -> FillText.Res(if (verifyGaveUp) Res.string.lbbot_fill_placed_unindexed else Res.string.lbbot_fill_placed)
	"verified" -> FillText.Res(Res.string.lbbot_fill_verified)
	"cancelled" -> FillText.Res(Res.string.lbbot_fill_cancelled)
	"needs_match" -> FillText.Res(Res.string.lbbot_fill_needs_match)
	// `searching`, and the first seconds before lb-bot has written a row.
	else -> FillText.Res(Res.string.lbbot_fill_searching)
}

fun describeFill(entry: LbFillEntry, now: Long, wishlistAvailable: Boolean): FillPresentation {
	val buttons = mutableListOf<FillButton>()
	val sublines = mutableListOf<FillText>()
	var explain: FillText? = null
	var progress = FillProgressKind.NONE
	var percent = 0

	if (!entry.settled) {
		val headline = runningHeadline(entry.state, entry.done, entry.total)
		when (entry.state) {
			"downloading" -> {
				val parts = mutableListOf<String>()
				if (entry.bytesTotal > 0L) parts += "${formatBytes(entry.bytesDone)} / ${formatBytes(entry.bytesTotal)}"
				if (entry.speedBps > 0L) parts += "${(entry.speedBps / (1024.0 * 1024.0) * 10).toLong() / 10.0} MB/s"
				if (entry.lastSource.isNotBlank()) parts += "@${entry.lastSource}"
				if (parts.isNotEmpty()) sublines += FillText.Raw(parts.joinToString(" · "))
				if (entry.failedFiles > 0) sublines += FillText.Res(Res.string.lbbot_fill_failed_files, listOf(entry.failedFiles))
				progress = if (entry.bytesTotal > 0L || entry.total > 0) FillProgressKind.DETERMINATE else FillProgressKind.INDETERMINATE
				percent = entry.percent
			}
			"queued" -> {
				if (entry.lastSource.isNotBlank()) sublines += FillText.Raw("@${entry.lastSource}")
				progress = FillProgressKind.INDETERMINATE
			}
			"placing", "placed" -> { progress = FillProgressKind.DETERMINATE; percent = 100 }
			else -> {
				if (entry.reason.isNotBlank()) sublines += FillText.Raw(entry.reason)
				progress = FillProgressKind.INDETERMINATE
			}
		}
		if (entry.lastErrorTicks >= UNREACHABLE_AFTER_ERRORS && entry.lastCheckedAt > 0L) {
			sublines += FillText.Res(Res.string.lbbot_fill_unreachable, listOf(seconds(now - entry.lastCheckedAt)))
		}
		if (entry.canCancel) buttons += FillButton.CANCEL
		if (entry.state == "placed" && entry.rgid.isNotBlank()) buttons += FillButton.OPEN_ALBUM
		return FillPresentation(headline, sublines, null, progress, percent, buttons)
	}

	val headline: FillText = when (entry.outcome) {
		"cancelled" -> {
			if (entry.rgid.isNotBlank()) buttons += FillButton.OPEN_ALBUM
			FillText.Res(Res.string.lbbot_fill_cancelled)
		}
		"done" -> {
			if (entry.rgid.isNotBlank()) buttons += FillButton.OPEN_ALBUM
			FillText.Res(if (entry.verifyGaveUp) Res.string.lbbot_fill_placed_unindexed else Res.string.lbbot_fill_verified)
		}
		"gave_up" -> {
			if (entry.reason.isNotBlank()) sublines += FillText.Raw(entry.reason)
			if (entry.rgid.isNotBlank()) buttons += FillButton.OPEN_ALBUM
			FillText.Res(Res.string.lbbot_fill_gave_up)
		}
		"needs_pick" -> {
			explain = FillText.Res(Res.string.lbbot_fill_needs_pick_hint)
			FillText.Res(Res.string.lbbot_fill_needs_pick)
		}
		else -> {
			if (entry.state == "needs_match") {
				if (entry.reason.isNotBlank()) sublines += FillText.Raw(entry.reason)
				FillText.Res(Res.string.lbbot_fill_needs_match)
			} else {
				if (entry.reason.isNotBlank()) sublines += FillText.Raw(entry.reason)
				if (entry.attempts > 1) sublines += FillText.Res(Res.string.lbbot_fill_attempts, listOf(entry.attempts))
				if (entry.retryAt > now) {
					sublines += FillText.Res(Res.string.lbbot_fill_retry_in, listOf(seconds(entry.retryAt - now)))
					if (entry.canCancel) buttons += FillButton.CANCEL
				}
				val wishlistable = wishlistAvailable && entry.failureKind == "no_source" && entry.rgid.isNotBlank()
				if (entry.mp3WouldHelp) buttons += FillButton.ALLOW_MP3
				if (entry.canRetry) buttons += FillButton.RETRY
				if (entry.canTryAnotherSource) buttons += FillButton.TRY_ANOTHER
				if (wishlistable) {
					buttons += FillButton.WISHLIST
					explain = FillText.Res(Res.string.lbbot_fill_wishlist_hint)
				} else if (!entry.canRetry && !entry.mp3WouldHelp) {
					explain = FillText.Res(Res.string.lbbot_fill_no_retry)
				}
				FillText.Res(failureHeadline(entry.failureKind))
			}
		}
	}
	buttons += FillButton.DISMISS
	return FillPresentation(headline, sublines, explain, progress, percent, buttons)
}

/**
 * Q-037: a gap track's `state` (lb-bot's `_TRACK_STATE_BY_DECISION`, PROTOCOL §15.2) as words —
 * the sheet printed the raw lowercase token beside the one it did translate ("Cancelled").
 * Mirrors Feishin's `TRACK_LABEL`. Null for a token this build doesn't know.
 */
fun gapTrackStateLabel(state: String): StringResource? = when (state) {
	"missing" -> Res.string.lbbot_track_missing
	"picked" -> Res.string.lbbot_track_picked
	"queued" -> Res.string.lbbot_track_queued
	"downloading" -> Res.string.lbbot_fill_downloading_plain
	"downloaded" -> Res.string.lbbot_track_downloaded
	"failed" -> Res.string.lbbot_track_failed
	"cancelled" -> Res.string.lbbot_fill_cancelled
	"skipped" -> Res.string.lbbot_track_skipped
	"done" -> Res.string.lbbot_track_added
	else -> null
}
