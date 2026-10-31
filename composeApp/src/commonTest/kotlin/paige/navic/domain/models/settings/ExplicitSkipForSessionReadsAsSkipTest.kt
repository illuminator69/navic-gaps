package paige.navic.domain.models.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Worbus settings N4: "Skip explicit songs for this session" was never implemented, so a stored one
 * is Skip. The setting offers Play and Skip only.
 */
class ExplicitSkipForSessionReadsAsSkipTest {

	@Test
	fun aStoredSkipForThisSessionReadsAsSkip() {
		assertEquals(ExplicitContentPlayback.Skip, ExplicitContentPlayback.SkipForThisSession.normalised())
		assertEquals(ExplicitContentPlayback.Skip, ExplicitContentPlayback.Skip.normalised())
		assertEquals(ExplicitContentPlayback.Allowed, ExplicitContentPlayback.Allowed.normalised())
	}

	@Test
	fun bothSkipValuesHoldExplicitSongsBack() {
		assertTrue(ExplicitContentPlayback.Skip.skipsExplicit())
		assertTrue(ExplicitContentPlayback.SkipForThisSession.skipsExplicit())
		assertFalse(ExplicitContentPlayback.Allowed.skipsExplicit())
	}
}
