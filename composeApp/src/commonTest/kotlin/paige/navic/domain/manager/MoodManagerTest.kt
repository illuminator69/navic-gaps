package paige.navic.domain.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MoodManager's pure parts (P7 Task A3): reading the hub's catalogue and a tile's answer, what an
 * older hub or a dead one does to the cached chips, the request a tile sends, and one request per
 * key however many ask at once (`MoodSingleFlightTest`, on the JVM).
 */
class MoodManagerTest {

	private val answer = """
		{"configured":true,"available":true,"ready":false,"version":"2026-10-20","extra":"ignored",
		 "moods":[
		  {"id":"relax","label":"Relax","group":"home","filter":true,"maxSeconds":480,"depth":300,
		   "variants":[{"id":"acoustic","title":"Acoustic evenings","hue":[32,12],"new":1}]},
		  {"id":"sleep","label":"Sleep","group":"home","filter":false,"maxSeconds":null,"depth":200,"variants":[]},
		  {"id":"pump-up","label":"Pump-up","group":"extra","filter":true,"maxSeconds":null,"depth":300,"variants":[]}
		 ]}
	""".trimIndent()

	@Test
	fun theHubsCatalogueIsReadWithUnknownFieldsIgnored() {
		val c = assertNotNull(parseMoodCatalogue(answer))
		assertTrue(c.available)
		assertFalse(c.ready)
		assertEquals("2026-10-20", c.version)
		assertEquals(listOf("relax", "sleep", "pump-up"), c.moods.map { it.id })
		val relax = c.moods[0]
		assertEquals(480, relax.maxSeconds)
		assertTrue(relax.filter)
		assertEquals(listOf(32, 12), relax.variants.single().hue)
		assertNull(c.moods[1].maxSeconds)
		assertEquals(200, c.moods[1].depth)
	}

	@Test
	fun aCatalogueWithoutMoodsIsAnEmptyOne() {
		val c = assertNotNull(parseMoodCatalogue("""{"configured":false,"available":false}"""))
		assertTrue(c.moods.isEmpty())
		assertFalse(c.chipsVisible())
		assertNull(parseMoodCatalogue("not json"))
	}

	@Test
	fun anOlderHubTakesTheChipsAwayAndADeadOneKeepsThem() {
		val cached = assertNotNull(parseMoodCatalogue(answer))
		assertTrue(cached.chipsVisible())
		val absent = catalogueAfterRefresh(cached, catalogueOutcome(true, MoodHttpReply(404, "{}")))
		assertFalse(absent.chipsVisible())
		assertEquals(cached.moods, absent.moods)
		val dead = catalogueAfterRefresh(cached, catalogueOutcome(true, null))
		assertEquals(cached, dead)
		val fiveHundred = catalogueAfterRefresh(cached, catalogueOutcome(true, MoodHttpReply(503, "{}")))
		assertEquals(cached, fiveHundred)
		val noHub = catalogueAfterRefresh(cached, catalogueOutcome(false, null))
		assertFalse(noHub.chipsVisible())
		val fresh = catalogueAfterRefresh(MoodCatalogue.NONE, catalogueOutcome(true, MoodHttpReply(200, answer)))
		assertEquals(cached, fresh)
	}

	@Test
	fun aStoredCatalogueComesBackAndAnUnreadableOneIsNone() {
		assertEquals(MoodCatalogue.NONE, restoreCatalogue(""))
		assertEquals(MoodCatalogue.NONE, restoreCatalogue("{broken"))
		assertEquals(parseMoodCatalogue(answer), restoreCatalogue(answer))
	}

	@Test
	fun aTilesAnswer() {
		val ok = tileIdsOutcome(200, """{"mood":"relax","variant":"acoustic","depth":300,"version":"v","ids":["a","b"]}""", "relax", "acoustic")
		assertEquals(TileIdsResult.Ok(listOf("a", "b"), "v"), ok)
		assertEquals(TileIdsResult.Absent, tileIdsOutcome(404, """{"code":"variant_unknown"}""", "relax", "x"))
		assertEquals(TileIdsResult.Unavailable, tileIdsOutcome(503, """{"fallback":true}""", "relax", "acoustic"))
		assertEquals(TileIdsResult.Unavailable, tileIdsOutcome(200, """{"mood":"relax","variant":"acoustic","ids":"no"}""", "relax", "acoustic"))
		assertEquals(TileIdsResult.Unavailable, tileIdsOutcome(200, """{"mood":"relax","variant":"acoustic"}""", "relax", "acoustic"))
		// An answer for another key (a stale one) is not this tile's.
		assertEquals(
			TileIdsResult.Unavailable,
			tileIdsOutcome(200, """{"mood":"sleep","variant":"acoustic","ids":["a"]}""", "relax", "acoustic")
		)
	}

	@Test
	fun aTileRequestCarriesTheTokenAndBothIds() {
		val r = assertNotNull(tileRequest("http://hub:4790", "tok", "feel-good", "soul-funk"))
		assertEquals("http://hub:4790/sonic/mood/list", r.url)
		assertEquals("Bearer tok", r.authorization)
		assertEquals("""{"mood":"feel-good","variant":"soul-funk"}""", r.body)
		assertNull(tileRequest(null, "tok", "a", "b"))
		val c = assertNotNull(catalogueRequest("http://hub:4790", "tok"))
		assertEquals("http://hub:4790/sonic/mood/catalogue", c.url)
		assertNull(c.body)
	}
}
