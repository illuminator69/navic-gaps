package paige.navic.ui.screens.search

import paige.navic.domain.manager.LbAlbumCandidate
import paige.navic.domain.manager.LbArtistCandidate
import paige.navic.domain.models.DomainArtist
import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * P3: answers arrive out of order and some never arrive. Every one carries its query, and one for a
 * query that is no longer the field's is dropped: a slow lookup for "pale k" never lands under
 * "pale kiosk". A failed lookup is said, and only a lookup that is out can be answered.
 */
class SearchViewModelReducerTest {

	private val online = reduce(SearchUi(), SearchEvent.Environment(online = true, lbConfigured = true))
	private val hits = LibraryHits.None.copy(artists = listOf(DomainArtist("nd1", "Pale Kiosk")))
	private val album = LbAlbumCandidate(rgid = "rg", title = "Kiosk Hours", artist = "Pale Kiosk")

	private fun typed(query: String, from: SearchUi = online) = reduce(from, SearchEvent.QueryChanged(query))

	@Test
	fun aStaleAnswerIsDropped() {
		val ui = typed("pale kiosk", typed("pale k"))
		val after = reduce(ui, SearchEvent.LibraryLanded("pale k", hits))
		assertEquals(ui, after)
		val after2 = reduce(ui, SearchEvent.AlbumsLanded("pale k", Lookup.Done(listOf(album))))
		assertEquals(Lookup.Looking, after2.albums)
		val after3 = reduce(ui, SearchEvent.ArtistsLanded("pale k", Lookup.Failed))
		assertEquals(Lookup.Looking, after3.artists)
	}

	@Test
	fun theCurrentAnswerLands() {
		val ui = reduce(typed("pale kiosk"), SearchEvent.LibraryLanded("pale kiosk", hits))
		assertEquals(hits, ui.hits)
		assertEquals(SearchPhase.Results, ui.phase)
		val done = reduce(ui, SearchEvent.AlbumsLanded("pale kiosk", Lookup.Done(listOf(album))))
		assertEquals(Lookup.Done(listOf(album)), done.albums)
	}

	@Test
	fun aFailedLookupReplacesLooking() {
		val ui = reduce(typed("pale kiosk"), SearchEvent.ArtistsLanded("pale kiosk", Lookup.Failed))
		assertEquals(Lookup.Failed, ui.artists)
		assertEquals(Lookup.Looking, ui.albums)
	}

	@Test
	fun aRetryAsksTheFailedOnesAgain() {
		var ui = typed("pale kiosk")
		ui = reduce(ui, SearchEvent.LibraryLanded("pale kiosk", LibraryHits.None))
		ui = reduce(ui, SearchEvent.ArtistsLanded("pale kiosk", Lookup.Failed))
		ui = reduce(ui, SearchEvent.AlbumsLanded("pale kiosk", Lookup.Done(emptyList())))
		assertEquals(SearchPhase.Results, ui.phase)
		ui = reduce(ui, SearchEvent.RetryLookups("pale kiosk"))
		assertEquals(Lookup.Looking, ui.artists)
		assertEquals(Lookup.Done(emptyList()), ui.albums)
		ui = reduce(ui, SearchEvent.ArtistsLanded("pale kiosk", Lookup.Done(emptyList())))
		assertEquals(SearchPhase.NoResults, ui.phase)
	}

	@Test
	fun aNewQueryLooksUpOnlyWhenWantedAndConfigured() {
		assertEquals(Lookup.Looking, typed("abc").artists)
		assertEquals(Lookup.Looking, typed("abc").albums)
		assertEquals(Lookup.Idle, typed("ab").artists)
		val off = reduce(SearchUi(), SearchEvent.Environment(online = true, lbConfigured = false))
		assertEquals(Lookup.Idle, typed("abc", off).artists)
		assertEquals(Lookup.Idle, typed("abc", off).albums)
		val offline = reduce(SearchUi(), SearchEvent.Environment(online = false, lbConfigured = true))
		assertEquals(Lookup.Idle, typed("abc", offline).artists)
	}

	@Test
	fun aNewQueryIsNeverNoResultsBeforeTheLibraryAnswers() {
		val off = reduce(SearchUi(), SearchEvent.Environment(online = true, lbConfigured = false))
		val ui = typed("zzqvx", off)
		assertEquals(SearchPhase.Results, ui.phase)
		assertEquals(SearchPhase.NoResults, reduce(ui, SearchEvent.LibraryLanded("zzqvx", LibraryHits.None)).phase)
	}

	@Test
	fun clearingTheFieldIsBrowseAndLateAnswersAreIgnored() {
		val ui = typed("", typed("pale kiosk"))
		assertEquals(SearchPhase.Browse, ui.phase)
		assertEquals(LibraryHits.None, ui.hits)
		assertEquals(Lookup.Idle, ui.artists)
		val late = reduce(ui, SearchEvent.LibraryLanded("pale kiosk", hits))
		assertEquals(ui, late)
		val late2 = reduce(ui, SearchEvent.ArtistsLanded("pale kiosk", Lookup.Done(emptyList())))
		assertEquals(ui, late2)
	}

	@Test
	fun ownedArtistsJoinTheLibrarySection() {
		var ui = reduce(typed("pale kiosk"), SearchEvent.LibraryLanded("pale kiosk", hits))
		ui = reduce(ui, SearchEvent.OwnedArtistsLanded("pale kiosk", listOf(DomainArtist("nd2", "Pale Kiosk"), DomainArtist("nd1", "Pale Kiosk"))))
		assertEquals(listOf("nd1", "nd2"), ui.hits.artists.map { it.id })
		val external = ExternalArtist(LbArtistCandidate(mbid = "m", name = "Pale Kiosk"), null)
		ui = reduce(ui, SearchEvent.ArtistsLanded("pale kiosk", Lookup.Done(listOf(external))))
		assertEquals(Lookup.Done(listOf(external)), ui.artists)
	}

	@Test
	fun goingOfflineShowsDownloadedMusicOnly() {
		var ui = reduce(typed("pale kiosk"), SearchEvent.LibraryLanded("pale kiosk", LibraryHits.None))
		ui = reduce(ui, SearchEvent.Environment(online = false, lbConfigured = true))
		assertEquals(SearchPhase.OfflineNone, ui.phase)
	}
}
