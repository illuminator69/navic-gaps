package paige.navic.ui.screens.mixes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import navic.composeapp.generated.resources.mix_seed_adaptive
import navic.composeapp.generated.resources.mix_seed_fingerprint
import navic.composeapp.generated.resources.mix_seed_needs_playing
import navic.composeapp.generated.resources.mix_seed_none_found
import navic.composeapp.generated.resources.mix_seed_pick
import navic.composeapp.generated.resources.mix_seed_playing
import navic.composeapp.generated.resources.mix_seed_search_artist
import navic.composeapp.generated.resources.mix_seed_search_genre
import navic.composeapp.generated.resources.option_mix_name
import navic.composeapp.generated.resources.title_new_mix
import org.jetbrains.compose.resources.stringResource
import paige.navic.domain.models.DomainSong
import paige.navic.domain.models.Mix
import paige.navic.domain.models.MixKind
import paige.navic.domain.models.creditText
import paige.navic.domain.models.settings.MoodCharacter
import paige.navic.ui.components.common.CoverArt
import paige.navic.ui.components.sheets.ModalBottomSheet
import paige.navic.ui.screens.mixes.viewmodels.MixSeedOption
import paige.navic.ui.util.rememberNowPlayingCoverAmbient

/** The lengths offered. Chips rather than a number field: three plausible answers. */
private val LENGTHS = listOf(25, 50, 100)

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
 * A sheet rather than a `FormDialog`, which is 300dp wide: two of the five kinds
 * need a searchable list of the library's own artists or genres, and that does not
 * fit in a dialog without becoming a second screen.
 *
 * The seed is offered per kind rather than universally, because the five kinds do
 * not take the same kind of seed — an id, a genre name, a steering character, or
 * nothing at all. Feishin's `mix-form.tsx` offers the playing track only, arguing
 * a library picker would be a second search UI for a field whose answer is always
 * "this". That holds for `similar`, which is why this keeps the playing track
 * there; it does not hold for the two kinds Feishin can also only reach through a
 * playing track's artist or first genre tag.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MixFormSheet(
	playing: DomainSong?,
	artists: List<MixSeedOption>,
	genres: List<MixSeedOption>,
	defaultMoodCharacter: MoodCharacter,
	onSave: (Mix) -> Unit,
	onDismissRequest: () -> Unit
) {
	var kind by remember { mutableStateOf(MixKind.SIMILAR) }
	var seed by remember { mutableStateOf<MixSeedOption?>(null) }
	var character by remember { mutableStateOf(defaultMoodCharacter) }
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

	val suggested = when (kind) {
		MixKind.SIMILAR -> playing?.title?.let { "$it mix" }.orEmpty()
		MixKind.FINGERPRINT -> mixKindName(MixKind.FINGERPRINT)
		MixKind.ADAPTIVE -> character.label
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
		MixKind.ARTIST -> artists
		MixKind.GENRE -> genres
		else -> emptyList()
	}
	val query = searchState.text.toString().trim()
	val filtered = remember(options, query) {
		if (query.isBlank()) options.take(100)
		else options.filter { it.name.contains(query, ignoreCase = true) }.take(100)
	}

	val blocked = when (kind) {
		MixKind.SIMILAR -> playing?.id.isNullOrBlank()
		MixKind.ARTIST, MixKind.GENRE -> seed == null
		else -> false
	} || nameState.text.isBlank()

	ModalBottomSheet(
		onDismissRequest = onDismissRequest,
		ambient = rememberNowPlayingCoverAmbient(),
		sheetTitle = stringResource(Res.string.title_new_mix)
	) {
		Column(
			modifier = Modifier.padding(horizontal = 24.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp)
		) {
			TextField(
				state = nameState,
				label = { Text(stringResource(Res.string.option_mix_name)) },
				lineLimits = TextFieldLineLimits.SingleLine,
				modifier = Modifier.fillMaxWidth()
			)

			Text(
				stringResource(Res.string.mix_how_built),
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
			FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				MixKind.ALL.forEach { candidate ->
					FilterChip(
						selected = kind == candidate,
						onClick = { kind = candidate },
						label = { Text(mixKindName(candidate)) }
					)
				}
			}

			when (kind) {
				MixKind.SIMILAR -> Text(
					// Stated as a fact about what will be SAVED, since "the
					// currently playing track" stops being true the moment
					// playback moves on.
					if (playing != null) {
						stringResource(
							Res.string.mix_seed_playing,
							playing.title,
							playing.creditText
						)
					} else {
						stringResource(Res.string.mix_seed_needs_playing)
					},
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)

				MixKind.FINGERPRINT -> Text(
					stringResource(Res.string.mix_seed_fingerprint),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)

				MixKind.ADAPTIVE -> {
					Text(
						stringResource(Res.string.mix_seed_adaptive),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
					Text(
						stringResource(Res.string.mix_mood_character),
						style = MaterialTheme.typography.labelLarge,
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
					FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						MoodCharacter.entries.forEach { candidate ->
							FilterChip(
								selected = character == candidate,
								onClick = { character = candidate },
								label = { Text(candidate.label) }
							)
						}
					}
				}

				else -> {
					Text(
						stringResource(Res.string.mix_seed),
						style = MaterialTheme.typography.labelLarge,
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
					TextField(
						state = searchState,
						label = {
							Text(
								if (kind == MixKind.ARTIST) {
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
						Text(
							stringResource(Res.string.mix_seed_none_found),
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
					} else {
						LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
							items(filtered, key = { it.id }) { option ->
								ListItem(
									colors = ListItemDefaults.colors(
										containerColor = Color.Transparent
									),
									onClick = { seed = option },
									leadingContent = if (kind == MixKind.ARTIST) {
										{
											CoverArt(
												modifier = Modifier.height(40.dp),
												coverArtId = option.coverArtId.ifBlank { null },
												contentDescription = null,
												isArtist = true
											)
										}
									} else null,
									content = {
										Text(
											option.name,
											maxLines = 1,
											overflow = TextOverflow.Ellipsis,
											color = if (seed?.id == option.id) {
												MaterialTheme.colorScheme.primary
											} else {
												MaterialTheme.colorScheme.onSurface
											}
										)
									}
								)
							}
						}
					}
					if (seed == null) {
						Text(
							stringResource(Res.string.mix_seed_pick),
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
					}
				}
			}

			Text(
				stringResource(Res.string.mix_length),
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
			FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				LENGTHS.forEach { candidate ->
					FilterChip(
						selected = count == candidate,
						onClick = { count = candidate },
						label = { Text(stringResource(Res.string.mix_length_tracks, candidate)) }
					)
				}
			}

			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
				verticalAlignment = Alignment.CenterVertically
			) {
				TextButton(onClick = onDismissRequest) {
					Text(stringResource(Res.string.action_cancel))
				}
				Button(
					enabled = !blocked,
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
			Spacer(Modifier.height(16.dp))
		}
	}
}

/**
 * The form's answers as a recipe.
 *
 * `seedId` is written in the seed kind's own vocabulary — a song id, an artist id,
 * or a genre **name** — because that is what `RadioManager.regenerate` looks each
 * one up by. `id` is blank: minting belongs to the hub.
 */
private fun buildRecipe(
	name: String,
	kind: String,
	playing: DomainSong?,
	seed: MixSeedOption?,
	character: MoodCharacter,
	count: Int
): Mix = when (kind) {
	MixKind.SIMILAR -> Mix(
		id = "",
		name = name,
		kind = kind,
		seedId = playing?.id.orEmpty(),
		seedName = playing?.title.orEmpty(),
		count = count,
		coverArtId = playing?.coverArtId.orEmpty()
	)

	MixKind.ADAPTIVE -> Mix(
		id = "",
		name = name,
		kind = kind,
		// By name, never by ordinal: the presets are hand-mirrored with Feishin and
		// an ordinal would re-point every stored recipe the day either side
		// reorders the enum.
		moodCharacter = character.name,
		count = count
	)

	MixKind.FINGERPRINT -> Mix(id = "", name = name, kind = kind, count = count)

	else -> Mix(
		id = "",
		name = name,
		kind = kind,
		seedId = seed?.id.orEmpty(),
		seedName = seed?.name.orEmpty(),
		count = count,
		coverArtId = seed?.coverArtId.orEmpty()
	)
}
