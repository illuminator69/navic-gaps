package paige.navic.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/*
 * I-022 "Fix album match": the wire and the rules in AlbumOverride.kt, apart from the poll
 * (`AlbumOverridePollTest`). lb-bot and the hub are built beside this client against one written
 * contract, so every body below is that contract's, spelled out: what a route answers, what its
 * refusals mean, and what the sheet counts and says from them.
 */
class AlbumOverrideTest {
	private val release = "d105e2cd-1111-4111-8111-111111111111"
	private val other = "22222222-2222-4222-8222-222222222222"
	private val group = "33333333-3333-4333-8333-333333333333"

	private fun <T> value(result: LbBotManager.LbResult<T>): T =
		assertIs<LbBotManager.LbResult.Ok<T>>(result).value

	private fun error(result: LbBotManager.LbResult<*>): LbBotManager.LbError =
		assertIs<LbBotManager.LbResult.Failed>(result).error

	private fun rejected(status: Int, message: String, code: String = "") =
		LbBotManager.LbError.Rejected(status, message, code)

	// --- GET album/override ------------------------------------------------------------------

	@Test
	fun theStateBodyDecodesAsTheContractWritesIt() {
		val state = value(
			albumStateAnswer(
				200,
				"""{"albumId":"al-1","artist":"Tame Impala","album":"Currents","releaseMbid":"$release","rgid":"$group",
				"tracks":[{"disc":1,"track":2,"title":"Keep On Lying","duration":353}],
				"override":{"id":"ov-1","state":"applied","releaseMbid":"$release","prevReleaseMbid":"","rgid":"$group",
				"artist":"Tame Impala","album":"Currents","albumId":"al-2","coverUrl":"https://coverartarchive.org/release/x/1.jpg",
				"setAt":1760000000.5,"done":11,"total":12,"failures":[{"title":"Eventually","reason":"read-only file"}]}}""",
				byId = false
			)
		)
		assertEquals("al-1", state.albumId)
		assertEquals("Tame Impala", state.artist)
		assertEquals("Currents", state.album)
		assertEquals(release, state.releaseMbid)
		assertEquals(group, state.rgid)
		assertEquals(listOf(LbAlbumTrack(disc = 1, track = 2, title = "Keep On Lying", duration = 353.0)), state.tracks)
		assertEquals(
			LbAlbumOverrideRow(
				id = "ov-1",
				state = "applied",
				releaseMbid = release,
				prevReleaseMbid = "",
				rgid = group,
				artist = "Tame Impala",
				album = "Currents",
				albumId = "al-2",
				coverUrl = "https://coverartarchive.org/release/x/1.jpg",
				setAt = 1760000000.5,
				done = 11,
				total = 12,
				failures = listOf(LbAlbumFailure("Eventually", "read-only file"))
			),
			state.override
		)
	}

	@Test
	fun unknownFieldsAreIgnoredAndMissingOnesDefaulted() {
		val state = value(
			albumStateAnswer(
				200,
				"""{"albumId":"al-1","surprise":{"a":[1,2]},"tracks":[{"title":"X","bitrate":320}],"override":null}""",
				byId = false
			)
		)
		assertEquals(LbAlbumState(albumId = "al-1", tracks = listOf(LbAlbumTrack(title = "X"))), state)
		assertNull(state.override)
	}

	@Test
	fun aNullWhereAValueIsExpectedReadsAsTheDefault() {
		val state = value(
			albumStateAnswer(
				200,
				"""{"albumId":"al-1","rgid":null,"tracks":null,"override":{"id":"ov-1","state":"applying","done":null,"failures":null}}""",
				byId = false
			)
		)
		assertEquals("", state.rgid)
		assertEquals(emptyList(), state.tracks)
		assertEquals(LbAlbumOverrideRow(id = "ov-1", state = "applying"), state.override)
	}

	@Test
	fun aBodyThatNamesNoAlbumIsNotAnAnswer() {
		// `{}` decodes to an album with no name, no release and no tracks: "untagged", for nothing.
		assertEquals(rejected(200, ALBUM_NOT_AN_ANSWER), error(albumStateAnswer(200, "{}", byId = false)))
		assertEquals(rejected(200, ALBUM_NOT_AN_ANSWER), error(albumStateAnswer(200, "<!doctype html><html>", byId = false)))
		assertEquals(rejected(200, ALBUM_NOT_AN_ANSWER), error(albumStateAnswer(200, "", byId = false)))
	}

	@Test
	fun askedByIdTheAnswerIsTheRowAndItsAlbumIdMayBeBlank() {
		// After a retag Navidrome has not indexed the album yet: the row is the answer, "" the id.
		val state = value(
			albumStateAnswer(200, """{"albumId":"","album":"Currents","override":{"id":"ov-1","state":"applying","done":3,"total":12}}""", byId = true)
		)
		assertEquals("", state.albumId)
		assertEquals(3, state.override?.done)
		// The same body with no row answers nothing a poll asked.
		assertEquals(
			rejected(200, ALBUM_NOT_AN_ANSWER),
			error(albumStateAnswer(200, """{"albumId":"al-1","album":"Currents","override":null}""", byId = true))
		)
		assertEquals(
			rejected(200, ALBUM_NOT_AN_ANSWER),
			error(albumStateAnswer(200, """{"albumId":"al-1","override":{"state":"applied"}}""", byId = true))
		)
	}

	// --- refusals ----------------------------------------------------------------------------

	@Test
	fun aBareNotFoundIsAnLbBotThatPredatesTheRoute() {
		val older = LbBotManager.LbError.RouteUnknown
		assertEquals(older, error(albumStateAnswer(404, """{"error":"Not found"}""", byId = false)))
		assertEquals(older, error(albumStateAnswer(404, "", byId = false)))
		assertEquals(older, error(albumStateAnswer(404, "<html>404</html>", byId = true)))
		assertEquals(older, error(albumCoversAnswer(404, """{"error":"unknown route"}""")))
	}

	@Test
	fun methodNotAllowedIsAnLbBotThatPredatesTheRoute() {
		// lb-bot's SPA fallback is a GET-only catch-all: a POST it has never heard of is a 405.
		val older = LbBotManager.LbError.RouteUnknown
		assertEquals(older, error(albumPreviewAnswer(405, """{"error":"Method Not Allowed"}""")))
		assertEquals(older, error(albumApplyAnswer(405, "")))
		assertEquals(older, error(albumWriteAnswer(405, "<html>")))
	}

	@Test
	fun aCodedNotFoundIsLbBotsOwnAnswer() {
		assertEquals(
			rejected(404, "Navidrome has no such album", "unknown_album"),
			error(albumStateAnswer(404, """{"error":"Navidrome has no such album","code":"unknown_album"}""", byId = false))
		)
		assertEquals(
			rejected(404, "No such override", "no_override"),
			error(albumStateAnswer(404, """{"error":"No such override","code":"no_override"}""", byId = true))
		)
		assertEquals(
			rejected(404, "MusicBrainz has no such release", "unknown_release"),
			error(albumPreviewAnswer(404, """{"error":"MusicBrainz has no such release","code":"unknown_release"}"""))
		)
	}

	@Test
	fun busyAndInProgressKeepTheirCodeAndLbBotsText() {
		assertEquals(
			rejected(409, "A download for this album is running", "busy"),
			error(albumApplyAnswer(409, """{"error":"A download for this album is running","code":"busy"}"""))
		)
		assertEquals(
			rejected(409, "This album is already being retagged", "in_progress"),
			error(albumWriteAnswer(409, """{"error":"This album is already being retagged","code":"in_progress"}"""))
		)
	}

	@Test
	fun musicBrainzBeingDownIsNotTheHubBeingDown() {
		// lb-bot's own 502: nothing was written, and its sentence says which leg did not answer.
		assertEquals(
			rejected(502, "MusicBrainz did not answer"),
			error(albumPreviewAnswer(502, """{"error":"MusicBrainz did not answer","retryable":true}"""))
		)
		assertEquals(
			rejected(502, "The Cover Art Archive did not answer"),
			error(albumCoversAnswer(502, """{"error":"The Cover Art Archive did not answer","retryable":true}"""))
		)
		// The hub's own 502 / 504 carry no such flag: lb-bot was not reached at all.
		assertEquals(LbBotManager.LbError.Busy, error(albumPreviewAnswer(502, """{"error":"lb-bot unreachable","down":true}""")))
		assertEquals(LbBotManager.LbError.Busy, error(albumApplyAnswer(504, """{"error":"lb-bot timed out"}""")))
		assertEquals(LbBotManager.LbError.Busy, error(albumStateAnswer(502, "", byId = true)))
	}

	@Test
	fun aMalformedRequestIsRefusedInLbBotsWords() {
		assertEquals(
			rejected(400, "release_mbid must be a MusicBrainz id"),
			error(albumPreviewAnswer(400, """{"error":"release_mbid must be a MusicBrainz id"}"""))
		)
		assertEquals(
			rejected(400, "cover_url is not a Cover Art Archive image"),
			error(albumWriteAnswer(400, """{"error":"cover_url is not a Cover Art Archive image"}"""))
		)
	}

	// --- POST album/override -----------------------------------------------------------------

	@Test
	fun anApplyThatStartedNamesTheRowToPoll() {
		assertEquals(
			LbAlbumApply.Started("ov-1"),
			value(albumApplyAnswer(200, """{"ok":true,"id":"ov-1","state":"applying"}"""))
		)
	}

	@Test
	fun anApplyAnsweredWithoutOkOrWithoutAnIdStartedNothing() {
		val unconfirmed = rejected(200, WRITE_NOT_CONFIRMED)
		assertEquals(unconfirmed, error(albumApplyAnswer(200, """{"ok":false,"id":"ov-1"}""")))
		assertEquals(unconfirmed, error(albumApplyAnswer(200, """{"ok":true}""")))
		assertEquals(unconfirmed, error(albumApplyAnswer(200, "{}")))
		assertEquals(unconfirmed, error(albumApplyAnswer(200, "<html>")))
	}

	@Test
	fun aPoorMatchIsAQuestionThatNamesBothNumbers() {
		assertEquals(
			LbAlbumApply.PoorMatch(matched = 3, total = 12),
			value(albumApplyAnswer(409, """{"error":"Only 3 of 12 files match","code":"poor_match","matched":3,"total":12}"""))
		)
		// None of the files having a slot is still the question, with a zero in it.
		assertEquals(
			LbAlbumApply.PoorMatch(matched = 0, total = 9),
			value(albumApplyAnswer(409, """{"code":"poor_match","matched":0,"total":9}"""))
		)
	}

	@Test
	fun aPoorMatchWithoutItsNumbersIsShownAsLbBotWroteIt() {
		// The confirmation names both numbers; with one missing there is nothing true to ask.
		assertEquals(
			rejected(409, "Too few files match", "poor_match"),
			error(albumApplyAnswer(409, """{"error":"Too few files match","code":"poor_match"}"""))
		)
		assertEquals(
			rejected(409, "Too few files match", "poor_match"),
			error(albumApplyAnswer(409, """{"error":"Too few files match","code":"poor_match","matched":3}"""))
		)
	}

	@Test
	fun aPoorMatchIsOnlyReadOffAConflict() {
		assertEquals(
			rejected(400, "bad", "poor_match"),
			error(albumApplyAnswer(400, """{"error":"bad","code":"poor_match","matched":3,"total":12}"""))
		)
	}

	// --- POST album/cover, album/override/remove ----------------------------------------------

	@Test
	fun aCoverChangeAndARemoveAnswerTheRowsId() {
		assertEquals(
			LbAlbumWrite(ok = true, id = "ov-1"),
			value(albumWriteAnswer(200, """{"ok":true,"id":"ov-1"}"""))
		)
		assertEquals(
			LbAlbumWrite(ok = true, id = "ov-1", state = "removing"),
			value(albumWriteAnswer(200, """{"ok":true,"id":"ov-1","state":"removing","took":0.2}"""))
		)
	}

	@Test
	fun aWriteThatDoesNotSayOkIsNotDone() {
		val unconfirmed = rejected(200, WRITE_NOT_CONFIRMED)
		assertEquals(unconfirmed, error(albumWriteAnswer(200, """{"id":"ov-1"}""")))
		assertEquals(unconfirmed, error(albumWriteAnswer(200, """{"ok":true,"id":""}""")))
		assertEquals(unconfirmed, error(albumWriteAnswer(200, "{}")))
	}

	@Test
	fun removingARowThatIsGoneIsLbBotsOwnAnswer() {
		assertEquals(
			rejected(404, "No such override", "no_override"),
			error(albumWriteAnswer(404, """{"error":"No such override","code":"no_override"}"""))
		)
	}

	// --- GET album/covers --------------------------------------------------------------------

	@Test
	fun coversComeInLbBotsOrder() {
		val covers = value(
			albumCoversAnswer(
				200,
				"""{"covers":[
				{"thumb":"https://coverartarchive.org/release/$release/1-250.jpg","full":"https://coverartarchive.org/release/$release/1.jpg","releaseMbid":"$release","source":"release"},
				{"thumb":"https://coverartarchive.org/release-group/$group/front-250","full":"https://coverartarchive.org/release-group/$group/front","releaseMbid":"","source":"release-group"}]}"""
			)
		)
		assertEquals(
			listOf(
				LbAlbumCover(
					thumb = "https://coverartarchive.org/release/$release/1-250.jpg",
					full = "https://coverartarchive.org/release/$release/1.jpg",
					releaseMbid = release,
					source = "release"
				),
				LbAlbumCover(
					thumb = "https://coverartarchive.org/release-group/$group/front-250",
					full = "https://coverartarchive.org/release-group/$group/front",
					releaseMbid = "",
					source = "release-group"
				)
			),
			covers
		)
	}

	@Test
	fun anEmptyCoverListIsAnAnswerAndABodyWithNoListIsNot() {
		// `[]` is "the Archive has none"; `{}` is nothing anybody said.
		assertEquals(emptyList(), value(albumCoversAnswer(200, """{"covers":[]}""")))
		assertEquals(rejected(200, ALBUM_NOT_AN_ANSWER), error(albumCoversAnswer(200, "{}")))
		assertEquals(rejected(200, ALBUM_NOT_AN_ANSWER), error(albumCoversAnswer(200, """{"covers":null}""")))
	}

	@Test
	fun aCoverThatCannotBeSentBackIsNotOffered() {
		// `cover_url` must be one of the `full` URLs; a row without one, or with something a
		// browser would not open, cannot be chosen.
		val covers = value(
			albumCoversAnswer(
				200,
				"""{"covers":[{"thumb":"https://a/t.jpg","full":""},{"thumb":"https://a/t.jpg","full":"file:///etc/passwd"},
				{"thumb":"","full":"https://archive.org/b.jpg","source":"release-group"}]}"""
			)
		)
		// A missing thumbnail falls back to the image itself.
		assertEquals(
			listOf(LbAlbumCover(thumb = "https://archive.org/b.jpg", full = "https://archive.org/b.jpg", source = "release-group")),
			covers
		)
	}

	// --- POST album/override/preview ---------------------------------------------------------

	private val previewBody = """{"albumId":"al-1","releaseMbid":"$release",
		"release":{"title":"Currents","disambiguation":"deluxe","year":"2015","country":"AU","format":"CD","trackCount":13,"discCount":1},
		"files":[
		{"from":{"disc":1,"track":1,"title":"Let It Happen"},"to":{"disc":1,"track":1,"title":"Let It Happen"},"basis":"mbid","changed":false},
		{"from":{"disc":1,"track":3,"title":"the moment"},"to":{"disc":1,"track":3,"title":"The Moment"},"basis":"exact","changed":true},
		{"from":{"disc":0,"track":0,"title":"Yes Im Changing"},"to":{"disc":1,"track":4,"title":"Yes I'm Changing"},"basis":"fuzzy","changed":true},
		{"from":{"disc":1,"track":14,"title":"Hidden demo"},"to":null,"basis":"","changed":false}],
		"matched":3,"unmatched":1,
		"missing":[{"disc":1,"track":2,"title":"Nangs"},{"disc":1,"track":5,"title":"Eventually"}]}"""

	@Test
	fun thePreviewBodyDecodesAsTheContractWritesIt() {
		val preview = value(albumPreviewAnswer(200, previewBody))
		assertEquals("al-1", preview.albumId)
		assertEquals(release, preview.releaseMbid)
		assertEquals(
			LbPreviewRelease(
				title = "Currents",
				disambiguation = "deluxe",
				year = "2015",
				country = "AU",
				format = "CD",
				trackCount = 13,
				discCount = 1
			),
			preview.release
		)
		assertEquals(4, preview.files.size)
		assertEquals(
			LbPreviewFile(
				from = LbPreviewSlot(0, 0, "Yes Im Changing"),
				to = LbPreviewSlot(1, 4, "Yes I'm Changing"),
				basis = "fuzzy",
				changed = true
			),
			preview.files[2]
		)
		assertNull(preview.files[3].to)
		assertEquals(3, preview.matched)
		assertEquals(1, preview.unmatched)
		assertEquals(listOf(LbPreviewSlot(1, 2, "Nangs"), LbPreviewSlot(1, 5, "Eventually")), preview.missing)
	}

	@Test
	fun aYearSentAsANumberIsStillAYear() {
		val preview = value(
			albumPreviewAnswer(200, """{"albumId":"al-1","releaseMbid":"$release","release":{"title":"Currents","year":2015}}""")
		)
		assertEquals("2015", preview.release.year)
	}

	@Test
	fun aPreviewThatNamesNoReleaseIsNotAnAnswer() {
		// `{}` would read as "0 files change, none missing": a preview of nothing, said as fact.
		assertEquals(rejected(200, ALBUM_NOT_AN_ANSWER), error(albumPreviewAnswer(200, "{}")))
		assertEquals(rejected(200, ALBUM_NOT_AN_ANSWER), error(albumPreviewAnswer(200, """{"albumId":"al-1","files":[]}""")))
	}

	// --- previewSummary ----------------------------------------------------------------------

	@Test
	fun thePreviewIsSummedIntoItsThreeCounts() {
		val summary = previewSummary(value(albumPreviewAnswer(200, previewBody)))
		assertEquals(AlbumPreviewSummary(changing = 2, unchanged = 1, noSlot = 1, missing = 2), summary)
	}

	@Test
	fun aFileWithNoSlotIsNeverCountedAsChanging() {
		// An apply leaves it untouched, whatever its `changed` says.
		val preview = LbAlbumPreview(
			releaseMbid = release,
			files = listOf(
				LbPreviewFile(from = LbPreviewSlot(1, 1, "A"), to = null, changed = true),
				LbPreviewFile(from = LbPreviewSlot(1, 2, "B"), to = LbPreviewSlot(1, 2, "B"), changed = true)
			),
			matched = 1,
			unmatched = 1
		)
		assertEquals(AlbumPreviewSummary(changing = 1, unchanged = 0, noSlot = 1, missing = 0), previewSummary(preview))
	}

	@Test
	fun theNoSlotCountIsTheLargerOfTheListAndLbBotsOwn() {
		// The warning it drives must show when either says a file is left behind.
		val slotless = LbPreviewFile(from = LbPreviewSlot(1, 1, "A"), to = null)
		assertEquals(1, previewSummary(LbAlbumPreview(releaseMbid = release, files = listOf(slotless), unmatched = 0)).noSlot)
		assertEquals(3, previewSummary(LbAlbumPreview(releaseMbid = release, files = listOf(slotless), unmatched = 3)).noSlot)
	}

	@Test
	fun anEmptyPreviewSumsToZeros() {
		assertEquals(AlbumPreviewSummary(0, 0, 0, 0), previewSummary(LbAlbumPreview(releaseMbid = release)))
	}

	// --- slotLabel ---------------------------------------------------------------------------

	@Test
	fun aSlotIsNumberedByDiscOnlyWhereThereIsMoreThanOne() {
		assertEquals("3", slotLabel(LbPreviewSlot(1, 3, "X"), multiDisc = false))
		assertEquals("2-3", slotLabel(LbPreviewSlot(2, 3, "X"), multiDisc = true))
		// An untagged file has no number to show, and "0" would read as one.
		assertEquals("", slotLabel(LbPreviewSlot(0, 0, "X"), multiDisc = false))
		assertEquals("", slotLabel(LbPreviewSlot(1, 0, "X"), multiDisc = true))
		// A track number with no disc tag beside it: the number alone, never "0-3".
		assertEquals("3", slotLabel(LbPreviewSlot(0, 3, "X"), multiDisc = true))
	}

	@Test
	fun aPreviewIsMultiDiscWhenTheReleaseOrAnyFileSaysSo() {
		val one = LbPreviewFile(from = LbPreviewSlot(1, 1, "A"), to = LbPreviewSlot(1, 1, "A"))
		assertEquals(false, previewMultiDisc(LbAlbumPreview(releaseMbid = release, release = LbPreviewRelease(discCount = 1), files = listOf(one))))
		assertEquals(true, previewMultiDisc(LbAlbumPreview(releaseMbid = release, release = LbPreviewRelease(discCount = 2), files = listOf(one))))
		// Files tagged across two discs being put onto a one-disc release still need the disc shown.
		val second = LbPreviewFile(from = LbPreviewSlot(2, 1, "B"), to = LbPreviewSlot(1, 2, "B"))
		assertEquals(true, previewMultiDisc(LbAlbumPreview(releaseMbid = release, release = LbPreviewRelease(discCount = 1), files = listOf(one, second))))
		assertEquals(true, previewMultiDisc(LbAlbumPreview(releaseMbid = release, missing = listOf(LbPreviewSlot(2, 1, "C")))))
	}

	// --- parseReleaseMbid --------------------------------------------------------------------

	@Test
	fun aBareReleaseMbidIsAccepted() {
		assertEquals(release, parseReleaseMbid(release))
	}

	@Test
	fun whitespaceAndCaseAreNormalised() {
		assertEquals(release, parseReleaseMbid("  ${release.uppercase()}\n"))
	}

	@Test
	fun aMusicBrainzReleaseUrlIsAccepted() {
		assertEquals(release, parseReleaseMbid("https://musicbrainz.org/release/$release"))
		assertEquals(release, parseReleaseMbid("http://musicbrainz.org/release/$release"))
		assertEquals(release, parseReleaseMbid("musicbrainz.org/release/$release"))
		assertEquals(release, parseReleaseMbid("https://beta.musicbrainz.org/release/$release"))
		assertEquals(release, parseReleaseMbid("https://musicbrainz.org/release/$release/cover-art"))
		assertEquals(release, parseReleaseMbid("https://musicbrainz.org/release/$release?tab=discids#top"))
		assertEquals(release, parseReleaseMbid("HTTPS://MusicBrainz.org/release/${release.uppercase()}"))
	}

	@Test
	fun aUrlForSomethingOtherThanAReleaseIsRefused() {
		// A release group's id is a well-formed UUID and names no release.
		assertNull(parseReleaseMbid("https://musicbrainz.org/release-group/$group"))
		assertNull(parseReleaseMbid("https://musicbrainz.org/artist/$other"))
		assertNull(parseReleaseMbid("https://musicbrainz.org/recording/$other"))
	}

	@Test
	fun aLookAlikeHostIsNotMusicBrainz() {
		assertNull(parseReleaseMbid("https://musicbrainz.org.example.com/release/$release"))
		assertNull(parseReleaseMbid("https://notmusicbrainz.org/release/$release"))
		assertNull(parseReleaseMbid("https://example.com/musicbrainz.org/release/$release"))
	}

	@Test
	fun anythingElseIsRefused() {
		assertNull(parseReleaseMbid(""))
		assertNull(parseReleaseMbid("   "))
		assertNull(parseReleaseMbid("Currents"))
		assertNull(parseReleaseMbid("d105e2cd-1111-4111-8111"))
		assertNull(parseReleaseMbid("id: $release"))
		assertNull(parseReleaseMbid("$release $other"))
	}

	// --- albumEditionRows --------------------------------------------------------------------

	private fun edition(mbid: String, label: String, country: String = "", year: String = "", cover: String = "") =
		LbEdition(releaseMbid = mbid, label = label, format = label, year = year, country = country, coverUrl = cover)

	@Test
	fun everyEditionOfEveryVariantIsOneRowInLbBotsOrder() {
		val rows = albumEditionRows(
			listOf(
				LbVariant(
					releaseMbid = release,
					title = "Currents",
					year = "2015",
					trackCount = 13,
					coverUrl = "https://c/variant",
					editions = listOf(edition(release, "CD", "AU", "2015", "https://c/cd"), edition(other, "Vinyl", "GB"))
				),
				LbVariant(releaseMbid = group, title = "Currents", disambiguation = "collector's", trackCount = 17)
			),
			currentReleaseMbid = other
		)
		assertEquals(
			listOf(
				AlbumEditionRow(release, "Currents", "", "CD", "CD", "AU", "2015", 13, "https://c/cd", current = false),
				// The edition's own year and cover first, the variant's where it has none.
				AlbumEditionRow(other, "Currents", "", "Vinyl", "Vinyl", "GB", "2015", 13, "https://c/variant", current = true),
				// A variant with no editions is itself a release.
				AlbumEditionRow(group, "Currents", "collector's", "", "", "", "", 17, "", current = false)
			),
			rows
		)
	}

	@Test
	fun aReleaseListedTwiceIsOneRowAndABlankOneIsNone() {
		val rows = albumEditionRows(
			listOf(
				LbVariant(releaseMbid = release, title = "A", editions = listOf(edition(release, "CD"), edition("", "Vinyl"))),
				LbVariant(releaseMbid = release, title = "A again", editions = listOf(edition(release, "Digital"))),
				LbVariant(releaseMbid = "", title = "nothing")
			),
			currentReleaseMbid = ""
		)
		assertEquals(listOf(release), rows.map { it.releaseMbid })
		assertEquals("CD", rows.single().label)
		// An untagged album marks nothing as current.
		assertEquals(false, rows.single().current)
	}

	// --- albumTaggedAs -----------------------------------------------------------------------

	@Test
	fun theTaggedReleaseIsDescribedFromTheEditionsWhenTheyListIt() {
		val rows = listOf(AlbumEditionRow(release, "Currents", "deluxe", "CD", "CD", "AU", "2015", 13, "", current = true))
		assertEquals(
			AlbumTaggedAs(releaseMbid = release, title = "Currents", disambiguation = "deluxe", year = "2015", country = "AU", format = "CD", trackCount = 13),
			albumTaggedAs(LbAlbumState(albumId = "al-1", album = "Currents (Deluxe)", releaseMbid = release), rows)
		)
	}

	@Test
	fun aTaggedReleaseTheEditionsDoNotListIsOnlyItsId() {
		// Nothing is known of it but the id: the library's own title is not the release's.
		assertEquals(
			AlbumTaggedAs(releaseMbid = release),
			albumTaggedAs(LbAlbumState(albumId = "al-1", album = "Currents", releaseMbid = release), emptyList())
		)
	}

	@Test
	fun anUntaggedAlbumIsTaggedAsNothing() {
		assertNull(albumTaggedAs(LbAlbumState(albumId = "al-1", album = "Currents", releaseMbid = ""), emptyList()))
	}

	// --- the release group lookup ------------------------------------------------------------

	@Test
	fun theLookupIsFieldedAndDropsTheEditionSuffix() {
		assertEquals(
			"""artist:"Daft Punk" AND releasegroup:"Discovery"""",
			albumLookupQuery("Daft Punk", "Discovery (Remastered)")
		)
		// What Lucene would read as syntax is dropped, as the Discover lookup drops it.
		assertEquals(
			"""artist:"ACDC" AND releasegroup:"Back in Black"""",
			albumLookupQuery("AC\"DC", " Back in Black [2003 remaster] ")
		)
		// With no artist to field, the title alone.
		assertEquals("Discovery", albumLookupQuery("  ", "Discovery"))
		// A title that is ONLY a parenthetical keeps it.
		assertEquals("""artist:"Sigur Rós" AND releasegroup:"( )"""", albumLookupQuery("Sigur Rós", "( )"))
		assertEquals("", albumLookupQuery("Daft Punk", "  "))
	}

	@Test
	fun theSecondAttemptIsFreeTextAndOnlyWhenItAddsSomething() {
		assertEquals("Daft Punk Discovery", albumLookupFreeText("Daft Punk", "Discovery (Remastered)"))
		// With no artist the fielded query was already the bare title: asking again adds nothing.
		assertEquals("", albumLookupFreeText(" ", "Discovery"))
		assertEquals("", albumLookupFreeText("Daft Punk", " "))
	}

	private fun candidate(rgid: String, title: String, artist: String, score: Int = 0, ownedAs: String = "") =
		LbAlbumCandidate(
			rgid = rgid,
			title = title,
			artist = artist,
			score = score,
			releaseOwned = ownedAs.isNotBlank(),
			releaseAlbumId = ownedAs
		)

	@Test
	fun theFirstHitIsNeverTakenOnItsRank() {
		// MusicBrainz's order is a text score: a title that CONTAINS both words outranks the album.
		val candidates = listOf(
			candidate(other, "Daft Punk's Discovery but it's in the SM64 Soundfont", "Pignickel", score = 100),
			candidate(group, "Discovery", "Daft Punk", score = 92)
		)
		assertEquals(group, releaseGroupFor(candidates, "al-1", "Daft Punk", "Discovery"))
	}

	@Test
	fun theAnswerIsCheckedAgainstTheArtistAndTheTitle() {
		assertNull(releaseGroupFor(listOf(candidate(group, "Discovery", "Mike Oldfield", 100)), "al-1", "Daft Punk", "Discovery"))
		assertNull(releaseGroupFor(listOf(candidate(group, "Discovery Live", "Daft Punk", 100)), "al-1", "Daft Punk", "Discovery"))
		assertNull(releaseGroupFor(emptyList(), "al-1", "Daft Punk", "Discovery"))
	}

	@Test
	fun punctuationCaseAndAnEditionSuffixDoNotDefeatTheMatch() {
		assertEquals(
			group,
			releaseGroupFor(listOf(candidate(group, "discovery", "DAFT PUNK", 80)), "al-1", "Daft Punk", "Discovery (Remastered)")
		)
		// MusicBrainz writes the full credit where the library files the act.
		assertEquals(
			group,
			releaseGroupFor(listOf(candidate(group, "Discovery", "Daft Punk feat. Romanthony", 80)), "al-1", "Daft Punk", "Discovery")
		)
	}

	@Test
	fun theGroupLbBotSaysIsThisVeryAlbumOutranksAnyScore() {
		val candidates = listOf(
			candidate(other, "Bad", "Michael Jackson", score = 100),
			candidate(group, "Bad", "Michael Jackson", score = 60, ownedAs = "al-1")
		)
		assertEquals(group, releaseGroupFor(candidates, "al-1", "Michael Jackson", "Bad"))
		// Owned as some OTHER album is no evidence about this one: the score decides.
		assertEquals(other, releaseGroupFor(candidates, "al-9", "Michael Jackson", "Bad"))
	}

	@Test
	fun aCandidateWithNoReleaseGroupResolvesNothing() {
		assertNull(releaseGroupFor(listOf(candidate("", "Discovery", "Daft Punk", 100)), "al-1", "Daft Punk", "Discovery"))
	}

	// --- albumCommit -------------------------------------------------------------------------

	@Test
	fun theSameReleaseWithNothingToRewriteIsNothingToDo() {
		assertEquals(AlbumCommit.NOTHING, albumCommit(release, release, changing = 0, coverChosen = false))
		assertEquals(AlbumCommit.NOTHING, albumCommit(release, "", changing = 3, coverChosen = true))
	}

	@Test
	fun aCoverOnTheSameReleaseWithNothingToRewriteIsACoverChangeAlone() {
		assertEquals(AlbumCommit.COVER_ONLY, albumCommit(release, release, changing = 0, coverChosen = true))
	}

	@Test
	fun anythingThatRewritesATagIsAnApply() {
		assertEquals(AlbumCommit.APPLY, albumCommit(release, other, changing = 12, coverChosen = false))
		assertEquals(AlbumCommit.APPLY, albumCommit(release, other, changing = 12, coverChosen = true))
		// The same release, but its numbers or titles are not what the files carry.
		assertEquals(AlbumCommit.APPLY, albumCommit(release, release, changing = 2, coverChosen = false))
		assertEquals(AlbumCommit.APPLY, albumCommit(release, release, changing = 2, coverChosen = true))
		// An untagged album has no current release to equal.
		assertEquals(AlbumCommit.APPLY, albumCommit("", release, changing = 0, coverChosen = false))
		// Another release that no file fits: lb-bot is asked, and answers `poor_match`.
		assertEquals(AlbumCommit.APPLY, albumCommit(release, other, changing = 0, coverChosen = false))
	}

	// --- forceAllowed ------------------------------------------------------------------------

	@Test
	fun aPoorMatchConfirmationBelongsToTheReleaseItWasAnsweredFor() {
		val question = AlbumPoorMatchQuestion(releaseMbid = release, matched = 3, total = 12)
		assertEquals(true, forceAllowed(question, release))
		// lb-bot's counts for one release say nothing about another: no question, no force.
		assertEquals(false, forceAllowed(question, other))
		assertEquals(false, forceAllowed(null, release))
		assertEquals(false, forceAllowed(AlbumPoorMatchQuestion("", 0, 0), ""))
	}

	// --- albumAfterOverride ------------------------------------------------------------------

	@Test
	fun afterAChangeTheAlbumIsWhereLbBotSaysItIs() {
		assertEquals(AlbumAfter.Album("al-2"), albumAfterOverride(openedAlbumId = "al-1", currentAlbumId = "al-2"))
		// Navidrome has not indexed the retagged album yet: there is no album page to open.
		assertEquals(AlbumAfter.Artist, albumAfterOverride(openedAlbumId = "al-1", currentAlbumId = ""))
		// The id survived: the page under the sheet is still the album.
		assertEquals(AlbumAfter.Stay, albumAfterOverride(openedAlbumId = "al-1", currentAlbumId = "al-1"))
	}
}
