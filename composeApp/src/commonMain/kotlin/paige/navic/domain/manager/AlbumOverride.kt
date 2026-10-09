package paige.navic.domain.manager

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/*
 * "Fix album match" (I-022): the wire shapes and the rules, kept free of the network, Room and
 * Compose so they can be run in a test (`AlbumOverrideTest`, `AlbumOverridePollTest`,
 * `AlbumJobTest`). [LbBotManager] makes the calls and follows a job to its end, and
 * `AlbumMatchSheet` draws what these functions decide.
 *
 * For an album the library owns, the user picks which MusicBrainz release it is. lb-bot retags
 * the album's files to that release — release id, disc and track numbers, titles, date; tags
 * only, never a rename or a move — keeps the previous tags so the change can be undone, and can
 * save a chosen Cover Art Archive image as the album's cover. The state is lb-bot's and is
 * shared by every client; nothing about it is stored on the device.
 *
 * Feishin's `album-override.ts` is this file's twin. There is no shared build, so the rules
 * below — what ends a poll, what a refusal means, what is counted — are mirrored by hand.
 */

// ----- wire shapes --------------------------------------------------------------------------
// Field names mirror the wire verbatim (lb-bot answers camelCase), so each can be checked
// against the contract by name. Every field is defaulted: an lb-bot that sends less decodes.

/**
 * `GET /lb/album/override`: an owned album as its files are tagged now, with the override row
 * whose album it is, if any.
 *
 * [albumId] is the Navidrome album id — as asked, or, when the row was asked for by its id, the
 * album's CURRENT one, "" while Navidrome has not indexed the retagged album yet. [releaseMbid]
 * is "" for an untagged album, and [rgid] is "" when lb-bot cannot tell the release group (the
 * sheet then resolves one itself, see [releaseGroupFor]).
 */
@Serializable
data class LbAlbumState(
	val albumId: String = "",
	val artist: String = "",
	val album: String = "",
	val releaseMbid: String = "",
	val rgid: String = "",
	val tracks: List<LbAlbumTrack> = emptyList(),
	val override: LbAlbumOverrideRow? = null
)

/** One file of the album, as tagged. [duration] is seconds. */
@Serializable
data class LbAlbumTrack(
	val disc: Int = 0,
	val track: Int = 0,
	val title: String = "",
	val duration: Double = 0.0
)

/**
 * "lb-bot rewrote this album's tags to release [releaseMbid] at [setAt]" — or changed its cover
 * alone, in which case the release is the one it already had.
 *
 * [state] is `applying`, `applied`, `failed` or `removing`. [done] / [total] are the files
 * written so far and the files to write; [failures] the files lb-bot could not write, with why.
 * A row can be `applied` WITH failures: a partial result. [albumId] is the album's current
 * Navidrome id, "" while unknown.
 */
@Serializable
data class LbAlbumOverrideRow(
	val id: String = "",
	val state: String = "",
	val releaseMbid: String = "",
	val prevReleaseMbid: String = "",
	val rgid: String = "",
	val artist: String = "",
	val album: String = "",
	val albumId: String = "",
	val coverUrl: String = "",
	val setAt: Double = 0.0,
	val done: Int = 0,
	val total: Int = 0,
	val failures: List<LbAlbumFailure> = emptyList()
)

/** A file lb-bot could not write. It is named by its title: no path ever goes on the wire. */
@Serializable
data class LbAlbumFailure(
	val title: String = "",
	val reason: String = ""
)

/**
 * One Cover Art Archive image. [thumb] and [full] are public URLs loaded straight from the
 * Archive; [full] is what goes back as `cover_url`. [source] is `release` (the chosen release's
 * own front) or `release-group`.
 */
@Serializable
data class LbAlbumCover(
	val thumb: String = "",
	val full: String = "",
	val releaseMbid: String = "",
	val source: String = ""
)

/**
 * `GET /lb/album/covers`. Nullable on purpose: `[]` is an answer — the Archive has none — and a
 * body with no list is not one.
 */
@Serializable
internal data class LbAlbumCovers(val covers: List<LbAlbumCover>? = null)

/**
 * `POST /lb/album/override/preview`: how lb-bot would renumber and retitle the album's files for
 * one release. **It writes nothing.**
 *
 * [matched] / [unmatched] count the files with and without a slot on the release; [missing] are
 * the release's slots no file fills.
 */
@Serializable
data class LbAlbumPreview(
	val albumId: String = "",
	val releaseMbid: String = "",
	val release: LbPreviewRelease = LbPreviewRelease(),
	val files: List<LbPreviewFile> = emptyList(),
	val matched: Int = 0,
	val unmatched: Int = 0,
	val missing: List<LbPreviewSlot> = emptyList()
)

@Serializable
data class LbPreviewRelease(
	val title: String = "",
	val disambiguation: String = "",
	val year: String = "",
	val country: String = "",
	val format: String = "",
	val trackCount: Int = 0,
	val discCount: Int = 0
)

/**
 * One file of the album and the slot it would take. [to] is null for a file with no slot on the
 * release: an apply leaves it untouched. [basis] is how the file was matched (`mbid`, `exact`,
 * `prefix`, `contained`, `fuzzy`, `duration`, "" with no slot); [changed] is false when the file
 * already carries exactly the slot's numbers, title and release id.
 */
@Serializable
data class LbPreviewFile(
	val from: LbPreviewSlot = LbPreviewSlot(),
	val to: LbPreviewSlot? = null,
	val basis: String = "",
	val changed: Boolean = false
)

@Serializable
data class LbPreviewSlot(
	val disc: Int = 0,
	val track: Int = 0,
	val title: String = ""
)

/**
 * What `POST /lb/album/cover` and `POST /lb/album/override/remove` answer: the override row's
 * [id], and for a remove the [state] it is now in (`removing`).
 */
@Serializable
data class LbAlbumWrite(
	val ok: Boolean = false,
	val id: String = "",
	val state: String = ""
)

/** What `POST /lb/album/override` came to, when lb-bot did not refuse it outright. */
sealed interface LbAlbumApply {
	/** lb-bot took it and is writing in the background: follow the row [id]. */
	data class Started(val id: String) : LbAlbumApply

	/**
	 * lb-bot wrote nothing and asks first: only [matched] of the album's [total] files have a
	 * slot on the chosen release (fewer than half). The sheet asks for a confirmation that
	 * names both numbers and sends the apply again with `force`.
	 */
	data class PoorMatch(val matched: Int, val total: Int) : LbAlbumApply
}

const val ALBUM_STATE_APPLYING = "applying"
const val ALBUM_STATE_APPLIED = "applied"
const val ALBUM_STATE_FAILED = "failed"
const val ALBUM_STATE_REMOVING = "removing"

/** lb-bot's `code` for an override id it does not hold: how a poll learns a remove finished. */
const val ALBUM_CODE_NO_OVERRIDE = "no_override"

/** An apply or a remove is already running for the album. */
const val ALBUM_CODE_IN_PROGRESS = "in_progress"

/** Fewer than half of the album's files have a slot on the chosen release. */
const val ALBUM_CODE_POOR_MATCH = "poor_match"

/** What a 200 that is not the body the route promises is said to have failed with. */
const val ALBUM_NOT_AN_ANSWER = "lb-bot's answer could not be read"

// ----- reading an answer --------------------------------------------------------------------
// One function per route: the HTTP status and the body as text in, what the route said out.
// Kept here, not in the manager, so the decoding and every refusal can be run against the
// contract's own bodies.

/** Lenient on purpose: an lb-bot that adds a field, drops one or sends a null still decodes. */
private val albumJson = Json {
	ignoreUnknownKeys = true
	isLenient = true
	coerceInputValues = true
}

/**
 * A refusal's body. [matched] and [total] are nullable so that a `poor_match` that carries no
 * numbers can be told from one whose numbers are zero.
 */
@Serializable
private data class LbAlbumRefusal(
	val error: String = "",
	val code: String = "",
	val retryable: Boolean = false,
	val matched: Int? = null,
	val total: Int? = null
)

private fun albumRefusal(raw: String): LbAlbumRefusal = try {
	albumJson.decodeFromString<LbAlbumRefusal>(raw)
} catch (e: Exception) {
	// An HTML error page, an empty body: a refusal that says nothing.
	LbAlbumRefusal()
}

/**
 * Why an album-override call was refused, from its status and lb-bot's `code` / `error` /
 * `retryable`. The artist override's rules ([overrideWriteError]), with the code kept:
 * - a **bare** 404, and a **405** on a POST (lb-bot's SPA fallback is a GET-only catch-all), is
 *   an lb-bot older than the hub: the entry points hide, and the sheet says so in one sentence;
 * - a 404 **with a code** (`unknown_album`, `no_override`, `unknown_release`) is lb-bot's answer;
 * - a 502 **with `retryable`** is MusicBrainz, Navidrome or the Cover Art Archive not answering
 *   lb-bot — nothing was written, and its sentence says which. The hub's own 502 / 504 (lb-bot
 *   unreachable, or timed out) carry no such flag;
 * - everything else (400, 409 `busy` / `in_progress`) keeps lb-bot's sentence and its code.
 */
private fun albumOverrideError(status: Int, refusal: LbAlbumRefusal): LbBotManager.LbError = when {
	status == 405 || (status == 404 && refusal.code.isBlank()) -> LbBotManager.LbError.RouteUnknown
	(status == 502 || status == 504) && !refusal.retryable -> LbBotManager.LbError.Busy
	else -> LbBotManager.LbError.Rejected(status, refusal.error, refusal.code)
}

private fun albumRefused(status: Int, raw: String): LbBotManager.LbResult.Failed =
	LbBotManager.LbResult.Failed(albumOverrideError(status, albumRefusal(raw)))

/**
 * A 2xx body as [T], or a failure when it is not the body the route promises ([isAnswer]).
 *
 * **Nothing is not an answer.** `{}` decodes — to an untagged album with no tracks, a preview
 * that changes nothing, a write that started nothing — and each of those would be said as fact.
 */
private fun <T : Any> albumBody(
	raw: String,
	serializer: KSerializer<T>,
	notAnAnswer: String = ALBUM_NOT_AN_ANSWER,
	isAnswer: (T) -> Boolean
): LbBotManager.LbResult<T> {
	val body = try {
		albumJson.decodeFromString(serializer, raw)
	} catch (e: Exception) {
		null
	}
	return if (body != null && isAnswer(body)) LbBotManager.LbResult.Ok(body)
	else LbBotManager.LbResult.Failed(LbBotManager.LbError.Rejected(200, notAnAnswer))
}

private fun isSuccess(status: Int): Boolean = status in 200..299

/**
 * `GET /lb/album/override`. [byId]: the row was asked for by its id, as a poll does — the
 * answer is then the row, and the album's id may be "" (Navidrome has not indexed the retagged
 * album yet). Asked by album id, the answer names that album.
 */
internal fun albumStateAnswer(status: Int, raw: String, byId: Boolean): LbBotManager.LbResult<LbAlbumState> {
	if (!isSuccess(status)) return albumRefused(status, raw)
	return albumBody(raw, serializer<LbAlbumState>()) { state ->
		if (byId) !state.override?.id.isNullOrBlank() else state.albumId.isNotBlank()
	}
}

/**
 * `GET /lb/album/covers`: the chosen release's own front first, then the release group's, as
 * lb-bot ordered them. An empty list is the Archive having none.
 *
 * A cover whose [LbAlbumCover.full] is not an http(s) address is dropped: `full` is the one
 * thing that can be sent back as `cover_url`, and the image is loaded from it directly.
 */
internal fun albumCoversAnswer(status: Int, raw: String): LbBotManager.LbResult<List<LbAlbumCover>> {
	if (!isSuccess(status)) return albumRefused(status, raw)
	return when (val body = albumBody(raw, serializer<LbAlbumCovers>()) { it.covers != null }) {
		is LbBotManager.LbResult.Failed -> body
		is LbBotManager.LbResult.Ok -> LbBotManager.LbResult.Ok(
			body.value.covers.orEmpty()
				.filter { isWebUrl(it.full) }
				.map { if (isWebUrl(it.thumb)) it else it.copy(thumb = it.full) }
		)
	}
}

private fun isWebUrl(url: String): Boolean = url.startsWith("https://") || url.startsWith("http://")

/** `POST /lb/album/override/preview`. A preview that names no release previews nothing. */
internal fun albumPreviewAnswer(status: Int, raw: String): LbBotManager.LbResult<LbAlbumPreview> {
	if (!isSuccess(status)) return albumRefused(status, raw)
	return albumBody(raw, serializer<LbAlbumPreview>()) { it.releaseMbid.isNotBlank() }
}

/**
 * `POST /lb/album/override`.
 *
 * A **409 `poor_match`** that carries both numbers is not a failure but lb-bot asking back
 * ([LbAlbumApply.PoorMatch]). Without them there is nothing true to ask, and it is shown as
 * lb-bot wrote it. A 200 that does not say `ok`, or names no row, started nothing.
 */
internal fun albumApplyAnswer(status: Int, raw: String): LbBotManager.LbResult<LbAlbumApply> {
	if (!isSuccess(status)) {
		val refusal = albumRefusal(raw)
		val matched = refusal.matched
		val total = refusal.total
		return if (status == 409 && refusal.code == ALBUM_CODE_POOR_MATCH && matched != null && total != null) {
			LbBotManager.LbResult.Ok(LbAlbumApply.PoorMatch(matched, total))
		} else {
			LbBotManager.LbResult.Failed(albumOverrideError(status, refusal))
		}
	}
	return when (val written = albumWriteAnswer(status, raw)) {
		is LbBotManager.LbResult.Failed -> written
		is LbBotManager.LbResult.Ok -> LbBotManager.LbResult.Ok(LbAlbumApply.Started(written.value.id))
	}
}

/**
 * `POST /lb/album/cover` and `POST /lb/album/override/remove`. As [confirmedWrite]: done only
 * when the answer says `ok` — and names the row, since the row's id is what is followed next.
 */
internal fun albumWriteAnswer(status: Int, raw: String): LbBotManager.LbResult<LbAlbumWrite> {
	if (!isSuccess(status)) return albumRefused(status, raw)
	return albumBody(raw, serializer<LbAlbumWrite>(), WRITE_NOT_CONFIRMED) { it.ok && it.id.isNotBlank() }
}

// ----- a pasted release ---------------------------------------------------------------------

private const val RELEASE_MBID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
private val RELEASE_MBID = Regex(RELEASE_MBID_PATTERN)
private val RELEASE_URL =
	Regex("(?:https?://)?(?:[a-z0-9-]+\\.)?musicbrainz\\.org/release/($RELEASE_MBID_PATTERN)(?:[/?#].*)?")

/**
 * What the picker's paste field accepts: a bare MBID, or a `musicbrainz.org/release/<uuid>` URL.
 * Returns the id in lowercase, or null for anything else. ([parseArtistMbid] is the artist twin.)
 *
 * The path is checked, not just the UUID: a release-GROUP or artist URL carries a well-formed id
 * that names no release, and lb-bot would only find that out by asking MusicBrainz. So is the
 * host: a look-alike is not MusicBrainz.
 */
internal fun parseReleaseMbid(input: String): String? {
	val text = input.trim().lowercase()
	if (RELEASE_MBID.matches(text)) return text
	return RELEASE_URL.matchEntire(text)?.groupValues?.get(1)
}

// ----- the preview, summed ------------------------------------------------------------------

/**
 * A preview's three counts, plus the files it leaves as they are.
 *
 * @property changing files an apply would rewrite: they have a slot and are not already right.
 * @property unchanged files with a slot that already carry exactly its numbers, title and release.
 * @property noSlot files with no slot on the release. An apply leaves them untouched, and
 *   Navidrome will then list them as an album of their own — the sheet says so before applying.
 * @property missing slots of the release no file fills: tracks still missing after the change.
 */
data class AlbumPreviewSummary(
	val changing: Int,
	val unchanged: Int,
	val noSlot: Int,
	val missing: Int
)

/**
 * [AlbumPreviewSummary.changing] and `unchanged` are counted off the file list, which is what
 * the table under the counts shows. A file with no slot is never "changing", whatever its
 * `changed` says. `noSlot` is the larger of the list's count and lb-bot's own `unmatched`: it
 * drives the one warning that must not be missed, so either of them saying a file is left
 * behind is enough.
 */
internal fun previewSummary(preview: LbAlbumPreview): AlbumPreviewSummary {
	val slotted = preview.files.filter { it.to != null }
	val changing = slotted.count { it.changed }
	return AlbumPreviewSummary(
		changing = changing,
		unchanged = slotted.size - changing,
		noSlot = maxOf(preview.files.size - slotted.size, preview.unmatched),
		missing = preview.missing.size
	)
}

/**
 * Whether the from → to table needs the disc beside the track number: the release has more
 * than one medium, or some file or slot is on a disc past the first.
 */
internal fun previewMultiDisc(preview: LbAlbumPreview): Boolean =
	preview.release.discCount > 1 ||
		preview.files.any { it.from.disc > 1 || (it.to?.disc ?: 0) > 1 } ||
		preview.missing.any { it.disc > 1 }

/**
 * A slot's number as the table prints it: `disc-track` on a multi-disc album, the track alone
 * otherwise. Blank for a file with no track number — "0" would read as one — and the track alone
 * where there is no disc number to put beside it.
 */
internal fun slotLabel(slot: LbPreviewSlot, multiDisc: Boolean): String = when {
	slot.track <= 0 -> ""
	multiDisc && slot.disc > 0 -> "${slot.disc}-${slot.track}"
	else -> "${slot.track}"
}

// ----- editions -----------------------------------------------------------------------------

/**
 * One row of the edition picker: a release, described by the variant it belongs to (the title,
 * what tells it apart, how many tracks) and by its own pressing (medium, country, year).
 * [current] marks the release the album's files are tagged with now.
 */
data class AlbumEditionRow(
	val releaseMbid: String,
	val title: String,
	val disambiguation: String,
	val label: String,
	val format: String,
	val country: String,
	val year: String,
	val trackCount: Int,
	val coverUrl: String,
	val current: Boolean
)

/**
 * The picker's rows out of `GET /lb/album/releases`: every edition of every variant, once, in
 * lb-bot's order. A variant with no editions is itself a release and becomes one row. A row
 * with no release id cannot be picked and is dropped.
 */
internal fun albumEditionRows(variants: List<LbVariant>, currentReleaseMbid: String): List<AlbumEditionRow> {
	val seen = mutableSetOf<String>()
	val rows = mutableListOf<AlbumEditionRow>()
	fun add(row: AlbumEditionRow) {
		if (row.releaseMbid.isBlank() || !seen.add(row.releaseMbid)) return
		rows += row.copy(current = currentReleaseMbid.isNotBlank() && row.releaseMbid == currentReleaseMbid)
	}
	for (variant in variants) {
		if (variant.editions.isEmpty()) {
			add(
				AlbumEditionRow(
					releaseMbid = variant.releaseMbid,
					title = variant.title,
					disambiguation = variant.disambiguation,
					label = "",
					format = "",
					country = variant.country,
					year = variant.year,
					trackCount = variant.trackCount,
					coverUrl = variant.coverUrl,
					current = false
				)
			)
			continue
		}
		for (edition in variant.editions) {
			add(
				AlbumEditionRow(
					releaseMbid = edition.releaseMbid,
					title = variant.title,
					disambiguation = variant.disambiguation,
					label = edition.label,
					format = edition.format,
					country = edition.country,
					year = edition.year.ifBlank { variant.year },
					trackCount = variant.trackCount,
					coverUrl = edition.coverUrl.ifBlank { variant.coverUrl },
					current = false
				)
			)
		}
	}
	return rows
}

/**
 * What the album is tagged as now. Only [releaseMbid] is lb-bot's statement about the album;
 * the rest is that release as the edition list describes it, blank (0) where the list does not
 * hold the release — the library's own album title is not the release's, and is not borrowed.
 */
data class AlbumTaggedAs(
	val releaseMbid: String,
	val title: String = "",
	val disambiguation: String = "",
	val year: String = "",
	val country: String = "",
	val format: String = "",
	val trackCount: Int = 0
)

/** Null for an album whose files carry no MusicBrainz release. */
internal fun albumTaggedAs(state: LbAlbumState, rows: List<AlbumEditionRow>): AlbumTaggedAs? {
	if (state.releaseMbid.isBlank()) return null
	val row = rows.firstOrNull { it.releaseMbid == state.releaseMbid }
		?: return AlbumTaggedAs(releaseMbid = state.releaseMbid)
	return AlbumTaggedAs(
		releaseMbid = row.releaseMbid,
		title = row.title,
		disambiguation = row.disambiguation,
		year = row.year,
		country = row.country,
		format = row.format,
		trackCount = row.trackCount
	)
}

// ----- the release group, when lb-bot cannot tell it ----------------------------------------
// `rgid` is "" for an untagged album of an artist lb-bot has not indexed. The editions are then
// found through one MusicBrainz search — the same search, and the same rules, as the Discover
// screen's `resolveBrowseAlbum`: a FIELDED query, and an answer validated against what was
// asked. Declining is a correct outcome: the paste field is still there.

/** "Discovery (Remastered)" -> "Discovery". A title that is only its parenthetical keeps it. */
private fun withoutEditionSuffix(title: String): String =
	title.trim().substringBefore(" (").substringBefore(" [").trim().ifBlank { title.trim() }

/** What Lucene would otherwise read as syntax, dropped. */
private fun lucene(value: String): String = value.replace("\\", "").replace("\"", "").trim()

/**
 * The `q` for `GET /lb/album/lookup`, which reaches MusicBrainz verbatim. Fielded, because a
 * free-text "artist title" scores a release group whose TITLE contains both words above the one
 * where the artist is a separate field. "" when there is no title to ask about.
 */
internal fun albumLookupQuery(artist: String, title: String): String {
	val wanted = lucene(withoutEditionSuffix(title))
	if (wanted.isBlank()) return ""
	val by = lucene(artist)
	return if (by.isBlank()) wanted else "artist:\"$by\" AND releasegroup:\"$wanted\""
}

/**
 * The one more attempt after a fielded query matched nothing — a real answer for an album whose
 * artist MusicBrainz credits differently. Free text ranks badly, which is why [releaseGroupFor]
 * validates whatever comes back; "" when there is no artist to add (the fielded query was
 * already the bare title) or no title.
 */
internal fun albumLookupFreeText(artist: String, title: String): String {
	val wanted = lucene(withoutEditionSuffix(title))
	val by = lucene(artist)
	return if (wanted.isBlank() || by.isBlank()) "" else "$by $wanted"
}

/** Lower-cased, punctuation-free, single-spaced. */
private fun normalizeName(value: String): String = value
	.lowercase()
	.map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }
	.joinToString("")
	.split(" ")
	.filter { it.isNotBlank() }
	.joinToString(" ")

private fun normalizeTitle(value: String): String = normalizeName(withoutEditionSuffix(value))

/**
 * Two credits naming the same act: equal, or one a prefix of the other on a token boundary
 * (MusicBrainz writes "Daft Punk feat. Romanthony" where the library files "Daft Punk").
 */
private fun artistsAgree(a: String, b: String): Boolean =
	a == b || a.startsWith("$b ") || b.startsWith("$a ")

/**
 * The release group the album belongs to, out of a lookup's [candidates] — or null.
 *
 * **Never the first hit.** MusicBrainz's order is a text score. A candidate counts only when
 * its title is the album's (edition suffix aside) and its artist agrees; among those, the one
 * lb-bot marks as this very album ([albumId]) wins outright, and the score decides the rest.
 * Owned as some OTHER album is no evidence about this one.
 */
internal fun releaseGroupFor(
	candidates: List<LbAlbumCandidate>,
	albumId: String,
	artist: String,
	title: String
): String? {
	val wantTitle = normalizeTitle(title)
	val wantArtist = normalizeName(artist)
	if (wantTitle.isBlank()) return null
	return candidates
		.filter { it.rgid.isNotBlank() }
		.filter { normalizeTitle(it.title) == wantTitle }
		.filter { wantArtist.isBlank() || artistsAgree(normalizeName(it.artist), wantArtist) }
		.maxByOrNull { candidate ->
			val thisAlbum = albumId.isNotBlank() && candidate.releaseOwned && candidate.releaseAlbumId == albumId
			(if (thisAlbum) 1_000_000 else 0) + candidate.score
		}
		?.rgid
}

// ----- what the button does -----------------------------------------------------------------

/** What confirming the picked release (and cover) sends. */
enum class AlbumCommit {
	/** Nothing would change: no release picked, or the album's own with no tag to rewrite. */
	NOTHING,

	/** `POST /lb/album/override`: retag, with the cover when one was chosen. */
	APPLY,

	/** `POST /lb/album/cover`: the release stays and no tag is rewritten; only the cover changes. */
	COVER_ONLY
}

/**
 * [changing] is [AlbumPreviewSummary.changing] for the picked release. Another release than the
 * album's is always an apply, even when no file fits it — lb-bot is asked, and answers with its
 * `poor_match` question — and so is the album's own release when its numbers or titles are not
 * what the files carry.
 */
internal fun albumCommit(
	currentReleaseMbid: String,
	pickedReleaseMbid: String,
	changing: Int,
	coverChosen: Boolean
): AlbumCommit = when {
	pickedReleaseMbid.isBlank() -> AlbumCommit.NOTHING
	pickedReleaseMbid != currentReleaseMbid || changing > 0 -> AlbumCommit.APPLY
	coverChosen -> AlbumCommit.COVER_ONLY
	else -> AlbumCommit.NOTHING
}

/**
 * A `poor_match` the user is being asked about — tied to the release it was answered for.
 * lb-bot's counts for release A say nothing about release B, whose own guard must answer for
 * itself.
 */
data class AlbumPoorMatchQuestion(
	val releaseMbid: String,
	val matched: Int,
	val total: Int
)

/**
 * Whether `force` may go out for [releaseMbid], and whether the confirmation is shown over it:
 * only for the release whose `poor_match` answer is being confirmed.
 */
internal fun forceAllowed(question: AlbumPoorMatchQuestion?, releaseMbid: String): Boolean =
	question != null && releaseMbid.isNotBlank() && question.releaseMbid == releaseMbid

// ----- the apply / remove poll --------------------------------------------------------------
// lb-bot answers an apply and a remove at once and works in the background. The album id the
// job was started for dies on the retag, so the job is followed by the override row's id:
// `GET /lb/album/override?id=`, one short request per tick. The machine is pure; the loop and
// the clock are [LbBotManager]'s (`followAlbumJob`), so the job is followed to its end whether
// or not the sheet that started it is still open.

const val ALBUM_POLL_INTERVAL_MS = 2_000L

/** After this long the client stops asking and says lb-bot is still working. */
const val ALBUM_POLL_GIVE_UP_MS = 180_000L

enum class AlbumJob { APPLY, REMOVE }

enum class AlbumPollPhase {
	POLLING,

	/** An apply lb-bot calls `applied` (with or without failures), or a remove whose row is gone. */
	DONE,

	/** lb-bot says the job failed, or a remove left the row standing. */
	FAILED,

	/** The row vanished under an apply: undone from somewhere else while this client waited. */
	GONE,

	/** Three minutes passed. Nothing is known to have failed — lb-bot may still be working. */
	TIMED_OUT
}

/** What one tick of the poll read. */
sealed interface AlbumPollRead {
	/** lb-bot answered with the row. */
	data class Row(val state: LbAlbumState) : AlbumPollRead

	/** 404 `no_override`: lb-bot holds no such row (any more). */
	data object Gone : AlbumPollRead

	/**
	 * No answer: the hub out of reach, a timeout, a 5xx, a body that is not a row. **A failed
	 * poll is not an answer** — it ends nothing and changes nothing but the clock.
	 */
	data object Missed : AlbumPollRead
}

/**
 * A poll in progress, or its ending.
 *
 * @property state the last answer, kept across missed polls so the progress does not blink.
 * @property sawActive lb-bot has been seen working on this job (`removing`, for a remove). A
 *   remove can be polled before lb-bot has flipped the row, and the `applied` — or `failed`, for
 *   an interrupted apply being undone — read then is the state it started from, not a verdict.
 */
data class AlbumPoll(
	val job: AlbumJob,
	val startedAtMs: Long,
	val phase: AlbumPollPhase = AlbumPollPhase.POLLING,
	val state: LbAlbumState? = null,
	val sawActive: Boolean = false
) {
	val row: LbAlbumOverrideRow? get() = state?.override
}

private fun activeState(job: AlbumJob): String = when (job) {
	AlbumJob.APPLY -> ALBUM_STATE_APPLYING
	AlbumJob.REMOVE -> ALBUM_STATE_REMOVING
}

/**
 * [seen] is the row as already read, for a job found running when the sheet opened (started on
 * another client, or before a restart): it is shown at once.
 */
internal fun albumPollStart(job: AlbumJob, nowMs: Long, seen: LbAlbumState? = null): AlbumPoll =
	AlbumPoll(
		job = job,
		startedAtMs = nowMs,
		state = seen,
		sawActive = seen?.override?.state == activeState(job)
	)

/** The job lb-bot is already running for the album, if any. */
internal fun albumJobInFlight(state: LbAlbumState): AlbumJob? = when (state.override?.state) {
	ALBUM_STATE_APPLYING -> AlbumJob.APPLY
	ALBUM_STATE_REMOVING -> AlbumJob.REMOVE
	else -> null
}

/** A poll's request as what it read. Only the row, or lb-bot's own `no_override`, is an answer. */
internal fun albumPollRead(result: LbBotManager.LbResult<LbAlbumState>): AlbumPollRead = when (result) {
	is LbBotManager.LbResult.Ok -> AlbumPollRead.Row(result.value)
	is LbBotManager.LbResult.Failed -> {
		val error = result.error
		if (error is LbBotManager.LbError.Rejected && error.status == 404 && error.code == ALBUM_CODE_NO_OVERRIDE) {
			AlbumPollRead.Gone
		} else {
			AlbumPollRead.Missed
		}
	}
}

/**
 * One tick. **An answer is judged before the clock**, so one that lands at the deadline still
 * counts; a missed poll moves nothing but the clock; an ended poll stays ended.
 *
 * - An apply ends on `applied` or `failed`. The row vanishing under it is its own ending.
 * - A remove ends when the row is gone. A row standing again (`applied` / `failed`) after it
 *   was seen `removing` is a remove that did not finish.
 * - A state this client has never heard of keeps the poll going.
 */
internal fun albumPollStep(poll: AlbumPoll, read: AlbumPollRead, nowMs: Long): AlbumPoll {
	if (poll.phase != AlbumPollPhase.POLLING) return poll
	val next = when (read) {
		AlbumPollRead.Missed -> poll
		AlbumPollRead.Gone -> return poll.copy(
			phase = if (poll.job == AlbumJob.REMOVE) AlbumPollPhase.DONE else AlbumPollPhase.GONE
		)
		is AlbumPollRead.Row -> {
			val state = read.state.override?.state.orEmpty()
			val heard = poll.copy(state = read.state, sawActive = poll.sawActive || state == activeState(poll.job))
			val standing = state == ALBUM_STATE_APPLIED || state == ALBUM_STATE_FAILED
			when (poll.job) {
				AlbumJob.APPLY -> when (state) {
					ALBUM_STATE_APPLIED -> return heard.copy(phase = AlbumPollPhase.DONE)
					ALBUM_STATE_FAILED -> return heard.copy(phase = AlbumPollPhase.FAILED)
					else -> heard
				}
				AlbumJob.REMOVE ->
					if (standing && heard.sawActive) return heard.copy(phase = AlbumPollPhase.FAILED) else heard
			}
		}
	}
	return if (nowMs - poll.startedAtMs >= ALBUM_POLL_GIVE_UP_MS) next.copy(phase = AlbumPollPhase.TIMED_OUT) else next
}

/** What an ended poll has to say. Every number in it is a field of lb-bot's last answer. */
sealed interface AlbumOutcome {
	/** `applied`, no failures: the tags of [files] files were written. */
	data class Wrote(val files: Int) : AlbumOutcome

	/** `applied` with failures: a partial result. The reasons are the row's `failures`. */
	data class WrotePartly(val done: Int, val total: Int, val failed: Int) : AlbumOutcome

	/** `failed`. Removing the override restores what was written. */
	data class DidNotFinish(val done: Int, val total: Int) : AlbumOutcome

	/** The row is gone after a remove: the previous tags are back. */
	data object Removed : AlbumOutcome

	/** A remove that left the row standing. */
	data object NotRemoved : AlbumOutcome

	/** The row vanished under an apply. */
	data object Gone : AlbumOutcome

	/** Three minutes without an ending. */
	data object StillWorking : AlbumOutcome
}

/** Null while the poll is still running. */
internal fun albumOutcome(poll: AlbumPoll): AlbumOutcome? {
	val row = poll.row
	return when (poll.phase) {
		AlbumPollPhase.POLLING -> null
		AlbumPollPhase.TIMED_OUT -> AlbumOutcome.StillWorking
		AlbumPollPhase.GONE -> AlbumOutcome.Gone
		AlbumPollPhase.DONE -> when {
			poll.job == AlbumJob.REMOVE -> AlbumOutcome.Removed
			row != null && row.failures.isNotEmpty() ->
				AlbumOutcome.WrotePartly(row.done, row.total, row.failures.size)
			else -> AlbumOutcome.Wrote(row?.done ?: 0)
		}
		AlbumPollPhase.FAILED -> when (poll.job) {
			AlbumJob.REMOVE -> AlbumOutcome.NotRemoved
			AlbumJob.APPLY -> AlbumOutcome.DidNotFinish(row?.done ?: 0, row?.total ?: 0)
		}
	}
}

// ----- where to go afterwards ---------------------------------------------------------------

/** Where the user belongs once a change has landed. */
sealed interface AlbumAfter {
	/** The album kept its id (or nothing is known to have moved): stay, and re-read it. */
	data object Stay : AlbumAfter

	/** The album lives under another Navidrome id now: open that page instead. */
	data class Album(val albumId: String) : AlbumAfter

	/** The album's id is not known (Navidrome has not indexed it yet): the artist's page. */
	data object Artist : AlbumAfter
}

/**
 * A retag changes the album's Navidrome id, so the id the job was started for is dead.
 * [currentAlbumId] is the row's `albumId`: the album's id now, "" while lb-bot does not know it.
 */
internal fun albumAfterOverride(openedAlbumId: String, currentAlbumId: String): AlbumAfter = when {
	currentAlbumId.isBlank() -> AlbumAfter.Artist
	currentAlbumId == openedAlbumId -> AlbumAfter.Stay
	else -> AlbumAfter.Album(currentAlbumId)
}

/**
 * Whether undoing [row] changes the album's Navidrome id — the id follows the release, so it
 * does exactly when the override had changed the release. Null when the row does not say (no
 * row was seen, or it names no release): then nothing may be concluded either way.
 */
private fun undoChangesAlbumId(row: LbAlbumOverrideRow?): Boolean? = when {
	row == null || row.releaseMbid.isBlank() -> null
	else -> row.releaseMbid != row.prevReleaseMbid
}

/**
 * [albumAfterOverride] for a poll's ending; [openedAlbumId] is the id the job was started for.
 *
 * - While lb-bot is still working, or never answered, nothing is known to have moved.
 * - `applied` moved the album to the row's `albumId` — "" when lb-bot's wait for Navidrome's
 *   index ran out, and then only the artist's page is certain to exist.
 * - Once a row is gone — a finished remove, or an apply undone elsewhere — nothing names the
 *   album's id any more: **never an album**. The one thing still known is when the id cannot
 *   have changed: the undo of an override that had kept the release (a cover-only change).
 * - "Did not finish" (an apply that failed, a remove that left the row standing) says nothing
 *   about where the album is. Only a different, non-empty id moves anyone.
 */
internal fun albumAfterPoll(poll: AlbumPoll, openedAlbumId: String): AlbumAfter {
	val current = poll.row?.albumId.orEmpty()
	return when (poll.phase) {
		AlbumPollPhase.POLLING, AlbumPollPhase.TIMED_OUT -> AlbumAfter.Stay
		AlbumPollPhase.GONE -> AlbumAfter.Artist
		AlbumPollPhase.DONE -> when (poll.job) {
			AlbumJob.APPLY -> albumAfterOverride(openedAlbumId, current)
			AlbumJob.REMOVE ->
				if (poll.sawActive && undoChangesAlbumId(poll.row) == false) AlbumAfter.Stay else AlbumAfter.Artist
		}
		AlbumPollPhase.FAILED ->
			if (current.isBlank()) AlbumAfter.Stay else albumAfterOverride(openedAlbumId, current)
	}
}

// ----- the job, once it is no longer the sheet's ----------------------------------------------
// **The job outlives the surface that started it.** [LbBotManager] follows an apply or a remove
// in its own scope, keyed by the override row's id, whether or not the sheet is still open. When
// it ends, three things are decided from the job alone: which screen leaves, which album id the
// local library forgets, and — meanwhile — whether another change may be sent.

/**
 * The album "Fix album match" is opened on, as the screen that opened it knows it: its
 * Navidrome id, the library's own names for it (shown until lb-bot has answered), and
 * [artistId] — where a screen left on the album's dead id is sent when lb-bot names no new
 * one, "" when the opener knows none.
 */
data class AlbumMatchTarget(
	val albumId: String,
	val title: String,
	val artistName: String,
	val artistId: String,
	val coverArtId: String?
)

/**
 * A job this device is following, or one it has followed to its end.
 *
 * @property rowId the override row's id: what the job is polled by, and its key.
 * @property albumId the Navidrome album id the job was started for. A retag changes the
 *   album's id, so this is the id that dies; a screen is "showing the dead album" when the id it
 *   shows equals this one. "" when the starter knew none.
 * @property artistId where to send a screen that must leave and has no album to go to; "" when
 *   the starter knew none.
 * @property after null while the job runs **and** while the local library is being brought to
 *   agree with how it ended; then where the album is. A screen acts only once this is set, so
 *   the page it is sent to exists.
 * @property endedAtMs when [after] was set, on this device's clock; 0 until then.
 */
data class AlbumJobState(
	val rowId: String,
	val albumId: String,
	val artistId: String,
	val poll: AlbumPoll,
	val after: AlbumAfter? = null,
	val endedAtMs: Long = 0L
) {
	/** Still polled, or still being settled: lb-bot's answer is not acted on yet. */
	val unfinished: Boolean get() = after == null
}

/** What a second change is refused with while one is followed: nothing was sent to lb-bot. */
const val ALBUM_JOB_RUNNING = "A change to this album is still running."

/**
 * The followed job that stands in the way of another apply or remove — for [albumId], or of the
 * row [rowId] itself, which an undo may reach under another album id than the job was started
 * for. Null when nothing does.
 *
 * **A second change is refused, not queued and not sent**: lb-bot would answer `in_progress`,
 * and a job followed twice would settle the library twice. A job this client gave up on after
 * three minutes is no longer in the way — lb-bot says so itself if it still is.
 */
internal fun albumJobInWay(jobs: Collection<AlbumJobState>, albumId: String, rowId: String = ""): AlbumJobState? =
	jobs.lastOrNull { job ->
		job.unfinished && (
			(albumId.isNotBlank() && job.albumId == albumId) ||
				(rowId.isNotBlank() && job.rowId == rowId)
			)
	}

/** What a screen does when a job has ended. */
sealed interface AlbumLeave {
	/** Not this screen's business: it shows another album or none, or nothing is known yet. */
	data object Stay : AlbumLeave

	/** The album kept its id: the page is still the album, and is read again. */
	data object Reread : AlbumLeave

	/** The album lives under [albumId] now, and the local library holds it: open that page. */
	data class ToAlbum(val albumId: String) : AlbumLeave

	/** The page's id is dead and nothing names the album's new one: the artist's page. */
	data class ToArtist(val artistId: String) : AlbumLeave

	/** As [ToArtist], with no artist known either: the dead page is simply left. */
	data object Back : AlbumLeave
}

/**
 * Where a screen showing album [shownAlbumId] goes when [job] has ended ("" for a screen that
 * shows no album). Feishin's `decideLeave`.
 *
 * **Decided from the job and the id on screen, never from the sheet**: the sheet may be long
 * closed, and a page left showing a dead id is exactly what this exists to prevent. Only a
 * screen showing the album the job was started for acts — compared by the whole id — and only
 * once the job is over and settled.
 *
 * And only a screen the ending concerns:
 * - one that was **showing the album as the job ended** ([onScreenSinceMs] no later than
 *   [AlbumJobState.endedAtMs]) leaves, whatever the library still holds — the dead row is only
 *   dropped once it is empty, which can be minutes later or never;
 * - one **opened afterwards** leaves only when the library no longer holds the id
 *   ([albumMissing]): a queue or a list still naming it, with nothing to show under it. Where
 *   the library does still hold it, it is an album in its own right — the files a retag left
 *   behind — and opening it must open it.
 */
internal fun albumLeave(
	job: AlbumJobState,
	shownAlbumId: String,
	onScreenSinceMs: Long,
	albumMissing: Boolean
): AlbumLeave {
	val after = job.after ?: return AlbumLeave.Stay
	if (shownAlbumId.isBlank() || shownAlbumId != job.albumId) return AlbumLeave.Stay
	if (onScreenSinceMs > job.endedAtMs && !albumMissing) return AlbumLeave.Stay
	return when (after) {
		is AlbumAfter.Album -> AlbumLeave.ToAlbum(after.albumId)
		AlbumAfter.Artist -> if (job.artistId.isBlank()) AlbumLeave.Back else AlbumLeave.ToArtist(job.artistId)
		// The id survived a job that did end; a timeout says nothing at all.
		AlbumAfter.Stay -> when (job.poll.phase) {
			AlbumPollPhase.DONE, AlbumPollPhase.FAILED -> AlbumLeave.Reread
			else -> AlbumLeave.Stay
		}
	}
}

/**
 * **The dead id is the client's to forget.** The album id the local library should stop
 * listing once [poll] has ended — [startedAlbumId], the id the job was started for — or "" when
 * nothing is to be removed. Only where the contract says the id changed:
 *
 * - an apply that ended `applied` with a non-empty `albumId` different from the started one;
 * - a remove that finished (the row gone after it was seen `removing`) of an override that had
 *   changed the release: putting the previous release back changes the id again.
 *
 * Never on `failed`, on a timeout, on a row that vanished, on an `applied` that names no id
 * (lb-bot's wait for the index ran out: the next sync settles it), or when the id cannot have
 * changed — a cover-only change, the same release applied again, and the undo of either.
 *
 * This says which id is DEAD TO LB-BOT. Whether the row can go is the library's own check:
 * `DbRepository.forgetAlbumIfEmpty` removes it only once no song hangs off it.
 */
internal fun albumDeadId(poll: AlbumPoll, startedAlbumId: String): String {
	if (startedAlbumId.isBlank() || poll.phase != AlbumPollPhase.DONE) return ""
	val row = poll.row ?: return ""
	val changed = when (poll.job) {
		AlbumJob.APPLY -> row.albumId.isNotBlank() && row.albumId != startedAlbumId
		AlbumJob.REMOVE -> poll.sawActive && undoChangesAlbumId(row) == true
	}
	return if (changed) startedAlbumId else ""
}
