package paige.navic.domain.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * P3: the library half of Search tolerates typos. Navidrome's search3 and Room's LIKE both find
 * nothing for "radioheed", so a local pass scores every name in the library against what was typed.
 * Tokens of one to three letters match exactly (a typo allowance there matches half the library:
 * "boy" would find "Bob Dylan"), four to six allow one edit, seven or more two; a transposition is one
 * edit. The last word may be unfinished. Non-Latin words match as substrings, never fuzzily.
 */
class FuzzyMatchTest {

	@Test
	fun normalisesCaseAccentsAndSymbols() {
		assertEquals("bjork", normalizeForSearch("Björk"))
		assertEquals("beyonce and jay z", normalizeForSearch("Beyoncé & Jay-Z"))
		assertEquals("pale kiosk", normalizeForSearch("  Pale \t  KIOSK  "))
		assertEquals("dont stop", normalizeForSearch("Don’t Stop"))
		assertEquals("米津玄師", normalizeForSearch("米津玄師"))
	}

	@Test
	fun tiersDecreaseStrictly() {
		val exact = fuzzyScore("kiosk", "Kiosk")
		val prefix = fuzzyScore("kiosk", "Kiosk Hours")
		val inside = fuzzyScore("kiosk", "Low Kiosk")
		val typo = fuzzyScore("kiosc", "Kiosk")
		assertEquals(1000, exact)
		assertEquals(900, prefix)
		assertTrue(prefix > inside, "prefix $prefix > inside $inside")
		assertTrue(inside > typo, "inside $inside > typo $typo")
		assertTrue(typo > 0)
	}

	@Test
	fun typosThatMustMatch() {
		listOf(
			"pale kiosc" to "Pale Kiosk",
			"radioheed" to "Radiohead",
			"beetles" to "The Beatles",
			"kendrik lamar" to "Kendrick Lamar",
			"boygenuis" to "boygenius",
			"bjork" to "Björk",
			"kiosk pale" to "Pale Kiosk",
			"boy genius" to "boygenius"
		).forEach { (q, c) ->
			assertTrue(fuzzyScore(q, c) > 0, "\"$q\" should find \"$c\"")
		}
	}

	@Test
	fun shortTokensMatchExactlyOnly() {
		assertEquals(0, fuzzyScore("boy", "Bob Dylan"))
		// The documented limit: "pnk" is three letters, so it gets no edit.
		assertEquals(0, fuzzyScore("daft pnk", "Daft Punk"))
		assertEquals(0, fuzzyScore("zzqvx", "Pale Kiosk"))
		assertEquals(0, fuzzyScore("pale", "Kiosk Hours"))
	}

	@Test
	fun allowanceGrowsWithLength() {
		// four to six letters: one edit
		assertTrue(fuzzyScore("kiosc", "Kiosk") > 0)
		assertEquals(0, fuzzyScore("kiocc", "Kiosk"))
		// seven or more: two
		assertTrue(fuzzyScore("radiohxxd", "Radiohead") > 0)
		assertEquals(0, fuzzyScore("radxohxxd", "Radiohead"))
	}

	@Test
	fun oneLetterMatchesOnlyAStart() {
		assertTrue(fuzzyScore("a", "Abba") > 0)
		assertEquals(0, fuzzyScore("a", "Low Apple"))
		assertEquals(0, fuzzyScore("a", "Kiosk"))
	}

	@Test
	fun theLastTokenMayBeUnfinished() {
		assertTrue(fuzzyScore("pale kio", "Pale Kiosk") > 0)
		assertTrue(fuzzyScore("pale kioz", "Pale Kiosk") > 0)
		// an earlier token is a whole word: no fuzzy prefix there
		assertEquals(0, fuzzyScore("kioz pale", "Pale Kiosk"))
	}

	@Test
	fun nonLatinIsSubstringOnly() {
		assertTrue(fuzzyScore("米津", "米津玄師") > 0)
		assertTrue(fuzzyScore("玄師", "米津玄師") > 0)
		assertEquals(0, fuzzyScore("米津玄師", "米津玄司"))
	}

	@Test
	fun indexReturnsBestFirstTiesInInputOrder() {
		val index = FuzzyIndex(
			listOf(
				FuzzyIndex.Entry("low", "Low Kiosk"),
				FuzzyIndex.Entry("typo", "Kiosc"),
				FuzzyIndex.Entry("exact", "Kiosk"),
				FuzzyIndex.Entry("none", "Radiohead"),
				FuzzyIndex.Entry("prefix", "Kiosk Hours"),
				FuzzyIndex.Entry("exact2", "kiosk")
			)
		)
		assertEquals(listOf("exact", "exact2", "prefix", "low", "typo"), index.search("kiosk", 10))
		assertEquals(listOf("exact", "exact2"), index.search("kiosk", 2))
		assertEquals(emptyList(), index.search("   ", 10))
		assertEquals(emptyList(), index.search("", 10))
	}
}
