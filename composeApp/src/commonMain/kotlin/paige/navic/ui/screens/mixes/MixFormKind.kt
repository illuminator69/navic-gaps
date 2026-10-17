package paige.navic.ui.screens.mixes

import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.mix_kind_artist
import navic.composeapp.generated.resources.mix_kind_genre
import navic.composeapp.generated.resources.mix_kind_station
import navic.composeapp.generated.resources.mix_line_artist
import navic.composeapp.generated.resources.mix_line_genre
import navic.composeapp.generated.resources.mix_line_radio
import navic.composeapp.generated.resources.mix_your_mix_line
import navic.composeapp.generated.resources.queue_your_mix
import org.jetbrains.compose.resources.StringResource
import paige.navic.domain.models.DomainSong
import paige.navic.domain.models.Mix
import paige.navic.domain.models.MixKind
import paige.navic.domain.models.settings.StationCharacter
import paige.navic.ui.screens.mixes.viewmodels.MixSeedOption

/** What a choice in the new mix form is built around. */
enum class MixFormSeed {
	/** The playing song, stated rather than picked. */
	PlayingSong,
	/** Nothing: built from what the hub's user plays most. */
	None,
	/** A genre of the library's, by name. */
	Genre,
	/** An artist of the library's, by id. */
	Artist
}

/**
 * The new mix form's choices, in the order it offers them, with the words it says for each
 * (B-150). **Feishin's `mix-form.tsx` offers the same four, in this order, with the same
 * words**: change one and change the other.
 *
 * - **Radio**, "Around a song, steered by your skips and likes": a `station` recipe on the playing
 *   song, with a character.
 * - **Your mix**, "Built from what you play most": a `station` recipe with no seed, with a
 *   character. Only with the hub's station engine, which is what builds it.
 * - **Genre**, "A random run through one genre": a `genre` recipe by the genre's name, no character.
 * - **Artist**, "More like one artist": an `artist` recipe (an artist radio) with a character.
 *
 * The older `similar`, `fingerprint` and `adaptive` kinds still play and are not made here.
 */
enum class MixFormKind(
	val label: StringResource,
	val line: StringResource,
	val seed: MixFormSeed,
	val takesCharacter: Boolean
) {
	Radio(Res.string.mix_kind_station, Res.string.mix_line_radio, MixFormSeed.PlayingSong, takesCharacter = true),
	YourMix(Res.string.queue_your_mix, Res.string.mix_your_mix_line, MixFormSeed.None, takesCharacter = true),
	Genre(Res.string.mix_kind_genre, Res.string.mix_line_genre, MixFormSeed.Genre, takesCharacter = false),
	Artist(Res.string.mix_kind_artist, Res.string.mix_line_artist, MixFormSeed.Artist, takesCharacter = true)
}

/** The choices offered: all four with the station engine, Your mix hidden without it. */
fun mixFormKinds(stationEngine: Boolean): List<MixFormKind> =
	MixFormKind.entries.filter { stationEngine || it != MixFormKind.YourMix }

/**
 * The form's answers as a recipe (PROTOCOL §17.1).
 *
 * `seedId` is in the seed kind's own vocabulary: a song id, nothing, a genre **name** (in
 * `seedName` too), an artist id. `id` is blank: minting belongs to the hub. A kind that takes a
 * character carries it twice, as the wire id and as the old preset's name, for a client from
 * before stations; a genre carries none.
 */
fun mixRecipe(
	name: String,
	kind: MixFormKind,
	playing: DomainSong?,
	seed: MixSeedOption?,
	character: StationCharacter,
	count: Int
): Mix {
	val wire = if (kind.takesCharacter) character.wireId else ""
	val legacy = if (kind.takesCharacter) character.legacyName else ""
	return when (kind) {
		MixFormKind.Radio -> Mix(
			id = "",
			name = name,
			kind = MixKind.STATION,
			seedId = playing?.id.orEmpty(),
			seedName = playing?.title.orEmpty(),
			character = wire,
			moodCharacter = legacy,
			count = count,
			coverArtId = playing?.coverArtId.orEmpty()
		)

		MixFormKind.YourMix -> Mix(
			id = "",
			name = name,
			kind = MixKind.STATION,
			character = wire,
			moodCharacter = legacy,
			count = count
		)

		MixFormKind.Genre -> Mix(
			id = "",
			name = name,
			kind = MixKind.GENRE,
			seedId = seed?.name.orEmpty(),
			seedName = seed?.name.orEmpty(),
			count = count
		)

		MixFormKind.Artist -> Mix(
			id = "",
			name = name,
			kind = MixKind.ARTIST,
			seedId = seed?.id.orEmpty(),
			seedName = seed?.name.orEmpty(),
			character = wire,
			moodCharacter = legacy,
			count = count,
			coverArtId = seed?.coverArtId.orEmpty()
		)
	}
}
