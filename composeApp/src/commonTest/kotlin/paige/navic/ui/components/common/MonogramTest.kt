package paige.navic.ui.components.common

import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * P1: the header avatar is a Monogram on every root page, so whatever a server's username looks like
 * must give it something to draw. Empty or blank is no initials (the avatar draws a person instead);
 * a name starting with an emoji keeps the emoji whole, not half of its surrogate pair.
 */
class MonogramTest {

	@Test
	fun twoLettersUpperCased() {
		assertEquals("IL", monogramInitials("illia"))
		assertEquals("IL", monogramInitials("Illia"))
	}

	@Test
	fun oneLetterIsOne() {
		assertEquals("A", monogramInitials("a"))
	}

	@Test
	fun anEmptyOrBlankNameHasNoInitials() {
		assertEquals("", monogramInitials(""))
		assertEquals("", monogramInitials("   "))
	}

	@Test
	fun leadingSpacesDoNotCostALetter() {
		assertEquals("AB", monogramInitials("  ab"))
	}

	@Test
	fun anEmojiIsNotSplit() {
		assertEquals("😀X", monogramInitials("😀xyz"))
		assertEquals("😀", monogramInitials("😀"))
	}

	@Test
	fun aVeryLongNameStillGivesTwo() {
		assertEquals("LO", monogramInitials("l".repeat(1) + "o".repeat(500)))
	}
}
