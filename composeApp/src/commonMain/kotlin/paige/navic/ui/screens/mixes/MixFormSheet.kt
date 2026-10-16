package paige.navic.ui.screens.mixes

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import com.kyant.capsule.ContinuousCapsule
import paige.navic.ui.components.common.PageChips
import paige.navic.ui.components.common.SegmentedListItem
import paige.navic.ui.components.common.SegmentedListItemDefaults
import paige.navic.ui.components.sheets.SheetHeader
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_cancel
import navic.composeapp.generated.resources.action_save
import navic.composeapp.generated.resources.mix_how_built
import navic.composeapp.generated.resources.mix_length
import navic.composeapp.generated.resources.mix_length_tracks
import navic.composeapp.generated.resources.mix_mood_character
import navic.composeapp.generated.resources.mix_seed
import navic.composeapp.generated.resources.mix_kind_artist
import navic.composeapp.generated.resources.mix_kind_genre
import navic.composeapp.generated.resources.mix_kind_station
import navic.composeapp.generated.resources.mix_seed_needs_playing
import navic.composeapp.generated.resources.mix_seed_none_found
import navic.composeapp.generated.resources.mix_seed_pick
import navic.composeapp.generated.resources.mix_seed_radio
import navic.composeapp.generated.resources.mix_seed_your_mix
import navic.composeapp.generated.resources.queue_your_mix
import navic.composeapp.generated.resources.mix_seed_search_artist
import navic.composeapp.generated.resources.mix_seed_search_genre
import navic.composeapp.generated.resources.option_mix_name
import navic.composeapp.generated.resources.title_new_mix
import org.jetbrains.compose.resources.stringResource
import paige.navic.domain.models.DomainSong
import paige.navic.domain.models.Mix
import paige.navic.domain.models.MixKind
import paige.navic.domain.models.creditText
import paige.navic.domain.manager.StationCatalogue
import paige.navic.domain.models.settings.StationCharacter
import paige.navic.ui.components.common.CoverArt
import paige.navic.ui.components.sheets.ModalBottomSheet
import paige.navic.ui.screens.mixes.viewmodels.MixSeedOption
import paige.navic.ui.util.rememberNowPlayingCoverAmbient

/** The lengths offered. Chips rather than a number field: three plausible answers. */
private val LENGTHS = listOf(25, 50, 100)

/**
 * What the form makes. A radio and Your mix are both `station` recipes (PROTOCOL §17.1), with a
 * seed track or none; the older `similar`, `fingerprint` and `adaptive` kinds still play, and
 * are no longer made here (I-040): with the station engine they play as these two anyway.
 */
private enum class FormKind { Radio, YourMix, Genre, Artist }

/**
 * Making a mix from scratch.
 *
 * Until this existed the only create path was "save what's playing", which takes
 * its recipe from whatever autoplay mode happens to be switched on — so
 * [MixKind.GENRE] and [MixKind.ARTIST] were **unreachable from this client
 * entirely**. `RadioManager.regenerate` could play them, `MixFormat` could label
 * them and the hub validated them; nothing could make one. They arrived only from
 * Feishin.
 *
 * A sheet rather than a `FormDialog`, which is 300dp wide: two of the four choices
 * need a searchable list of the library's own artists or genres, and that does not
 * fit in a dialog without becoming a second screen.
 *
 * Four choices since I-040: a radio of the playing track and Your mix (both `station`
 * recipes, each with a character), a genre and an artist. The older `similar`,
 * `fingerprint` and `adaptive` recipes still play and are no longer made here. The seed
 * is offered per choice, because they do not take the same kind of seed: a track, a
 * genre name, an artist id, or nothing at all. Feishin's `mix-form.tsx` offers the
 * playing track only, arguing a library picker would be a second search UI for a field
 * whose answer is always "this". That holds for a radio, which is why this keeps the
 * playing track there; it does not hold for the two kinds Feishin can also only reach
 * through a playing track's artist or first genre tag.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MixFormSheet(
	playing: DomainSong?,
	artists: List<MixSeedOption>,
	genres: List<MixSeedOption>,
	defaultCharacter: StationCharacter,
	catalogue: StationCatalogue,
	onSave: (Mix) -> Unit,
	onDismissRequest: () -> Unit
) {
	var kind by remember { mutableStateOf(FormKind.Radio) }
	var seed by remember { mutableStateOf<MixSeedOption?>(null) }
	var character by remember { mutableStateOf(defaultCharacter) }
	var count by remember { mutableStateOf(50) }
	val nameState = rememberTextFieldState("")
	// The last name this form filled in by itself. Until the user edits the field,
	// it tracks the recipe — picking "Artist" and then an artist should not leave
	// "Nikes mix" sitting in the box — and the moment they type, it is theirs.
	//
	// Comparing against the last auto-fill rather than carrying a `touched` flag
	// set from a `snapshotFlow`: that version captured `suggested` once, so after
	// the first kind change it compared against a stale suggestion and decided the
	// user had typed when they had not.
	var autoFilled by remember { mutableStateOf("") }
	val searchState = rememberTextFieldState("")

	val yourMixName = stringResource(Res.string.queue_your_mix)
	val suggested = when (kind) {
		FormKind.Radio -> playing?.title?.let { "$it radio" }.orEmpty()
		FormKind.YourMix -> yourMixName
		else -> seed?.name.orEmpty()
	}

	LaunchedEffect(suggested) {
		val current = nameState.text.toString()
		if (current.isBlank() || current == autoFilled) {
			autoFilled = suggested
			nameState.setTextAndPlaceCursorAtEnd(suggested)
		}
	}

	// Changing kind invalidates a seed chosen for the previous one: an artist id is
	// not a genre name, and carrying it across would save a recipe that regenerates
	// to nothing.
	LaunchedEffect(kind) {
		seed = null
		searchState.clearText()
	}

	val options = when (kind) {
		FormKind.Artist -> artists
		FormKind.Genre -> genres
		else -> emptyList()
	}
	val query = searchState.text.toString().trim()
	val filtered = remember(options, query) {
		if (query.isBlank()) options.take(100)
		else options.filter { it.name.contains(query, ignoreCase = true) }.take(100)
	}

	val blocked = when (kind) {
		FormKind.Radio -> playing?.id.isNullOrBlank()
		FormKind.Artist, FormKind.Genre -> seed == null
		FormKind.YourMix -> false
	} || nameState.text.isBlank()

	ModalBottomSheet(
		onDismissRequest = onDismissRequest,
		ambient = rememberNowPlayingCoverAmbient()
	) {
		// A title the sheet shows (it used to sit in the drag area, where nothing drew it) over
		// ONE scroll: the seed list was a second one, capped at 240dp inside the first.
		SheetHeader(stringResource(Res.string.title_new_mix))
		Column(
			modifier = Modifier
				.verticalScroll(rememberScrollState())
				.padding(horizontal = 20.dp)
				.padding(top = 12.dp, bottom = 24.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp)
		) {
			OutlinedTextField(
				state = nameState,
				label = { Text(stringResource(Res.string.option_mix_name)) },
				lineLimits = TextFieldLineLimits.SingleLine,
				modifier = Modifier.fillMaxWidth()
			)

			FormLabel(stringResource(Res.string.mix_how_built))
			PageChips(
				options = FormKind.entries,
				selected = kind,
				label = { formKindName(it) },
				onSelect = { kind = it },
				contentPadding = PaddingValues(0.dp),
				wrap = true
			)

			when (kind) {
				FormKind.Radio, FormKind.YourMix -> {
					FormNote(
						// Stated as a fact about what will be SAVED, since "the currently playing
						// track" stops being true the moment playback moves on.
						when {
							kind == FormKind.YourMix -> stringResource(Res.string.mix_seed_your_mix)
							playing != null -> stringResource(Res.string.mix_seed_radio, playing.title, playing.creditText)
							else -> stringResource(Res.string.mix_seed_needs_playing)
						}
					)
					FormLabel(stringResource(Res.string.mix_mood_character))
					PageChips(
						options = StationCharacter.entries,
						selected = character,
						label = { catalogue.infoFor(it).label.ifBlank { it.label } },
						onSelect = { character = it },
						contentPadding = PaddingValues(0.dp),
						wrap = true
					)
					catalogue.infoFor(character).description.takeIf { it.isNotBlank() }?.let { FormNote(it) }
				}

				else -> {
					FormLabel(stringResource(Res.string.mix_seed))
					OutlinedTextField(
						state = searchState,
						label = {
							Text(
								if (kind == FormKind.Artist) {
									stringResource(Res.string.mix_seed_search_artist)
								} else {
									stringResource(Res.string.mix_seed_search_genre)
								}
							)
						},
						lineLimits = TextFieldLineLimits.SingleLine,
						modifier = Modifier.fillMaxWidth()
					)
					if (filtered.isEmpty()) {
						FormNote(stringResource(Res.string.mix_seed_none_found))
					} else {
						// The seed as a one-of list of upstream's segmented rows, each with its
						// radio button, as the sort sheet offers its orders.
						Column(
							modifier = Modifier.selectableGroup(),
							verticalArrangement = Arrangement.spacedBy(SegmentedListItemDefaults.SegmentedGap)
						) {
							filtered.forEachIndexed { index, option ->
								val selected = seed?.id == option.id
								SegmentedListItem(
									selected = selected,
									onClick = { seed = option },
									shapes = SegmentedListItemDefaults.segmentedShapes(
										index = index,
										count = filtered.size
									),
									verticalAlignment = Alignment.CenterVertically,
									leadingContent = if (kind == FormKind.Artist) {
										{
											CoverArt(
												modifier = Modifier.size(40.dp),
												coverArtId = option.coverArtId.ifBlank { null },
												contentDescription = null,
												isArtist = true
											)
										}
									} else null,
									trailingContent = { RadioButton(selected = selected, onClick = null) }
								) {
									Text(option.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
								}
							}
						}
					}
					if (seed == null) FormNote(stringResource(Res.string.mix_seed_pick))
				}
			}

			FormLabel(stringResource(Res.string.mix_length))
			PageChips(
				options = LENGTHS,
				selected = count,
				label = { stringResource(Res.string.mix_length_tracks, it) },
				onSelect = { count = it },
				contentPadding = PaddingValues(0.dp),
				wrap = true
			)

			Row(
				modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
				horizontalArrangement = Arrangement.spacedBy(8.dp)
			) {
				OutlinedButton(
					onClick = onDismissRequest,
					shape = ContinuousCapsule,
					modifier = Modifier.weight(1f)
				) { Text(stringResource(Res.string.action_cancel)) }
				Button(
					enabled = !blocked,
					shape = ContinuousCapsule,
					modifier = Modifier.weight(1f),
					onClick = {
						onSave(
							buildRecipe(
								name = nameState.text.toString().trim(),
								kind = kind,
								playing = playing,
								seed = seed,
								character = character,
								count = count
							)
						)
					}
				) { Text(stringResource(Res.string.action_save)) }
			}
		}
	}
}

@Composable
private fun formKindName(kind: FormKind): String = when (kind) {
	FormKind.Radio -> stringResource(Res.string.mix_kind_station)
	FormKind.YourMix -> stringResource(Res.string.queue_your_mix)
	FormKind.Genre -> stringResource(Res.string.mix_kind_genre)
	FormKind.Artist -> stringResource(Res.string.mix_kind_artist)
}

@Composable
private fun FormLabel(text: String) {
	Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun FormNote(text: String) {
	Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The form's answers as a recipe.
 *
 * `seedId` is written in the seed kind's own vocabulary — a song id, an artist id,
 * or a genre **name** — because that is what `RadioManager.regenerate` looks each
 * one up by. `id` is blank: minting belongs to the hub. A radio and Your mix carry the
 * character twice, as the wire id and as the old preset's name, for a client from before
 * stations (PROTOCOL §17.1).
 */
private fun buildRecipe(
	name: String,
	kind: FormKind,
	playing: DomainSong?,
	seed: MixSeedOption?,
	character: StationCharacter,
	count: Int
): Mix = when (kind) {
	FormKind.Radio -> Mix(
		id = "",
		name = name,
		kind = MixKind.STATION,
		seedId = playing?.id.orEmpty(),
		seedName = playing?.title.orEmpty(),
		character = character.wireId,
		moodCharacter = character.legacyName,
		count = count,
		coverArtId = playing?.coverArtId.orEmpty()
	)

	FormKind.YourMix -> Mix(
		id = "",
		name = name,
		kind = MixKind.STATION,
		character = character.wireId,
		moodCharacter = character.legacyName,
		count = count
	)

	FormKind.Genre, FormKind.Artist -> Mix(
		id = "",
		name = name,
		kind = if (kind == FormKind.Genre) MixKind.GENRE else MixKind.ARTIST,
		seedId = seed?.id.orEmpty(),
		seedName = seed?.name.orEmpty(),
		count = count,
		coverArtId = seed?.coverArtId.orEmpty()
	)
}
