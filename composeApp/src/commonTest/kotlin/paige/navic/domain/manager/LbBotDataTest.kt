package paige.navic.domain.manager

import paige.navic.data.database.entities.SyncActionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * I-020: the sentences of the "lb-bot data" panel, Refresh About and the pending-actions browser
 * (LbBotData.kt). Each is a claim about lb-bot, and the cases here are the ones where a claim was
 * once made from nothing: "already up to date" said after a timeout, which read the same whether
 * the mirror was current, the pull had failed or the pull was still running; an empty answer
 * replacing the About on screen; "Loading…" over a list that nothing was loading.
 */
class LbBotDataTest {
	private fun <T> ok(value: T) = LbBotManager.LbResult.Ok(value)
	private fun failed(error: LbBotManager.LbError) = LbBotManager.LbResult.Failed(error)
	private val unreachable = LbBotManager.LbError.Unreachable("timeout")

	// --- the index mirror's last pull ------------------------------------------------------

	@Test
	fun upToDateIsOnlyEverSaidOfAPullThatFinishedAndChangedNothing() {
		assertEquals(
			"Up to date: the last pull changed nothing (change 412).",
			indexRunLine(LbIndexRun.Done(serial = 3, cursor = 412, applied = 0))
		)
		// The three facts that used to share that sentence each read differently.
		val others = listOf(
			LbIndexRun.Idle,
			LbIndexRun.Running(4),
			LbIndexRun.Failed(4, "lb-bot was busy or unreachable"),
			LbIndexRun.Skipped(4, LbIndexSkip.LB_BOT_DOWN),
			LbIndexRun.Skipped(4, LbIndexSkip.NO_FEED),
			LbIndexRun.Skipped(4, LbIndexSkip.NOT_SET_UP),
			LbIndexRun.Skipped(4, LbIndexSkip.REFUSED)
		).map { indexRunLine(it) }
		assertTrue(others.none { it.contains("Up to date") })
		assertEquals(others.size, others.toSet().size)
	}

	@Test
	fun aPullThatChangedSomethingSaysHowMuchAndWhereItEnded() {
		assertEquals(
			"Pulled: 1 artist changed (now at change 413).",
			indexRunLine(LbIndexRun.Done(5, cursor = 413, applied = 1))
		)
		assertEquals(
			"Pulled: 7 artists changed (now at change 420).",
			indexRunLine(LbIndexRun.Done(6, cursor = 420, applied = 7))
		)
	}

	@Test
	fun aRunningAFailedAndAnUnstartedPullEachSaySo() {
		assertEquals("Not pulled since the app started.", indexRunLine(LbIndexRun.Idle))
		assertEquals("Pulling lb-bot's index…", indexRunLine(LbIndexRun.Running(1)))
		assertEquals(
			"The pull failed: lb-bot was busy or unreachable. It resumes where it stopped and is tried again on its own.",
			indexRunLine(LbIndexRun.Failed(2, "lb-bot was busy or unreachable"))
		)
	}

	@Test
	fun aSkippedPullSaysWhyItDidNothing() {
		assertEquals("Not pulled: no hub is set up.", indexRunLine(LbIndexRun.Skipped(1, LbIndexSkip.NOT_SET_UP)))
		assertEquals(
			"Not pulled: this hub or lb-bot has no index feed.",
			indexRunLine(LbIndexRun.Skipped(1, LbIndexSkip.NO_FEED))
		)
		assertEquals(
			"Not pulled: lb-bot is not reachable. The mirror is unchanged.",
			indexRunLine(LbIndexRun.Skipped(1, LbIndexSkip.LB_BOT_DOWN))
		)
		assertEquals(
			"Not pulled: lb-bot refused the request. The mirror is unchanged.",
			indexRunLine(LbIndexRun.Skipped(1, LbIndexSkip.REFUSED))
		)
	}

	@Test
	fun aFailureNamesItsCause() {
		assertEquals("lb-bot was busy or unreachable", indexFailureDetail(LbBotManager.LbError.Busy))
		assertEquals("the hub could not be reached", indexFailureDetail(unreachable))
		assertEquals(
			"another device's pull was in the way",
			indexFailureDetail(LbBotManager.LbError.Rejected(503, "busy"))
		)
		assertEquals(
			"lb-bot answered an error (500)",
			indexFailureDetail(LbBotManager.LbError.Rejected(500, ""))
		)
		assertEquals("no hub is set up", indexFailureDetail(LbBotManager.LbError.NotConfigured))
		assertEquals(
			"this hub or lb-bot has no index feed",
			indexFailureDetail(LbBotManager.LbError.RouteUnknown)
		)
	}

	@Test
	fun theMirrorsNumbersAreSaidAsTheyAre() {
		assertEquals(
			"812 artists mirrored, at change 4120 (epoch 9f3c2a1b).",
			mirrorLine(true, LbMirrorInfo(artists = 812, cursor = 4120, epoch = "9f3c2a1b77e0"))
		)
		assertEquals(
			"1 artist mirrored, at change 7.",
			mirrorLine(true, LbMirrorInfo(artists = 1, cursor = 7, epoch = ""))
		)
		// An empty mirror is not "0 artists at change 0" as though that were a pull's result.
		assertEquals("Nothing mirrored yet.", mirrorLine(true, LbMirrorInfo()))
		assertEquals(
			"This hub or lb-bot has no index feed, so nothing is mirrored here.",
			mirrorLine(false, LbMirrorInfo(artists = 812, cursor = 4120, epoch = "9f3c"))
		)
	}

	// --- what a list fed by lb-bot is ------------------------------------------------------

	@Test
	fun aListIsOnlyReadingWhileARequestIsInFlightAndNothingIsLoaded() {
		assertEquals(LbPanelData.READING, panelData(supported = true, reading = true, loaded = false, readFailed = false))
		// Not loading, nothing loaded: lb-bot could not be asked. Never "Loading…".
		assertEquals(LbPanelData.UNREACHABLE, panelData(supported = true, reading = false, loaded = false, readFailed = true))
		assertEquals(LbPanelData.UNREACHABLE, panelData(supported = true, reading = false, loaded = false, readFailed = false))
	}

	@Test
	fun aLoadedListStaysOnScreenAndSaysWhenItIsTheLastCopy() {
		assertEquals(LbPanelData.CURRENT, panelData(supported = true, reading = false, loaded = true, readFailed = false))
		assertEquals(LbPanelData.STALE, panelData(supported = true, reading = false, loaded = true, readFailed = true))
		// A re-read in flight does not turn a list that is on screen back into "reading".
		assertEquals(LbPanelData.CURRENT, panelData(supported = true, reading = true, loaded = true, readFailed = false))
		assertEquals(LbPanelData.STALE, panelData(supported = true, reading = true, loaded = true, readFailed = true))
	}

	@Test
	fun anOlderHubOrLbBotIsUnsupportedWhateverElseIsTrue() {
		assertEquals(LbPanelData.UNSUPPORTED, panelData(supported = false, reading = true, loaded = true, readFailed = false))
		assertEquals(LbPanelData.UNSUPPORTED, panelData(supported = false, reading = false, loaded = false, readFailed = true))
	}

	@Test
	fun theOverridesNoteSaysEachStateInItsOwnWords() {
		assertEquals(
			"This hub or lb-bot is older and does not store MusicBrainz overrides yet.",
			overridesNote(LbPanelData.UNSUPPORTED, 0)
		)
		assertEquals("Asking lb-bot…", overridesNote(LbPanelData.READING, 0))
		assertEquals(
			"lb-bot is needed for this and is not reachable right now.",
			overridesNote(LbPanelData.UNREACHABLE, 0)
		)
		assertEquals(
			"Can't reach lb-bot. This is the copy this device loaded last.",
			overridesNote(LbPanelData.STALE, 3)
		)
		assertEquals(
			"None. An override is set from an artist's page: Fix MusicBrainz match.",
			overridesNote(LbPanelData.CURRENT, 0)
		)
		assertNull(overridesNote(LbPanelData.CURRENT, 3))
	}

	@Test
	fun anOverrideRowSaysWhatNavidromeSaid() {
		assertEquals(
			"Navidrome said: 11111111-1111-4111-8111-111111111111",
			overrideTagLine(LbArtistOverride(tagMbid = "11111111-1111-4111-8111-111111111111"))
		)
		assertEquals("Navidrome said: none", overrideTagLine(LbArtistOverride(tagMbid = "")))
	}

	// --- a set or a remove made from the panel ---------------------------------------------

	@Test
	fun aWriteOnlySaysAScanIsRunningWhenLbBotStartedOne() {
		assertEquals(
			"Saved. lb-bot is rescanning this artist.",
			overrideWriteLine(ok(LbOverrideWrite(ok = true, taskId = "task-1")))
		)
		// A blank task id is lb-bot deciding not to scan; the panel starts none and claims none.
		assertEquals("Saved.", overrideWriteLine(ok(LbOverrideWrite(ok = true, taskId = ""))))
	}

	@Test
	fun aRefusedWriteIsSaidInLbBotsWords() {
		assertEquals(
			"Not saved: A download for this artist is running.",
			overrideWriteLine(failed(LbBotManager.LbError.Rejected(409, "A download for this artist is running")))
		)
		assertEquals(
			"Not saved: the request failed (400).",
			overrideWriteLine(failed(LbBotManager.LbError.Rejected(400, "")))
		)
		assertEquals(
			"Not saved: lb-bot is busy or unreachable.",
			overrideWriteLine(failed(LbBotManager.LbError.Busy))
		)
		assertEquals("Not saved: the hub could not be reached.", overrideWriteLine(failed(unreachable)))
		// lb-bot's own full stop is not doubled.
		assertEquals(
			"Not saved: MusicBrainz has no such artist.",
			overrideWriteLine(failed(LbBotManager.LbError.Rejected(404, "MusicBrainz has no such artist.")))
		)
	}

	@Test
	fun anOlderLbBotIsNamedNotShownAsAnError() {
		assertEquals(
			"This lb-bot is older and can't store a MusicBrainz match yet. Nothing was changed.",
			overrideWriteLine(failed(LbBotManager.LbError.RouteUnknown))
		)
	}

	// --- listening history, in words -------------------------------------------------------

	@Test
	fun theListeningStatusIsSaidForEveryState() {
		fun line(phase: ListeningPhase, count: Int = 0, lastSync: String = "") =
			listeningStatusLine(advertised = true, phase = phase, count = count, lastSync = lastSync)
		assertEquals("Asking lb-bot…", line(ListeningPhase.Loading))
		assertEquals("Off: lb-bot has no ListenBrainz user set up.", line(ListeningPhase.Off))
		assertEquals("This lb-bot has no listening history yet.", line(ListeningPhase.Older))
		assertEquals(
			"lb-bot is needed for this and is not reachable right now.",
			line(ListeningPhase.Unreachable)
		)
		assertEquals("Importing: 1200 of 9000 plays so far.", line(ListeningPhase.Importing(1200, 9000)))
		assertEquals("Importing: 1200 plays so far.", line(ListeningPhase.Importing(1200, null)))
		assertEquals(
			"Ready: 9000 plays stored. Last sync 2026-10-03 21:05.",
			line(ListeningPhase.Ready, count = 9000, lastSync = "2026-10-03 21:05")
		)
		assertEquals("Ready: 0 plays stored.", line(ListeningPhase.Ready))
		assertEquals(
			"The last sync failed: ListenBrainz did not answer. 9000 plays already stored are kept.",
			line(ListeningPhase.SyncFailed("ListenBrainz did not answer"), count = 9000)
		)
		// lb-bot's own full stop is not doubled.
		assertEquals(
			"The last sync failed: token rejected. 3 plays already stored are kept.",
			line(ListeningPhase.SyncFailed("token rejected."), count = 3)
		)
		assertEquals(
			"The last sync failed. 9000 plays already stored are kept.",
			line(ListeningPhase.SyncFailed(""), count = 9000)
		)
	}

	@Test
	fun aHubThatDoesNotAdvertiseListeningSaysSoWhateverThePhase() {
		assertEquals(
			"This hub does not offer listening history yet.",
			listeningStatusLine(advertised = false, phase = ListeningPhase.Ready, count = 5, lastSync = "x")
		)
	}

	// --- "Sync now", the listening half ----------------------------------------------------

	private fun status(
		state: String,
		count: Int = 100,
		lastSyncAt: Double? = 1_000.0,
		error: String = "",
		total: Int? = null
	) = LbListeningStatus(state = state, count = count, total = total, lastSyncAt = lastSyncAt, error = error)

	private val asked = ok(LbListeningSync(ok = true, state = "ready"))

	@Test
	fun a405IsAnLbBotWithNoListeningHistoryNotAnError() {
		assertEquals(
			ListeningSyncOutcome.Older,
			listeningSyncOutcome(null, failed(LbBotManager.LbError.RouteUnknown), null)
		)
		assertEquals(
			"This lb-bot has no listening history yet.",
			listeningSyncLine(ListeningSyncOutcome.Older)
		)
	}

	@Test
	fun aRequestThatFailedSaysLbBotWasNotAsked() {
		assertEquals(
			ListeningSyncOutcome.NotAsked(LbBotManager.LbError.Busy),
			listeningSyncOutcome(status("ready"), failed(LbBotManager.LbError.Busy), null)
		)
		assertEquals(
			"Couldn't ask lb-bot to sync: lb-bot is busy or unreachable.",
			listeningSyncLine(ListeningSyncOutcome.NotAsked(LbBotManager.LbError.Busy))
		)
	}

	@Test
	fun acceptedIsNotFinished() {
		// The request's own `ok` says the pull was asked for. Without a status showing a newer
		// sync, "synced" would be a guess — and so would "already up to date".
		assertEquals(ListeningSyncOutcome.Waiting, listeningSyncOutcome(status("ready"), asked, null))
		assertEquals(
			ListeningSyncOutcome.Waiting,
			listeningSyncOutcome(status("ready", lastSyncAt = 1_000.0), asked, ok(status("ready", lastSyncAt = 1_000.0)))
		)
		assertEquals(
			"lb-bot was asked to pull. No new sync has finished yet.",
			listeningSyncLine(ListeningSyncOutcome.Waiting)
		)
	}

	@Test
	fun aNewerLastSyncIsTheEvidenceThatItFinished() {
		assertEquals(
			ListeningSyncOutcome.Finished(120),
			listeningSyncOutcome(
				status("ready", lastSyncAt = 1_000.0), asked, ok(status("ready", count = 120, lastSyncAt = 1_500.0))
			)
		)
		// Never synced before, synced now.
		assertEquals(
			ListeningSyncOutcome.Finished(5),
			listeningSyncOutcome(
				status("ready", lastSyncAt = null), asked, ok(status("ready", count = 5, lastSyncAt = 1_500.0))
			)
		)
		// The status could not be read beforehand: a sync time now is still a finished sync.
		assertEquals(
			ListeningSyncOutcome.Finished(5),
			listeningSyncOutcome(null, asked, ok(status("ready", count = 5, lastSyncAt = 1_500.0)))
		)
		assertEquals("Synced: 120 plays stored.", listeningSyncLine(ListeningSyncOutcome.Finished(120)))
	}

	@Test
	fun theStatusAfterwardsIsWhatIsReported() {
		assertEquals(
			ListeningSyncOutcome.Importing(40, 900),
			listeningSyncOutcome(status("ready"), asked, ok(status("importing", count = 40, total = 900)))
		)
		assertEquals(
			ListeningSyncOutcome.Off,
			listeningSyncOutcome(status("ready"), asked, ok(status("off")))
		)
		assertEquals(
			ListeningSyncOutcome.Failed("ListenBrainz said 503"),
			listeningSyncOutcome(status("ready"), asked, ok(status("error", error = "ListenBrainz said 503")))
		)
		assertEquals(
			ListeningSyncOutcome.AskedUnread,
			listeningSyncOutcome(status("ready"), asked, failed(unreachable))
		)
		assertEquals(
			ListeningSyncOutcome.AskedUnread,
			listeningSyncOutcome(status("ready"), asked, ok(status("")))
		)
	}

	@Test
	fun anErrorThatWasAlreadyThereIsNotThisSyncsFailure() {
		val old = status("error", error = "ListenBrainz said 503")
		assertEquals(ListeningSyncOutcome.Waiting, listeningSyncOutcome(old, asked, ok(old)))
		// A different message is a new failure.
		assertEquals(
			ListeningSyncOutcome.Failed("token rejected"),
			listeningSyncOutcome(old, asked, ok(status("error", error = "token rejected")))
		)
	}

	@Test
	fun everyListeningSyncOutcomeHasItsOwnSentence() {
		val lines = listOf(
			ListeningSyncOutcome.Asking,
			ListeningSyncOutcome.Older,
			ListeningSyncOutcome.NotAsked(unreachable),
			ListeningSyncOutcome.Waiting,
			ListeningSyncOutcome.AskedUnread,
			ListeningSyncOutcome.Finished(3),
			ListeningSyncOutcome.Importing(3, 9),
			ListeningSyncOutcome.Importing(3, null),
			ListeningSyncOutcome.Failed("boom"),
			ListeningSyncOutcome.Failed(""),
			ListeningSyncOutcome.Off
		).map { listeningSyncLine(it) }
		assertEquals(lines.size, lines.toSet().size)
		assertEquals("lb-bot was asked to pull. Its status could not be read since.", lines[4])
		assertEquals("lb-bot is importing: 3 of 9 plays so far.", lines[6])
		assertEquals("The sync failed: boom", lines[8])
		assertEquals("The sync failed.", lines[9])
	}

	// --- Refresh About ---------------------------------------------------------------------

	@Test
	fun anAnswerWithNoAboutInItIsNotAnAbout() {
		assertNull(aboutBodyOrNull("{}"))
		assertNull(aboutBodyOrNull(""))
		assertNull(aboutBodyOrNull("[]"))
		assertNull(aboutBodyOrNull("<!doctype html><title>lb-bot</title>"))
		assertNull(aboutBodyOrNull("""{"error":"boom"}"""))
		assertNull(aboutBodyOrNull("""{"found":"maybe"}"""))
	}

	@Test
	fun aSparseAnswerIsContent() {
		// lb-bot looked and nobody has written about this. That is an answer, and it replaces.
		val sparse = aboutBodyOrNull("""{"found":false,"summary":"","paragraphs":[],"links":[]}""")
		assertEquals(LbMeta(found = false), sparse)
		assertEquals(LbMeta(found = false), aboutBodyOrNull("""{"found":false}"""))
		val full = aboutBodyOrNull("""{"found":true,"summary":"An English rock band.","paragraphs":["An English rock band."]}""")
		assertEquals(true, full?.found)
		assertEquals("An English rock band.", full?.summary)
	}

	@Test
	fun onlyAnAboutReplacesWhatIsShown() {
		assertEquals(
			LbMetaRefresh.Replaced(LbMeta(found = true, summary = "x")),
			metaRefreshOf(ok("""{"found":true,"summary":"x"}"""))
		)
		assertEquals(LbMetaRefresh.Replaced(LbMeta(found = false)), metaRefreshOf(ok("""{"found":false}""")))
		assertEquals(LbMetaRefresh.Nothing, metaRefreshOf(ok("{}")))
		assertEquals(LbMetaRefresh.Nothing, metaRefreshOf(ok("not json")))
	}

	@Test
	fun notReachingLbBotAndLbBotAnsweringAnErrorAreDifferentFailures() {
		assertEquals(LbMetaRefresh.Unreachable, metaRefreshOf(failed(unreachable)))
		assertEquals(LbMetaRefresh.Unreachable, metaRefreshOf(failed(LbBotManager.LbError.Busy)))
		assertEquals(LbMetaRefresh.Unreachable, metaRefreshOf(failed(LbBotManager.LbError.NotConfigured)))
		assertEquals(LbMetaRefresh.Refused(500), metaRefreshOf(failed(LbBotManager.LbError.Rejected(500, "boom"))))
		assertEquals(LbMetaRefresh.Refused(404), metaRefreshOf(failed(LbBotManager.LbError.RouteUnknown)))
	}

	@Test
	fun everyRefreshOutcomeEndsInItsOwnSentence() {
		assertEquals(
			AboutRefreshNotice.UPDATED,
			aboutRefreshNotice(LbMetaRefresh.Replaced(LbMeta(found = true, summary = "x")))
		)
		assertEquals(
			AboutRefreshNotice.UPDATED_EMPTY,
			aboutRefreshNotice(LbMetaRefresh.Replaced(LbMeta(found = false)))
		)
		assertEquals(AboutRefreshNotice.NOTHING, aboutRefreshNotice(LbMetaRefresh.Nothing))
		assertEquals(AboutRefreshNotice.UNREACHABLE, aboutRefreshNotice(LbMetaRefresh.Unreachable))
		assertEquals(AboutRefreshNotice.ERROR, aboutRefreshNotice(LbMetaRefresh.Refused(500)))
	}

	// --- pending actions -------------------------------------------------------------------

	@Test
	fun everyQueuedActionHasWords() {
		assertEquals(PendingActionKind.Scrobble, pendingActionKind(SyncActionType.SCROBBLE))
		assertEquals(PendingActionKind.Star, pendingActionKind(SyncActionType.STAR))
		assertEquals(PendingActionKind.Unstar, pendingActionKind(SyncActionType.UNSTAR))
		assertEquals(PendingActionKind.DeletePlaylist, pendingActionKind(SyncActionType.DELETE_PLAYLIST))
		assertEquals(
			(0..5).map { PendingActionKind.Rate(it) },
			listOf(
				SyncActionType.STAR_0, SyncActionType.STAR_1, SyncActionType.STAR_2,
				SyncActionType.STAR_3, SyncActionType.STAR_4, SyncActionType.STAR_5
			).map { pendingActionKind(it) }
		)
		// Nothing in the enum is left without a kind.
		assertEquals(SyncActionType.entries.size, SyncActionType.entries.map { pendingActionKind(it) }.size)
	}

	@Test
	fun anItemsTitleComesFromRoomAndFallsBackToItsId() {
		val titles = mapOf("so-1" to "Paranoid Android", "al-1" to "OK Computer", "pl-9" to " ")
		assertEquals("Paranoid Android", pendingActionTitle("so-1", titles))
		assertEquals("OK Computer", pendingActionTitle("al-1", titles))
		// Not in the local tables any more (a deleted playlist): the id, never a blank row.
		assertEquals("pl-404", pendingActionTitle("pl-404", titles))
		assertEquals("pl-9", pendingActionTitle("pl-9", titles))
		assertFalse(pendingActionTitle("", emptyMap()).isNotBlank())
	}
}
