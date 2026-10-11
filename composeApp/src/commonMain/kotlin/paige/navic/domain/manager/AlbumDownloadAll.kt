package paige.navic.domain.manager

/*
 * B-084: "this folder holds more files than the album", and "Download all M files".
 *
 * A whole-album download asks a peer's folder only for the release's tracks when the folder has
 * them all — a folder that also held six files of another release used to be asked for all
 * eleven, the peer refused the six, and the source was taken for failed. So the album picker
 * says when a folder has more, and offers the whole folder as a second, explicit action.
 *
 * Every sentence here is a field of an lb-bot answer, and nothing is worked out on the device
 * from a row's `files` or `fileCount` (except M, the number in the button's label):
 * - `extraFiles` on a source row of `GET /lb/album/sources` → the row's line and second action;
 * - the `allFiles` echo on the answer of `POST /lb/album/download` → what is said afterwards;
 * - `leftOut` on the fill view → the status line (`leftOutPart` in `FillVocabulary.kt`), kept
 *   across the views that do not say it ([fillKeepingLeftOut]).
 *
 * Pure, so `AlbumDownloadAllTest` runs it against the contract's own bodies. Feishin's
 * `album-download.ts` is the hand-mirrored twin.
 */

/** What an album source row adds when its folder holds more than the album. */
data class SourceExtras(
	/** N of "N more files in this folder": lb-bot's `extraFiles`. */
	val more: Int,
	/** M of "Download all M files": the row's `fileCount`, as sent. */
	val all: Int
)

/**
 * The row's addition, or null for exactly the row there was before: `extraFiles` absent (lb-bot
 * does not know the tracklist, or is older than the field) or 0. **Absent is never shown as 0**,
 * and it is also what keeps the gap-fill picker's rows as they were — the gap routes send the
 * same row shape without the field, and only the album picker calls this.
 */
fun sourceExtras(source: LbGapSource): SourceExtras? {
	val extra = source.extraFiles ?: return null
	return if (extra > 0) SourceExtras(more = extra, all = source.fileCount) else null
}

/** What the sheet says, beyond what it always said, after a download was asked for. */
sealed interface AllFilesNote {
	/** Nothing new: all files were not asked for, lb-bot started nothing, or a fill was
	 *  already running (the sheet's own "Already downloading" is then the whole of it). */
	data object None : AllFilesNote
	/** lb-bot echoed `allFiles: true`: "Asked @peer for all M files in the folder". */
	data class Asked(val peer: String, val files: Int) : AllFilesNote
	/** All files were asked for and lb-bot did not say it took that: the album's tracks only. */
	data object TracksOnly : AllFilesNote
}

/**
 * The echo decides, never the request. An older hub drops `allFiles` from the body without a
 * word (its allowlist) and lb-bot then starts an ordinary download: [echo] is false, or absent
 * from an lb-bot older still, and saying "all files" would be a claim nothing made. For a fill
 * that was already running ([existing]) the echo is what THAT fill was started with, which this
 * request did not change — the sheet's existing wording stands alone. And an answer that is not
 * [ok] started no download at all: "downloading the album's tracks only" would be as untrue of
 * it as "all files".
 */
fun allFilesNote(
	asked: Boolean,
	ok: Boolean,
	existing: Boolean,
	echo: Boolean?,
	peer: String,
	fileCount: Int
): AllFilesNote = when {
	!asked -> AllFilesNote.None
	!ok -> AllFilesNote.None
	existing -> AllFilesNote.None
	echo == true -> AllFilesNote.Asked(peer = peer, files = fileCount)
	else -> AllFilesNote.TracksOnly
}

/**
 * [view], with what the last view said of `leftOut` (and `allFiles`) kept when this one does
 * not say.
 *
 * The fill view is fed two ways into one writer (`LbBotManager.applyAlbumStatus`): the
 * `/lb/fills` poll, whose albums say `leftOut` every time, and the hub's `fill` frame, which
 * the hub rebuilds field by field from a list that has no `leftOut` — and a frame arrives on
 * every step of a download, with the poll backed off to 30 s behind it. Read as 0, each frame
 * would take the count off the status line until the next poll put it back. So silence keeps
 * the last said value, **while it is still about the same thing**:
 * - a view that says ([LbFillStatus.leftOut] not null) is taken at its word, 0 included;
 * - a new search (`searching`: a retry, or lb-bot starting over) keeps nothing — the count was
 *   the previous attempt's;
 * - a view naming another peer than the last one keeps nothing — lb-bot moved on, that folder
 *   is asked afresh (and for the release's tracks only), and only a full view says what it left
 *   out. Until one does, nothing is shown: never a number about a folder it was not counted in.
 * A view that names no peer is not a switch.
 */
fun fillKeepingLeftOut(view: LbFillStatus, previous: LbFillStatus?): LbFillStatus {
	if (view.leftOut != null || previous == null) return view
	val said = previous.leftOut ?: return view
	val anotherSource = view.source.isNotBlank() && previous.source.isNotBlank() &&
		view.source != previous.source
	if (view.state == "searching" || anotherSource) return view
	return view.copy(leftOut = said, allFiles = view.allFiles ?: previous.allFiles)
}
