package paige.navic.ui.screens.search

import paige.navic.domain.manager.LbAlbumCandidate
import paige.navic.domain.models.DomainArtist

/*
 * P3: the Search page's state and the one pure function that moves it (`SearchViewModelReducerTest`).
 * Answers arrive out of order: the library for "pale k" can land after "pale kiosk" was typed, and a
 * MusicBrainz lookup takes a second or more. Every answer carries the query it was for, and one for
 * any other query is dropped, so a slow answer never lands under a newer query.
 */

data class SearchUi(
	val phase: SearchPhase = SearchPhase.Browse,
	val hits: LibraryHits = LibraryHits.None,
	val artists: Lookup<ExternalArtist> = Lookup.Idle,
	val albums: Lookup<LbAlbumCandidate> = Lookup.Idle,
	/** The settled query (after the debounce) these answers are for. */
	val query: String = "",
	/** Whether the library has answered for [query]; until then "No results" is not said. */
	val librarySettled: Boolean = true,
	val online: Boolean = true,
	val lbConfigured: Boolean = false
)

sealed interface SearchEvent {
	/** Where the lookups may go: the connection and whether the lb-bot layer is on. */
	data class Environment(val online: Boolean, val lbConfigured: Boolean) : SearchEvent
	data class QueryChanged(val query: String) : SearchEvent
	data class LibraryLanded(val query: String, val hits: LibraryHits) : SearchEvent
	/** MusicBrainz artists the library holds (by MBID): drawn in the library section. */
	data class OwnedArtistsLanded(val query: String, val artists: List<DomainArtist>) : SearchEvent
	data class ArtistsLanded(val query: String, val result: Lookup<ExternalArtist>) : SearchEvent
	data class AlbumsLanded(val query: String, val result: Lookup<LbAlbumCandidate>) : SearchEvent
	/** The "Couldn't reach lb-bot" pill was tapped: the failed lookups are asked again. */
	data class RetryLookups(val query: String) : SearchEvent
}

fun reduce(ui: SearchUi, event: SearchEvent): SearchUi {
	val next = when (event) {
		is SearchEvent.Environment -> ui.copy(online = event.online, lbConfigured = event.lbConfigured)
		is SearchEvent.QueryChanged -> {
			if (event.query.isBlank()) {
				SearchUi(online = ui.online, lbConfigured = ui.lbConfigured)
			} else {
				val ask = ui.online && ui.lbConfigured && externalLookupWanted(event.query)
				ui.copy(
					query = event.query,
					librarySettled = false,
					// What is shown stays until the new answer lands; from Browse there is nothing.
					hits = if (ui.query.isBlank()) LibraryHits.None else ui.hits,
					artists = if (ask) Lookup.Looking else Lookup.Idle,
					albums = if (ask) Lookup.Looking else Lookup.Idle
				)
			}
		}
		is SearchEvent.LibraryLanded ->
			if (event.query != ui.query) ui else ui.copy(hits = event.hits, librarySettled = true)
		is SearchEvent.OwnedArtistsLanded ->
			if (event.query != ui.query || event.artists.isEmpty()) ui
			else ui.copy(hits = mergeHits(ui.hits, LibraryHits.None.copy(artists = event.artists)))
		is SearchEvent.ArtistsLanded ->
			if (event.query != ui.query || ui.artists !is Lookup.Looking) ui else ui.copy(artists = event.result)
		is SearchEvent.AlbumsLanded ->
			if (event.query != ui.query || ui.albums !is Lookup.Looking) ui else ui.copy(albums = event.result)
		is SearchEvent.RetryLookups ->
			if (event.query != ui.query) ui
			else ui.copy(
				artists = if (ui.artists is Lookup.Failed) Lookup.Looking else ui.artists,
				albums = if (ui.albums is Lookup.Failed) Lookup.Looking else ui.albums
			)
	}
	return next.copy(
		phase = searchPhase(
			next.query, next.online, next.lbConfigured, next.hits, next.artists, next.albums, next.librarySettled
		)
	)
}
