package paige.navic.ui.screens.album

import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.find_it_service_apple_music
import navic.composeapp.generated.resources.find_it_service_bandcamp
import navic.composeapp.generated.resources.find_it_service_spotify
import navic.composeapp.generated.resources.find_it_service_youtube_music
import paige.navic.domain.manager.LbFindItLink
import paige.navic.domain.manager.LbMeta
import paige.navic.domain.manager.lbWireJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Worbus P1, N4: a missing album's page shows where to find the record, from lb-bot's `findIt` on
 * `/lb/meta/album`: links MusicBrainz confirms first, then searches, each search named for its service
 * ("Search Bandcamp"). No `findIt` (an older lb-bot, MusicBrainz down, no names to search) is no
 * section at all.
 */
class FindItRowTest {
	private fun meta(findIt: String?) = lbWireJson.decodeFromString(
		LbMeta.serializer(),
		"""{"found": true, "summary": "x"${if (findIt != null) """, "findIt": $findIt""" else ""}}"""
	)

	@Test
	fun find_it_confirmed_before_search() {
		val links = meta(
			"""[{"service":"bandcamp","kind":"search","url":"https://bandcamp.com/search?q=a"},
			   {"service":"spotify","kind":"confirmed","url":"https://open.spotify.com/album/1"},
			   {"service":"qobuz","kind":"search","url":"https://www.qobuz.com/search?q=a"},
			   {"service":"tidal","kind":"confirmed","url":"https://tidal.com/album/2"}]"""
		).findIt
		val entries = findItEntries(links)
		assertEquals(listOf("spotify", "tidal", "bandcamp", "qobuz"), entries.map { it.service })
		assertEquals(listOf(true, true, false, false), entries.map { it.confirmed })
		// Each opens its own URL as lb-bot sent it.
		assertEquals("https://open.spotify.com/album/1", entries.first().url)
	}

	@Test
	fun find_it_absent_renders_nothing() {
		assertNull(meta(null).findIt)
		assertTrue(findItEntries(null).isEmpty())
		assertTrue(findItEntries(emptyList()).isEmpty())
		// A link with no URL, or a kind this build does not know, is not drawn.
		assertTrue(findItEntries(listOf(LbFindItLink("spotify", "confirmed", ""), LbFindItLink("deezer", "maybe", "https://x"))).isEmpty())
	}

	@Test
	fun find_it_search_labels_name_the_service() {
		val entries = findItEntries(
			listOf(
				LbFindItLink("bandcamp", "search", "https://bandcamp.com/search?q=a"),
				LbFindItLink("apple-music", "search", "https://music.apple.com/search?term=a"),
				LbFindItLink("youtube-music", "search", "https://music.youtube.com/search?q=a"),
				LbFindItLink("spotify", "confirmed", "https://open.spotify.com/album/1"),
				LbFindItLink("other", "confirmed", "https://www.discogs.com/release/9")
			)
		)
		assertEquals(
			listOf(
				FindItLabel.Name(Res.string.find_it_service_spotify),
				FindItLabel.Host("discogs.com"),
				FindItLabel.Search(Res.string.find_it_service_bandcamp),
				FindItLabel.Search(Res.string.find_it_service_apple_music),
				FindItLabel.Search(Res.string.find_it_service_youtube_music)
			),
			entries.map { it.label }
		)
	}
}
