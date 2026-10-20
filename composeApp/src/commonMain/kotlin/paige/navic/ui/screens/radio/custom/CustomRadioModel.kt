package paige.navic.ui.screens.radio.custom

import paige.navic.domain.manager.StationCountAnswer
import paige.navic.domain.models.Mix
import paige.navic.domain.models.MixKind
import paige.navic.domain.models.settings.StationCharacter

/*
 * A custom radio (P5): a radio from several artists, tuned by Artist variety and the character
 * (Familiar / Blend / Discover), with a live count of what it can draw from, saved under Mixes.
 *
 * Every rule the pick and tune screens apply, as pure functions (`CustomRadioModelTest`): no
 * Compose, no clock. The view model asks the hub and keeps the state; these decide what it means.
 */

/** The most artists one custom radio takes (the hub keeps at most 8 `seedIds`, PROTOCOL §17.1). */
const val CUSTOM_RADIO_MAX_ARTISTS = 8

/** The queue a custom radio asks for when it starts. */
const val CUSTOM_RADIO_COUNT = 50

/**
 * How often one artist may come back (the hub's `variety`, PROTOCOL §18.1). [Medium] is the
 * character's own spacing and what an absent value means, so a hub that predates it plays the
 * same radio.
 */
enum class Variety(val wire: String) {
	Low("low"),
	Medium("medium"),
	High("high");

	companion object {
		fun fromWire(value: String?): Variety? = entries.firstOrNull { it.wire == value }
	}
}

data class PickedArtist(val id: String, val name: String, val coverArtId: String)

/** The radio's name: the picked artists' names in the order they were picked. */
fun radioTitle(artists: List<PickedArtist>): String =
	artists.map { it.name.trim() }.filter { it.isNotEmpty() }.joinToString(", ")

/**
 * What the count line shows. [Counting] keeps the number it replaces, drawn dimmed under a spinner;
 * [Known] with [Known.partial] is a number that may be low (a list failed), never shown as complete.
 * [Unknown] is AudioMuse holding none of the picked songs; [Unavailable] no engine, or a hub that
 * cannot count; [Failed] a count that did not come back.
 */
sealed interface CountState {
	data object Idle : CountState
	data class Counting(val previous: Int?) : CountState
	data class Known(val count: Int, val partial: Boolean = false) : CountState
	data object Unknown : CountState
	data object Unavailable : CountState
	data object Failed : CountState
}

/**
 * What a count is asked for: the picked artists, in any order, and the character. Artist variety
 * is not in it: it changes how the pool is spaced, not what is in it, so tapping it asks nothing.
 */
data class CountKey(val artistIds: List<String>, val character: String) {
	companion object {
		fun of(artistIds: List<String>, character: StationCharacter): CountKey =
			CountKey(artistIds.distinct().sorted(), character.wireId)
	}
}

/**
 * The count line after an event about [key]. [answer] null means a count for [key] went out.
 * Anything about a key that is not [current] (the artists or the character changed since it was
 * asked) changes nothing: only the answer for what is on screen is shown.
 */
fun reduceCount(state: CountState, key: CountKey, current: CountKey, answer: StationCountAnswer?): CountState {
	if (key != current) return state
	return when (answer) {
		null -> CountState.Counting(
			when (state) {
				is CountState.Known -> state.count
				is CountState.Counting -> state.previous
				else -> null
			}
		)
		is StationCountAnswer.Counted -> CountState.Known(answer.count, answer.partial)
		StationCountAnswer.SeedUnknown -> CountState.Unknown
		StationCountAnswer.Unavailable -> CountState.Unavailable
		StationCountAnswer.Failed -> CountState.Failed
	}
}

/**
 * Whether Done can be tapped: an artist is picked, and the count has not said there is nothing to
 * play. Only a count of 0 and "AudioMuse knows none of these" disable it; no engine, a count that
 * failed or one still out leave it enabled (the radio then plays Navidrome's similar songs).
 */
fun canFinish(picked: Int, count: CountState): Boolean {
	if (picked <= 0) return false
	return when (count) {
		is CountState.Known -> count.count > 0
		CountState.Unknown -> false
		else -> true
	}
}

/**
 * The recipe a custom radio is saved as (PROTOCOL §17.1): an `artist` mix with every artist in
 * `seedIds` and the first also in `seedId`, so a client that predates `seedIds` plays the first
 * artist's radio rather than hiding the row. Both the character and its legacy name are written.
 */
fun customRadioRecipe(
	id: String,
	name: String,
	artists: List<PickedArtist>,
	character: StationCharacter,
	variety: Variety,
	count: Int = CUSTOM_RADIO_COUNT
): Mix {
	val first = artists.firstOrNull()
	return Mix(
		id = id,
		name = name,
		kind = MixKind.ARTIST,
		seedId = first?.id.orEmpty(),
		seedName = first?.name.orEmpty(),
		seedIds = artists.map { it.id }.distinct(),
		variety = variety.wire,
		character = character.wireId,
		moodCharacter = character.legacyName,
		count = count,
		coverArtId = first?.coverArtId.orEmpty()
	)
}
