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
	fun afterATapTheEarlierVerdictIsNotShownAsItsAnswer() {
		// Pass 3 finished "up to date" a while ago. "Sync now" is tapped, and no newer pass has
		// started yet: the line must not go on saying "Up to date" as if that answered the tap.
		val earlier = listOf(
			LbIndexRun.Done(serial = 3, cursor = 412, applied = 0),
			LbIndexRun.Done(serial = 3, cursor = 412, applied = 5),
			LbIndexRun.Skipped(3, LbIndexSkip.LB_BOT_DOWN),
			LbIndexRun.Failed(3, "lb-bot was busy or unreachable")
		)
		earlier.forEach { run ->
			assertEquals("A pull has been asked for…", indexRunLineAfterTap(run, serialAtTap = 3, overdue = false))
			// A few seconds on and still no pass: say that, never the earlier verdict.
			assertEquals(
				"No pull has started since you asked.",
				indexRunLineAfterTap(run, serialAtTap = 3, overdue = true)
			)
		}
		// The first tap of a process: nothing has run at all.
		assertEquals("A pull has been asked for…", indexRunLineAfterTap(LbIndexRun.Idle, serialAtTap = 0, overdue = false))
		assertEquals(
			"No pull has started since you asked.",
			indexRunLineAfterTap(LbIndexRun.Idle, serialAtTap = 0, overdue = true)
		)
	}

	@Test
	fun aPassNewerThanTheTapAnswersIt() {
		// Whatever started after the tap is a fact from after the tap, late or not.
		listOf(false, true).forEach { overdue ->
			assertEquals(
				"Pulling lb-bot's index…",
				indexRunLineAfterTap(LbIndexRun.Running(4), serialAtTap = 3, overdue = overdue)
			)
			assertEquals(
				"Up to date: the last pull changed nothing (change 412).",
				indexRunLineAfterTap(LbIndexRun.Done(4, cursor = 412, applied = 0), serialAtTap = 3, overdue = overdue)
			)
			assertEquals(
				"Not pulled: lb-bot is not reachable. The mirror is unchanged.",
				indexRunLineAfterTap(LbIndexRun.Skipped(4, LbIndexSkip.LB_BOT_DOWN), serialAtTap = 3, overdue = overdue)
			)
		}
	}

	@Test
	fun aPassAlreadyRunningAtTheTapIsSaidAsRunning() {
		// Not a verdict, and true as it stands; the pull the tap asked for follows it. It is
		// never "no pull has started" while one is running.
		listOf(false, true).forEach { overdue ->
			assertEquals(
				"Pulling lb-bot's index…",
				indexRunLineAfterTap(LbIndexRun.Running(3), serialAtTap = 3, overdue = overdue)
			)
		}
	}

	@Test
	fun withNoTapTheLineIsTheLastPulls() {
		listOf(
			LbIndexRun.Idle,
			LbIndexRun.Running(2),
			LbIndexRun.Done(2, cursor = 9, applied = 1),
			LbIndexRun.Failed(2, "the hub could not be reached")
		).forEach { run ->
			assertEquals(indexRunLine(run), indexRunLineAfterTap(run, serialAtTap = null, overdue = false))
		}
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

	// --- whether there is an lb-bot layer at all --------------------------------------------

	@Test
	fun theSectionSaysWhichThingIsMissing() {
		val noHub = "lb-bot is reached through the hub. Set the hub up above to see its data here."
		assertEquals(noHub, lbLayerLine(hubSet = false, hubSaysNoLbBot = false))
		// A hub that is switched off now: whatever it said while it was on no longer matters.
		assertEquals(noHub, lbLayerLine(hubSet = false, hubSaysNoLbBot = true))
		// A hub that IS set up and says it proxies no lb-bot is not told to "set the hub up".
		assertEquals(
			"This hub says it has no lb-bot set up, so there is nothing to show here.",
			lbLayerLine(hubSet = true, hubSaysNoLbBot = true)
		)
		// The layer is there: the lists speak.
		assertNull(lbLayerLine(hubSet = true, hubSaysNoLbBot = false))
	}

	// --- what a list fed by lb-bot is ------------------------------------------------------

	@Test
	fun aListIsOnlyReadingWhileARequestIsInFlightAndNothingIsLoaded() {
		assertEquals(LbPanelData.READING, panelData(hubHeard = true, supported = true, reading = true, loaded = false, readFailed = false))
		// Not loading, nothing loaded: lb-bot could not be asked. Never "Loading…".
		assertEquals(LbPanelData.UNREACHABLE, panelData(hubHeard = true, supported = true, reading = false, loaded = false, readFailed = true))
		assertEquals(LbPanelData.UNREACHABLE, panelData(hubHeard = true, supported = true, reading = false, loaded = false, readFailed = false))
	}

	@Test
	fun aListIsReadingUntilItsFirstReadStartsAndWhileAnyReadIsOut() {
		// Before the first read has started the list is about to be asked for: "reading", not
		// "unreachable" for the frame in between.
		assertTrue(panelReading(inFlight = 0, readDue = true))
		// Two reads at once (the panel opening, and "Sync now"): the first to finish must not
		// end the state for the one still out.
		assertTrue(panelReading(inFlight = 2, readDue = false))
		assertTrue(panelReading(inFlight = 1, readDue = false))
		assertFalse(panelReading(inFlight = 0, readDue = false))
	}

	@Test
	fun aLoadedListStaysOnScreenAndSaysWhenItIsTheLastCopy() {
		assertEquals(LbPanelData.CURRENT, panelData(hubHeard = true, supported = true, reading = false, loaded = true, readFailed = false))
		assertEquals(LbPanelData.STALE, panelData(hubHeard = true, supported = true, reading = false, loaded = true, readFailed = true))
		// A re-read in flight does not turn a list that is on screen back into "reading".
		assertEquals(LbPanelData.CURRENT, panelData(hubHeard = true, supported = true, reading = true, loaded = true, readFailed = false))
		assertEquals(LbPanelData.STALE, panelData(hubHeard = true, supported = true, reading = true, loaded = true, readFailed = true))
	}

	@Test
	fun anOlderHubOrLbBotIsUnsupportedWhateverElseIsTrue() {
		assertEquals(LbPanelData.UNSUPPORTED, panelData(hubHeard = true, supported = false, reading = true, loaded = true, readFailed = false))
		assertEquals(LbPanelData.UNSUPPORTED, panelData(hubHeard = true, supported = false, reading = false, loaded = false, readFailed = true))
	}

	@Test
	fun aHubThatHasNotSpokenIsNeverCalledOlder() {
		// A cold start with the hub out of reach: no advert and no status answer in this process,
		// so nothing has said the route is missing. "Older" is a verdict on a hub that answered.
		fun silent(reading: Boolean, loaded: Boolean, readFailed: Boolean = false) =
			panelData(hubHeard = false, supported = false, reading = reading, loaded = loaded, readFailed = readFailed)
		// The panel is asking the hub: that is a read in flight.
		assertEquals(LbPanelData.READING, silent(reading = true, loaded = false))
		// It asked and nothing answered, and nothing was loaded before.
		assertEquals(LbPanelData.UNREACHABLE, silent(reading = false, loaded = false))
		// The copy restored from this device's cache stays on screen, and is called the last copy
		// once nothing is asking any more — but not while the question is still out.
		assertEquals(LbPanelData.CURRENT, silent(reading = true, loaded = true))
		assertEquals(LbPanelData.STALE, silent(reading = false, loaded = true))
		assertEquals(LbPanelData.STALE, silent(reading = true, loaded = true, readFailed = true))
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
			overrideWriteLine(ok(LbOverrideWrite(ok = true, taskId = "task-1")), removing = false)
		)
		// A blank task id is lb-bot deciding not to scan; the panel starts none and claims none.
		assertEquals("Saved.", overrideWriteLine(ok(LbOverrideWrite(ok = true, taskId = "")), removing = false))
	}

	@Test
	fun aRefusedWriteIsSaidInLbBotsWords() {
		assertEquals(
			"Not saved: A download for this artist is running.",
			overrideWriteLine(failed(LbBotManager.LbError.Rejected(409, "A download for this artist is running")), removing = false)
		)
		assertEquals(
			"Not saved: the request failed (400).",
			overrideWriteLine(failed(LbBotManager.LbError.Rejected(400, "")), removing = false)
		)
		assertEquals(
			"Not saved: lb-bot is busy or unreachable.",
			overrideWriteLine(failed(LbBotManager.LbError.Busy), removing = false)
		)
		assertEquals(
			"Not saved: the hub could not be reached.",
			overrideWriteLine(failed(unreachable), removing = false)
		)
		// lb-bot's own full stop is not doubled.
		assertEquals(
			"Not saved: MusicBrainz has no such artist.",
			overrideWriteLine(failed(LbBotManager.LbError.Rejected(404, "MusicBrainz has no such artist.")), removing = false)
		)
	}

	@Test
	fun anOlderLbBotIsNamedNotShownAsAnError() {
		assertEquals(
			"This lb-bot is older and can't store a MusicBrainz match yet. Nothing was changed.",
			overrideWriteLine(failed(LbBotManager.LbError.RouteUnknown), removing = false)
		)
	}

	@Test
	fun aRemoveIsWordedAsARemove() {
		assertEquals(
			"Removed. lb-bot is rescanning this artist.",
			overrideWriteLine(ok(LbOverrideWrite(ok = true, taskId = "task-1")), removing = true)
		)
		assertEquals("Removed.", overrideWriteLine(ok(LbOverrideWrite(ok = true, taskId = "")), removing = true))
		// A remove that failed removed nothing: "Not saved" is the wrong sentence for it.
		assertEquals(
			"Not removed: the hub could not be reached.",
			overrideWriteLine(failed(unreachable), removing = true)
		)
		assertEquals(
			"Not removed: lb-bot is busy or unreachable.",
			overrideWriteLine(failed(LbBotManager.LbError.Busy), removing = true)
		)
		assertEquals(
			"Not removed: There is no override for this artist.",
			overrideWriteLine(
				failed(LbBotManager.LbError.Rejected(404, "There is no override for this artist.")),
				removing = true
			)
		)
		assertEquals(
			"This lb-bot is older and stores no MusicBrainz matches, so there was nothing to remove.",
			overrideWriteLine(failed(LbBotManager.LbError.RouteUnknown), removing = true)
		)
	}

	// --- listening history, in words -------------------------------------------------------

	@Test
	fun theListeningStatusIsSaidForEveryState() {
		fun line(phase: ListeningPhase, count: Int = 0, lastSync: String = "") =
			listeningStatusLine(hubHeard = true, advertised = true, phase = phase, count = count, lastSync = lastSync)
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
			listeningStatusLine(hubHeard = true, advertised = false, phase = ListeningPhase.Ready, count = 5, lastSync = "x")
		)
	}

	@Test
	fun aHubThatHasNotSpokenIsNotSaidToLackListening() {
		fun silent(advertised: Boolean, phase: ListeningPhase, count: Int = 0) =
			listeningStatusLine(hubHeard = false, advertised = advertised, phase = phase, count = count, lastSync = "")
		// "Not advertised" with no advert heard may be the default of an install that never
		// heard one. While the hub is being asked that is a read; after, lb-bot is out of reach.
		assertEquals("Asking lb-bot…", silent(advertised = false, phase = ListeningPhase.Loading))
		assertEquals(
			"lb-bot is needed for this and is not reachable right now.",
			silent(advertised = false, phase = ListeningPhase.Unreachable)
		)
		assertEquals(
			"lb-bot is needed for this and is not reachable right now.",
			silent(advertised = false, phase = ListeningPhase.Ready, count = 5)
		)
		// A remembered "advertised" lets the status be asked, and the phase then speaks as usual.
		assertEquals(
			"lb-bot is needed for this and is not reachable right now.",
			silent(advertised = true, phase = ListeningPhase.Unreachable)
		)
		assertEquals("Ready: 5 plays stored.", silent(advertised = true, phase = ListeningPhase.Ready, count = 5))
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
		// "Not seen to finish", which is all that is known — also when there was no baseline.
		assertEquals(
			"lb-bot was asked to pull. It has not been seen to finish yet.",
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
		assertEquals("Synced: 120 plays stored.", listeningSyncLine(ListeningSyncOutcome.Finished(120)))
	}

	@Test
	fun withoutABaselineNothingIsCalledFinishedOrFailed() {
		// The status could not be read just before the request. A sync time that is there now
		// may have been there all along, and so may an error: neither is this tap's.
		assertEquals(
			ListeningSyncOutcome.Waiting,
			listeningSyncOutcome(null, asked, ok(status("ready", count = 5, lastSyncAt = 1_500.0)))
		)
		assertEquals(
			ListeningSyncOutcome.Waiting,
			listeningSyncOutcome(null, asked, ok(status("error", error = "ListenBrainz said 503")))
		)
		// What lb-bot is doing right now is a fact with or without a baseline.
		assertEquals(
			ListeningSyncOutcome.Importing(40, 900),
			listeningSyncOutcome(null, asked, ok(status("importing", count = 40, total = 900)))
		)
		assertEquals(ListeningSyncOutcome.Off, listeningSyncOutcome(null, asked, ok(status("off"))))
		assertEquals(ListeningSyncOutcome.AskedUnread, listeningSyncOutcome(null, asked, failed(unreachable)))
	}

	@Test
	fun aSyncThatFinishedBeforeTheTapIsNotCreditedToIt() {
		// The panel last read the status minutes ago (last sync at 1000). A background sync
		// finished at 1400, then "Sync now" was tapped. The baseline is the status read
		// immediately before the request — 1400 — so the same 1400 afterwards is no evidence.
		val justBefore = status("ready", count = 110, lastSyncAt = 1_400.0)
		assertEquals(ListeningSyncOutcome.Waiting, listeningSyncOutcome(justBefore, asked, ok(justBefore)))
		// The pull this tap asked for finishes: the time moves past the baseline.
		assertEquals(
			ListeningSyncOutcome.Finished(120),
			listeningSyncOutcome(justBefore, asked, ok(status("ready", count = 120, lastSyncAt = 1_500.0)))
		)
	}

	@Test
	fun aStatusReadAfterTheRequestCanStandInForAMissingBaseline() {
		val firstSince = status("ready", count = 110, lastSyncAt = 1_400.0)
		// No baseline before the request: the first status read since becomes one. A sync that
		// finishes after THAT read finished after the request too.
		assertEquals(firstSince, listeningSyncBaseline(null, ok(firstSince)))
		assertEquals(
			ListeningSyncOutcome.Finished(120),
			listeningSyncOutcome(
				listeningSyncBaseline(null, ok(firstSince)), asked, ok(status("ready", count = 120, lastSyncAt = 1_500.0))
			)
		)
		// A baseline read before the request is never replaced.
		val before = status("ready", lastSyncAt = 1_000.0)
		assertEquals(before, listeningSyncBaseline(before, ok(firstSince)))
		// A read that failed, or answered nothing, is no baseline.
		assertNull(listeningSyncBaseline(null, failed(unreachable)))
		assertNull(listeningSyncBaseline(null, ok(status(""))))
		assertNull(listeningSyncBaseline(null, null))
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
	fun aSparseAnswerIsAnAboutButNotContent() {
		// `found: false` is a well-formed About — lb-bot answered — and it carries no text.
		val sparse = aboutBodyOrNull("""{"found":false,"summary":"","paragraphs":[],"links":[]}""")
		assertEquals(LbMeta(found = false), sparse)
		assertEquals(LbMeta(found = false), aboutBodyOrNull("""{"found":false}"""))
		val full = aboutBodyOrNull("""{"found":true,"summary":"An English rock band.","paragraphs":["An English rock band."]}""")
		assertEquals(true, full?.found)
		assertEquals("An English rock band.", full?.summary)
	}

	private val about = LbMeta(found = true, summary = "An English rock band.", paragraphs = listOf("An English rock band."))
	private val newer = about.copy(summary = "An English rock band formed in 1985.")
	private val sparse = LbMeta(found = false)

	@Test
	fun whatTheRequestCameTo() {
		assertEquals(LbMetaRefresh.Answered(LbMeta(found = true, summary = "x")), metaRefreshOf(ok("""{"found":true,"summary":"x"}""")))
		assertEquals(LbMetaRefresh.Answered(sparse), metaRefreshOf(ok("""{"found":false}""")))
		assertEquals(LbMetaRefresh.Nothing, metaRefreshOf(ok("{}")))
		assertEquals(LbMetaRefresh.Nothing, metaRefreshOf(ok("not json")))
		assertEquals(LbMetaRefresh.Unreachable, metaRefreshOf(failed(unreachable)))
		assertEquals(LbMetaRefresh.Unreachable, metaRefreshOf(failed(LbBotManager.LbError.Busy)))
		assertEquals(LbMetaRefresh.Unreachable, metaRefreshOf(failed(LbBotManager.LbError.NotConfigured)))
		assertEquals(LbMetaRefresh.Refused(404), metaRefreshOf(failed(LbBotManager.LbError.RouteUnknown)))
	}

	@Test
	fun anErrorAnswerKeepsItsStatusAndLbBotsText() {
		assertEquals(
			LbMetaRefresh.Refused(500, "boom"),
			metaRefreshOf(failed(LbBotManager.LbError.Rejected(500, "boom")))
		)
		assertEquals(
			LbMetaRefresh.Refused(503, ""),
			metaRefreshOf(failed(LbBotManager.LbError.Rejected(503, "")))
		)
	}

	@Test
	fun aRetryable502IsLbBotsOwnAnswerAndTheHubsOwn502IsNotReachingIt() {
		// lb-bot: a leg of the refresh did not answer; it kept its stored About and says so.
		assertEquals(
			LbBotManager.LbError.Rejected(502, "MusicBrainz did not answer."),
			metaRefreshError(502, "MusicBrainz did not answer.", retryable = true)
		)
		// The hub: lb-bot is unreachable (502) or timed out (504). No such flag.
		assertEquals(LbBotManager.LbError.Busy, metaRefreshError(502, "lb-bot unreachable", retryable = false))
		assertEquals(LbBotManager.LbError.Busy, metaRefreshError(504, "lb-bot timed out", retryable = false))
		assertEquals(LbBotManager.LbError.Rejected(400, "rgid is required"), metaRefreshError(400, "rgid is required", false))
		assertEquals(LbBotManager.LbError.Rejected(404, ""), metaRefreshError(404, "", false))
	}

	@Test
	fun onlyAnAnswerWithContentReplacesWhatIsShown() {
		assertEquals(AboutRefreshOutcome.Updated(newer), aboutRefreshOutcome(about, LbMetaRefresh.Answered(newer)))
		assertEquals(newer, aboutAfterRefresh(about, AboutRefreshOutcome.Updated(newer)))
		// Nothing was shown, and now there is an About.
		assertEquals(AboutRefreshOutcome.Updated(about), aboutRefreshOutcome(null, LbMetaRefresh.Answered(about)))
		assertEquals(AboutRefreshOutcome.Updated(about), aboutRefreshOutcome(sparse, LbMetaRefresh.Answered(about)))
	}

	@Test
	fun foundFalseNeverWipesAnAboutThatHasContent() {
		// lb-bot answers `found: false` for a MusicBrainz or Wikipedia blip as readily as for a
		// true "nothing written". The About on screen stays, and the sentence says what is known.
		val outcome = aboutRefreshOutcome(about, LbMetaRefresh.Answered(sparse))
		assertEquals(AboutRefreshOutcome.NothingThisTime, outcome)
		assertEquals(about, aboutAfterRefresh(about, outcome))
		assertEquals(AboutRefreshNotice.NOTHING_THIS_TIME, aboutRefreshNotice(outcome))
	}

	@Test
	fun foundFalseWithNothingShownIsSaidAsNothingWritten() {
		assertEquals(AboutRefreshOutcome.NothingWritten(sparse), aboutRefreshOutcome(null, LbMetaRefresh.Answered(sparse)))
		// What was shown had no content either: the sparse answer may stand in for it.
		val linksOnly = LbMeta(found = false, links = listOf(LbMetaLink(type = "official homepage", url = "https://example.org")))
		assertEquals(
			AboutRefreshOutcome.NothingWritten(linksOnly),
			aboutRefreshOutcome(sparse, LbMetaRefresh.Answered(linksOnly))
		)
		assertEquals(linksOnly, aboutAfterRefresh(sparse, AboutRefreshOutcome.NothingWritten(linksOnly)))
		assertEquals(AboutRefreshNotice.NOTHING_WRITTEN, aboutRefreshNotice(AboutRefreshOutcome.NothingWritten(sparse)))
	}

	@Test
	fun aSparseAnswerIsNeverStoredAndAnAboutWithContentIs() {
		// Stored under the ordinary key, a sparse body replaced a good About for a month.
		assertFalse(aboutRefreshStored(LbMetaRefresh.Answered(sparse)))
		assertTrue(aboutRefreshStored(LbMetaRefresh.Answered(about)))
		assertFalse(aboutRefreshStored(LbMetaRefresh.Nothing))
		assertFalse(aboutRefreshStored(LbMetaRefresh.Unreachable))
		assertFalse(aboutRefreshStored(LbMetaRefresh.Refused(502, "MusicBrainz did not answer")))
	}

	@Test
	fun anAnswerIdenticalToWhatIsShownSaysSo() {
		val outcome = aboutRefreshOutcome(about, LbMetaRefresh.Answered(about.copy()))
		assertEquals(AboutRefreshOutcome.Same, outcome)
		assertEquals(about, aboutAfterRefresh(about, outcome))
		assertEquals(AboutRefreshNotice.SAME, aboutRefreshNotice(outcome))
		// "Refreshed" is said only of an answer with content that changed something.
		assertEquals(AboutRefreshNotice.UPDATED, aboutRefreshNotice(aboutRefreshOutcome(about, LbMetaRefresh.Answered(newer))))
	}

	@Test
	fun aFailureLeavesWhatIsShownAndSaysWhichItWas() {
		listOf(
			LbMetaRefresh.Nothing to AboutRefreshNotice.NOTHING,
			LbMetaRefresh.Unreachable to AboutRefreshNotice.UNREACHABLE,
			LbMetaRefresh.Refused(503) to AboutRefreshNotice.ERROR,
			LbMetaRefresh.Refused(502, "MusicBrainz did not answer.") to AboutRefreshNotice.ERROR_SAID
		).forEach { (refresh, notice) ->
			val outcome = aboutRefreshOutcome(about, refresh)
			assertEquals(about, aboutAfterRefresh(about, outcome))
			assertNull(aboutAfterRefresh(null, outcome))
			assertEquals(notice, aboutRefreshNotice(outcome))
		}
	}

	@Test
	fun anErrorsSentenceCarriesItsStatusAndLbBotsText() {
		// "lb-bot answered an error (502): MusicBrainz did not answer. What is shown is unchanged."
		assertEquals(
			listOf<Any>(502, "MusicBrainz did not answer"),
			aboutRefreshArgs(AboutRefreshOutcome.Refused(502, "MusicBrainz did not answer."))
		)
		// "lb-bot answered an error (503). What is shown is unchanged."
		assertEquals(listOf<Any>(503), aboutRefreshArgs(AboutRefreshOutcome.Refused(503, " ")))
		assertEquals(emptyList(), aboutRefreshArgs(AboutRefreshOutcome.Same))
		assertEquals(emptyList(), aboutRefreshArgs(AboutRefreshOutcome.Updated(about)))
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
	fun onlyIdsNotLookedUpBeforeAreLookedUp() {
		// The queue re-emits on every row a drain deletes. An id that was looked up — found or
		// not — is not asked for again.
		assertEquals(setOf("so-3"), pendingTitlesToLookUp(listOf("so-1", "so-2", "so-3"), seen = setOf("so-1", "so-2")))
		assertEquals(emptySet(), pendingTitlesToLookUp(listOf("so-1", "so-2"), seen = setOf("so-1", "so-2", "so-9")))
		// The first emission: everything, each id once however many actions name it.
		assertEquals(setOf("so-1", "al-1"), pendingTitlesToLookUp(listOf("so-1", "al-1", "so-1"), seen = emptySet()))
		assertEquals(emptySet(), pendingTitlesToLookUp(emptyList(), seen = emptySet()))
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
