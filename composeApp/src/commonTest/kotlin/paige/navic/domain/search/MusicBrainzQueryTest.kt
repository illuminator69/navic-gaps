package paige.navic.domain.search

import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * P3: the unowned half of Search tolerates typos too, through MusicBrainz's own fuzzy terms. `q`
 * reaches MusicBrainz verbatim as Lucene, so a word of four or more letters gets "~1" (one edit) and
 * nothing else does: probed live on 2026-10-20, "boy~ genuis~" (distance 2, on a three-letter word)
 * put Bob Dylan first, while "boygenuis~1" found boygenius. A word with a symbol is escaped, not
 * fuzzed; input that is already Lucene is left alone. No request is added: the same one lookup.
 */
class MusicBrainzQueryTest {

	@Test
	fun fuzzesWordsOfFourOrMore() {
		assertEquals("radioheed~1", mbFuzzyQuery("radioheed"))
		assertEquals("kendrik~1 lamar~1", mbFuzzyQuery("kendrik lamar"))
		assertEquals("boygenuis~1", mbFuzzyQuery("boygenuis"))
		assertEquals("the strokes~1", mbFuzzyQuery("the strokes"))
		assertEquals("mgmt~1", mbFuzzyQuery("mgmt"))
		assertEquals("daft~1 pnk", mbFuzzyQuery("daft pnk"))
	}

	@Test
	fun shortWordsStayPlain() {
		assertEquals("boy genius~1", mbFuzzyQuery("boy genius"))
		assertEquals("abc", mbFuzzyQuery("abc"))
	}

	@Test
	fun symbolsAreEscapedNotFuzzed() {
		assertEquals("AC\\/DC", mbFuzzyQuery("AC/DC"))
		assertEquals("p\\!nk", mbFuzzyQuery("p!nk"))
		assertEquals("sunn~1 \\(o\\)", mbFuzzyQuery("sunn (o)"))
	}

	@Test
	fun nonLatinIsUnchanged() {
		assertEquals("米津玄師", mbFuzzyQuery("米津玄師"))
		assertEquals("björk~1", mbFuzzyQuery("björk"))
	}

	@Test
	fun blankIsEmpty() {
		assertEquals("", mbFuzzyQuery(""))
		assertEquals("", mbFuzzyQuery("   \t "))
	}

	@Test
	fun luceneIsLeftAlone() {
		assertEquals("artist:\"Daft Punk\"", mbFuzzyQuery("artist:\"Daft Punk\""))
		assertEquals("radiohead~2", mbFuzzyQuery("radiohead~2"))
		assertEquals("\"pale kiosk\"", mbFuzzyQuery("\"pale kiosk\""))
	}

	@Test
	fun collapsesWhitespace() {
		assertEquals("pale~1 kiosk~1", mbFuzzyQuery("  pale   kiosk "))
	}
}
