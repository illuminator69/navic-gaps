package paige.navic.domain.manager

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/*
 * B-084: the album source picker says when a folder holds more files than the album, and offers
 * "Download all M files" beside the row's own download.
 *
 * Every sentence is a field of an lb-bot answer: `extraFiles` on a source row of
 * `GET /lb/album/sources`, the `allFiles` echo on the answer of `POST /lb/album/download`,
 * `leftOut` on the fill view. Nothing is worked out here from `files` or `fileCount`, except M,
 * the number in the button's label. Absent is not zero: a row without `extraFiles` (an older
 * lb-bot, an unknown tracklist, every gap-fill row) has nothing to say. Mirrors Feishin's
 * `album-download.harness.ts` case for case.
 */
class AlbumDownloadAllTest {
	// --- the source row -----------------------------------------------------------------------

	private fun row(fileCount: Int = 11, extra: String = ""): LbGapSource = lbWireJson.decodeFromString(
		LbGapSource.serializer(),
		"""{"id":3,"peer":"bob","folder":"Music\\After\\After EP","fileCount":$fileCount,""" +
			""""coverage":"5/5 tracks","coverageFull":true$extra}"""
	)

	@Test
	fun extraFilesIsReadAsSentAndAbsentIsNotZero() {
		assertNull(row().extraFiles)
		assertNull(row(extra = ""","extraFiles":null""").extraFiles)
		assertEquals(0, row(extra = ""","extraFiles":0""").extraFiles)
		assertEquals(6, row(extra = ""","extraFiles":6""").extraFiles)
	}

	@Test
	fun aRowWithoutExtraFilesIsTodaysRow() {
		assertNull(sourceExtras(row()))
	}

	@Test
	fun aRowWithNoExtraFilesIsTodaysRow() {
		assertNull(sourceExtras(row(extra = ""","extraFiles":0""")))
	}

	@Test
	fun oneExtraFileIsSaidAndTheButtonCountsTheFolder() {
		assertEquals(
			SourceExtras(more = 1, all = 6),
			sourceExtras(row(fileCount = 6, extra = ""","extraFiles":1"""))
		)
	}

	@Test
	fun sixExtraFilesAreSaidAndTheButtonCountsTheFolder() {
		assertEquals(
			SourceExtras(more = 6, all = 11),
			sourceExtras(row(fileCount = 11, extra = ""","extraFiles":6"""))
		)
	}

	@Test
	fun aNonsenseCountIsNothing() {
		assertNull(sourceExtras(row(extra = ""","extraFiles":-2""")))
	}

	@Test
	fun aGapFillRowCarriesNoExtraFilesAndAddsNothing() {
		// The gap routes send the same row shape without the field: the two pickers share
		// the model, and only the album one's rows can say anything.
		val gapRow = lbWireJson.decodeFromString(
			LbGapSource.serializer(),
			"""{"id":1,"peer":"bob","folder":"Music\\After EP","fileCount":11,"coverage":"full"}"""
		)
		assertNull(gapRow.extraFiles)
		assertNull(sourceExtras(gapRow))
	}

	// --- the request --------------------------------------------------------------------------

	private val picked = LbGapSource(peer = "bob", folder = "Music\\After EP", fileCount = 11)

	private fun body(
		source: LbGapSource? = picked,
		allFiles: Boolean = false
	): Map<String, Any?> = lbWireJson.parseToJsonElement(
		lbWireJson.encodeToString(
			LbDownloadRequest.serializer(),
			albumDownloadRequest(
				rgid = "rg-1",
				quality = "flac",
				source = source,
				edition = LbResolvedEdition("rel-1", "After", "After EP", 5),
				allowMp3 = true,
				allFiles = allFiles
			)
		)
	).jsonObject.toMap()

	@Test
	fun aPlainDownloadSendsNoAllFilesKey() {
		assertFalse("allFiles" in body())
	}

	@Test
	fun downloadAllSendsAllFilesTrueWithThePickedFolder() {
		val sent = body(allFiles = true)
		assertEquals(JsonPrimitive(true), sent["allFiles"])
		assertEquals(JsonPrimitive("bob"), sent["sourceUsername"])
		assertEquals(JsonPrimitive("Music\\After EP"), sent["sourceFolder"])
	}

	@Test
	fun downloadAllIsTheSameRequestPlusTheOneKey() {
		assertEquals(body(), body(allFiles = true) - "allFiles")
		assertEquals(JsonPrimitive("rel-1"), body()["release_mbid"])
		assertEquals(JsonPrimitive(true), body()["allowMp3"])
	}

	@Test
	fun allFilesIsNeverSentWithoutAPickedSource() {
		// lb-bot answers 400 for it ("allFiles needs a picked source"), so it is not asked.
		assertFalse("allFiles" in body(source = null, allFiles = true))
		assertFalse("allFiles" in body(source = LbGapSource(peer = " ", folder = "x"), allFiles = true))
	}

	// --- the answer's echo --------------------------------------------------------------------

	private fun answer(extra: String = ""): LbDownloadResult = albumDownloadResult(
		lbWireJson.decodeFromString(
			LbDownloadResponse.serializer(),
			"""{"ok":true,"existing":false,"resolved":{"release_mbid":"rel-1"}$extra}"""
		)
	)

	@Test
	fun theEchoIsReadAsLbBotSaidIt() {
		assertEquals(true, answer(""","allFiles":true""").allFiles)
		assertEquals(false, answer(""","allFiles":false""").allFiles)
	}

	@Test
	fun anAnswerWithoutTheEchoStaysWithoutOne() {
		// An older hub drops the key from the request, an older lb-bot never echoes it.
		val old = answer()
		assertNull(old.allFiles)
		assertEquals("rel-1", old.releaseMbid)
		assertEquals(true, old.ok)
	}

	// --- what is said after the POST ----------------------------------------------------------

	private fun note(asked: Boolean = true, ok: Boolean = true, existing: Boolean = false, echo: Boolean?) =
		allFilesNote(asked = asked, ok = ok, existing = existing, echo = echo, peer = "bob", fileCount = 11)

	@Test
	fun echoTrueSaysAllFilesWithThePeerAndTheCount() {
		assertEquals(AllFilesNote.Asked(peer = "bob", files = 11), note(echo = true))
	}

	@Test
	fun echoFalseSaysTracksOnlyAndNeverAllFiles() {
		assertEquals(AllFilesNote.TracksOnly, note(echo = false))
	}

	@Test
	fun noEchoSaysTracksOnlyAndNeverAllFiles() {
		assertEquals(AllFilesNote.TracksOnly, note(echo = null))
	}

	@Test
	fun aFillAlreadyRunningKeepsItsOwnWordingWhateverWasEchoed() {
		// `existing: true` echoes what the RUNNING fill was started with; the sheet's
		// "Already downloading" is all that is said.
		listOf(true, false, null).forEach { echo ->
			assertEquals(AllFilesNote.None, note(existing = true, echo = echo), "echo=$echo")
		}
	}

	@Test
	fun anAnswerThatStartedNothingClaimsNoDownload() {
		// A 200 whose `ok` is false started nothing: neither "all files" nor "tracks only".
		listOf(true, false, null).forEach { echo ->
			assertEquals(AllFilesNote.None, note(ok = false, echo = echo), "echo=$echo")
		}
	}

	@Test
	fun aPlainDownloadHasNothingNewToSay() {
		listOf(true, false, null).forEach { echo ->
			assertEquals(AllFilesNote.None, note(asked = false, echo = echo), "echo=$echo")
		}
	}

	// --- the fill view ------------------------------------------------------------------------

	private fun fill(extra: String = ""): LbFillStatus = lbWireJson.decodeFromString(
		LbFillStatus.serializer(),
		"""{"state":"downloading","done":2,"total":5$extra}"""
	)

	@Test
	fun theFillViewCarriesAllFilesAndLeftOut() {
		val tracksOnly = fill(""","allFiles":false,"leftOut":6""")
		assertEquals(false, tracksOnly.allFiles)
		assertEquals(6, tracksOnly.leftOut)
		val whole = fill(""","allFiles":true,"leftOut":0""")
		assertEquals(true, whole.allFiles)
		assertEquals(0, whole.leftOut)
	}

	@Test
	fun aFillViewThatDoesNotSayReadsNotSaidAndNeverZero() {
		// An lb-bot older than the fields, and the hub's `fill` frame: rebuilt field by
		// field, it carries neither. 0 is something lb-bot says; silence is not 0.
		val silent = fill()
		assertNull(silent.allFiles)
		assertNull(silent.leftOut)
		assertEquals("downloading", silent.state)
	}

	@Test
	fun everyAlbumOfTheFillsReadCarriesThem() {
		val fills = lbWireJson.decodeFromString(
			LbFillsResponse.serializer(),
			"""{"albums":{"rg-1":{"state":"downloading","leftOut":6},"rg-2":{"state":"queued","leftOut":0}},"gaps":{}}"""
		)
		assertEquals(6, fills.albums.getValue("rg-1").leftOut)
		assertEquals(0, fills.albums.getValue("rg-2").leftOut)
	}

	// --- a view that does not say, after one that did ------------------------------------------

	private fun view(state: String = "downloading", source: String = "bob", leftOut: Int? = null) =
		LbFillStatus(state = state, source = source, done = 3, total = 5, leftOut = leftOut)

	@Test
	fun aFrameThatDoesNotSayKeepsWhatTheLastViewSaid() {
		// Poll: 6 left out. Then a `fill` frame for the next file, silent about it.
		val kept = fillKeepingLeftOut(view(), previous = view(leftOut = 6))
		assertEquals(6, kept.leftOut)
		assertEquals(3, kept.done)
		// The last frame of a fill, too: the finished row is no longer polled.
		assertEquals(6, fillKeepingLeftOut(view(state = "verified"), previous = view(leftOut = 6)).leftOut)
		assertEquals(6, fillKeepingLeftOut(view(state = "verified", source = ""), previous = view(leftOut = 6)).leftOut)
	}

	@Test
	fun aViewThatSaysIsTakenAtItsWord() {
		assertEquals(0, fillKeepingLeftOut(view(leftOut = 0), previous = view(leftOut = 6)).leftOut)
		assertEquals(2, fillKeepingLeftOut(view(leftOut = 2), previous = view(leftOut = 6)).leftOut)
		assertEquals(6, fillKeepingLeftOut(view(leftOut = 6), previous = null).leftOut)
	}

	@Test
	fun nothingIsKeptAcrossANewSearch() {
		// A retry, or lb-bot starting over: the count was the previous attempt's.
		assertNull(fillKeepingLeftOut(view(state = "searching", source = ""), previous = view(leftOut = 6)).leftOut)
		assertNull(fillKeepingLeftOut(view(state = "searching"), previous = view(leftOut = 6)).leftOut)
	}

	@Test
	fun nothingIsKeptAcrossASwitchToAnotherSource() {
		// lb-bot moved on to another peer: that folder is asked afresh, and only a full
		// view says what was left out of it.
		assertNull(fillKeepingLeftOut(view(source = "carol"), previous = view(leftOut = 6)).leftOut)
	}

	@Test
	fun nothingIsMadeUpWhenNothingWasEverSaid() {
		assertNull(fillKeepingLeftOut(view(), previous = null).leftOut)
		assertNull(fillKeepingLeftOut(view(), previous = view()).leftOut)
	}
}
