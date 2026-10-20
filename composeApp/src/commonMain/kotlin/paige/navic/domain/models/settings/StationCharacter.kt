package paige.navic.domain.models.settings

/**
 * How far a station (a radio, Your mix, autoplay continuing a queue) may wander from what it
 * started with. The hub's station engine reads it (navi-connect PROTOCOL §18): its gate, its
 * sources and its artist spacing per character are the hub's, and the words shown for each come
 * from the hub's catalogue (`GET /sonic/station/characters`) when it has answered, with [label]
 * and the bundled descriptions in `StationManager` as the offline copy.
 *
 * **APPEND-ONLY, and the order is load-bearing.** The preference is stored by ordinal under the
 * key "moodCharacter", and these three take the places of the old `MoodCharacter` presets
 * (EchoMatch, SteadyVibes, TransitionMaestro) one for one, so a stored choice keeps its meaning
 * across the rename. Never reorder or remove an entry; a new one goes at the end. A stored
 * ordinal this build does not know reads as the default (`BasePreferenceManager.getEnum`).
 *
 * [wireId] is what the hub and stored recipes carry (`close|balanced|explore`), and [legacyName]
 * the old preset's name, which a recipe saved before stations still carries as `moodCharacter`.
 */
enum class StationCharacter(
	val wireId: String,
	val legacyName: String,
	val label: String,
	/**
	 * The old presets' Song Alchemy numbers, for an `adaptive` mix played with no station engine
	 * (`RadioManager.generate`), so that path sounds as it did. The engine has its own per
	 * character and ignores these.
	 */
	internal val alchemyTemperature: Float,
	internal val alchemySubtractDistance: Float?
) {
	// The words are Familiar / Blend / Discover since P5 (2026-10-20); the entries keep their
	// names, wire ids and ordinals, which is what a stored preference or recipe holds.
	Close("close", "EchoMatch", "Familiar", 0.5f, null),
	Balanced("balanced", "SteadyVibes", "Blend", 0.6f, 0.35f),
	Explore("explore", "TransitionMaestro", "Discover", 1.6f, null);

	companion object {
		val Default = Balanced

		/** By wire id, or by an old preset's name; null for anything else. */
		fun fromWire(value: String?): StationCharacter? {
			if (value.isNullOrBlank()) return null
			return entries.firstOrNull { it.wireId == value || it.legacyName == value || it.name == value }
		}
	}
}
