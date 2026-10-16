package paige.navic.domain.manager

import com.russhwolf.settings.Settings
import paige.navic.domain.models.settings.StationCharacter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I-040's preference migration, through a real [PreferenceManager] over an in-memory store: the
 * four-way `AutoplayMode` (stored by ordinal at "autoplayMode") becomes On / Off, and the old
 * `MoodCharacter` ordinal at "moodCharacter" is read as a `StationCharacter` that means the same.
 * Constructing the manager is itself the "no crash" check: an enum read through a shrunk enum
 * used to throw from the property delegate.
 */
class AutoplayPrefMigrationTest {

	private fun store(vararg ints: Pair<String, Int>) = MemorySettings().apply {
		ints.forEach { (key, value) -> putInt(key, value) }
	}

	@Test
	fun everyStoredModeAndCharacterMigratesWithoutCrashing() {
		val expectedOn = mapOf(0 to false, 1 to true, 2 to true, 3 to true)
		val expectedCharacter = listOf(StationCharacter.Close, StationCharacter.Balanced, StationCharacter.Explore)
		for (mode in 0..3) {
			for (character in 0..2) {
				val settings = store("autoplayMode" to mode, "moodCharacter" to character)
				val prefs = PreferenceManager(settings)
				assertEquals(expectedOn[mode], prefs.autoplayOn, "mode $mode")
				assertEquals(expectedCharacter[character], prefs.stationCharacter, "character $character")
				assertNull(settings.getIntOrNull("autoplayMode"), "the old key is removed")
				assertEquals(character, settings.getInt("moodCharacter", -1), "the character keeps its key")
			}
		}
	}

	@Test
	fun moodFlowAndTransitionMaestroLandOnOnAndExplore() {
		// The plan's S6: an upgrade from Mood Flow + Transition Maestro.
		val prefs = PreferenceManager(store("autoplayMode" to 3, "moodCharacter" to 2))
		assertTrue(prefs.autoplayOn)
		assertEquals(StationCharacter.Explore, prefs.stationCharacter)
	}

	@Test
	fun aFreshInstallIsOffAndBalanced() {
		val prefs = PreferenceManager(MemorySettings())
		assertFalse(prefs.autoplayOn)
		assertEquals(StationCharacter.Balanced, prefs.stationCharacter)
	}

	@Test
	fun theMigrationRunsOnceAndDoesNotOverruleALaterChoice() {
		val settings = store("autoplayMode" to 1)
		val first = PreferenceManager(settings)
		assertTrue(first.autoplayOn)
		first.autoplayOn = false
		// A later launch: nothing at the old key any more, and the user's Off stands.
		val second = PreferenceManager(settings)
		assertFalse(second.autoplayOn)
		// Even a stray old value written back is not read again.
		settings.putInt("autoplayMode", 3)
		assertFalse(PreferenceManager(settings).autoplayOn)
	}

	@Test
	fun anOrdinalThisBuildDoesNotKnowIsTheDefaultNotACrash() {
		val prefs = PreferenceManager(store("moodCharacter" to 7, "theme" to 99))
		assertEquals(StationCharacter.Balanced, prefs.stationCharacter)
		val negative = PreferenceManager(store("moodCharacter" to -1))
		assertEquals(StationCharacter.Balanced, negative.stationCharacter)
	}

	@Test
	fun theCharacterOrderIsTheOldPresetOrder() {
		// Append-only: these ordinals are what installs have stored since MoodCharacter.
		assertEquals(listOf("EchoMatch", "SteadyVibes", "TransitionMaestro"), StationCharacter.entries.map { it.legacyName })
		assertEquals(listOf("close", "balanced", "explore"), StationCharacter.entries.map { it.wireId })
		assertEquals(StationCharacter.Explore, StationCharacter.fromWire("TransitionMaestro"))
		assertEquals(StationCharacter.Close, StationCharacter.fromWire("close"))
		assertNull(StationCharacter.fromWire("nonsense"))
		assertNull(StationCharacter.fromWire(""))
	}
}

/** An in-memory [Settings], for constructing [PreferenceManager] on the JVM. */
internal class MemorySettings : Settings {
	private val values = LinkedHashMap<String, Any>()

	override val keys: Set<String> get() = values.keys.toSet()
	override val size: Int get() = values.size
	override fun clear() = values.clear()
	override fun remove(key: String) {
		values.remove(key)
	}

	override fun hasKey(key: String): Boolean = key in values

	override fun putInt(key: String, value: Int) {
		values[key] = value
	}

	override fun getInt(key: String, defaultValue: Int): Int = values[key] as? Int ?: defaultValue
	override fun getIntOrNull(key: String): Int? = values[key] as? Int
	override fun putLong(key: String, value: Long) {
		values[key] = value
	}

	override fun getLong(key: String, defaultValue: Long): Long = values[key] as? Long ?: defaultValue
	override fun getLongOrNull(key: String): Long? = values[key] as? Long
	override fun putString(key: String, value: String) {
		values[key] = value
	}

	override fun getString(key: String, defaultValue: String): String = values[key] as? String ?: defaultValue
	override fun getStringOrNull(key: String): String? = values[key] as? String
	override fun putFloat(key: String, value: Float) {
		values[key] = value
	}

	override fun getFloat(key: String, defaultValue: Float): Float = values[key] as? Float ?: defaultValue
	override fun getFloatOrNull(key: String): Float? = values[key] as? Float
	override fun putDouble(key: String, value: Double) {
		values[key] = value
	}

	override fun getDouble(key: String, defaultValue: Double): Double = values[key] as? Double ?: defaultValue
	override fun getDoubleOrNull(key: String): Double? = values[key] as? Double
	override fun putBoolean(key: String, value: Boolean) {
		values[key] = value
	}

	override fun getBoolean(key: String, defaultValue: Boolean): Boolean = values[key] as? Boolean ?: defaultValue
	override fun getBooleanOrNull(key: String): Boolean? = values[key] as? Boolean
}
