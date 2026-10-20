package paige.navic.ui.screens.mixes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.mix_kind_adaptive
import navic.composeapp.generated.resources.mix_kind_artist
import navic.composeapp.generated.resources.mix_kind_fingerprint
import navic.composeapp.generated.resources.mix_kind_genre
import navic.composeapp.generated.resources.mix_kind_similar
import navic.composeapp.generated.resources.mix_kind_station
import navic.composeapp.generated.resources.mix_custom_radio
import navic.composeapp.generated.resources.mix_kind_unknown
import navic.composeapp.generated.resources.mix_seeded_by
import navic.composeapp.generated.resources.queue_station_line
import navic.composeapp.generated.resources.queue_your_mix
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import paige.navic.domain.manager.StationManager
import paige.navic.domain.models.Mix
import paige.navic.domain.models.MixKind
import paige.navic.domain.models.isCustomRadio
import paige.navic.domain.models.settings.StationCharacter

/**
 * Display rules for mixes, shared by the Mixed for You screen and the Discover row —
 * the same arrangement `SavedQueueFormat.kt` has, and for the same reason: one
 * record, several surfaces, and it must read identically on all of them.
 *
 * Feishin renders the same hub-side list, so these labels are kept deliberately in
 * step with its own. There is no shared build; see `DiscoverRows.kt`.
 */

/** What kind of recipe this is, named for a person rather than for the wire. */
@Composable
fun mixKindName(kind: String): String = when (kind) {
	MixKind.SIMILAR -> stringResource(Res.string.mix_kind_similar)
	MixKind.FINGERPRINT -> stringResource(Res.string.mix_kind_fingerprint)
	MixKind.ADAPTIVE -> stringResource(Res.string.mix_kind_adaptive)
	MixKind.GENRE -> stringResource(Res.string.mix_kind_genre)
	MixKind.ARTIST -> stringResource(Res.string.mix_kind_artist)
	MixKind.STATION -> stringResource(Res.string.mix_kind_station)
	// Not the raw value, unlike `queueKindLabel`: a saved queue's unknown kind is
	// cosmetic, but an unknown mix kind is one this build cannot regenerate, and the
	// label is where the user finds that out before tapping it.
	else -> stringResource(Res.string.mix_kind_unknown)
}

/**
 * The recipe as one line: the kind, plus what it is seeded from when that is a
 * thing the user named, plus the character a radio, Your mix or an artist radio plays with
 * ("Radio from Cinnamon Girl · Balanced", "Your mix · Explore", "Artist from Neil Young · Close").
 *
 * Your mix (a `station` with no seed, `fingerprint`, `adaptive`) is built from listening
 * history, so it renders the kind alone rather than an empty "from".
 */
@Composable
fun mixKindLabel(mix: Mix): String {
	val seed = mix.seedName.ifBlank { mix.seedId }
	val character = StationCharacter.fromWire(mix.character) ?: StationCharacter.fromWire(mix.moodCharacter)
	// A custom radio (P5): its name already lists the artists, so the line says what it is.
	if (isCustomRadio(mix)) {
		val kind = stringResource(Res.string.mix_custom_radio)
		if (character == null) return kind
		val catalogue by koinInject<StationManager>().catalogue.collectAsState()
		return stringResource(Res.string.queue_station_line, kind, catalogue.infoFor(character).label.ifBlank { character.label })
	}
	if (mix.kind == MixKind.STATION || mix.kind == MixKind.ADAPTIVE) {
		val catalogue by koinInject<StationManager>().catalogue.collectAsState()
		val kind = if (mix.kind == MixKind.STATION && seed.isNotBlank()) {
			stringResource(Res.string.mix_seeded_by, stringResource(Res.string.mix_kind_station), seed)
		} else {
			stringResource(Res.string.queue_your_mix)
		}
		val shown = character ?: StationCharacter.Default
		return stringResource(
			Res.string.queue_station_line,
			kind,
			catalogue.infoFor(shown).label.ifBlank { shown.label }
		)
	}
	val kind = mixKindName(mix.kind)
	val line = if (MixKind.needsSeed(mix.kind) && seed.isNotBlank()) {
		stringResource(Res.string.mix_seeded_by, kind, seed)
	} else {
		kind
	}
	// An artist recipe made since B-150 carries the character its radio plays with; an older one
	// says none, and none is guessed for it.
	if (mix.kind == MixKind.ARTIST && character != null) {
		val catalogue by koinInject<StationManager>().catalogue.collectAsState()
		return stringResource(Res.string.queue_station_line, line, catalogue.infoFor(character).label.ifBlank { character.label })
	}
	return line
}
