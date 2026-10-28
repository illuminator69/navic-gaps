package paige.navic.ui.screens.radio.custom

import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.material3.minimumInteractiveComponentSize
import paige.navic.ui.util.rememberFoldMotion
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_close
import navic.composeapp.generated.resources.custom_radio_add_artists
import navic.composeapp.generated.resources.custom_radio_count
import navic.composeapp.generated.resources.custom_radio_count_partial
import navic.composeapp.generated.resources.custom_radio_count_unavailable
import navic.composeapp.generated.resources.custom_radio_count_unfinished
import navic.composeapp.generated.resources.custom_radio_count_unknown
import navic.composeapp.generated.resources.custom_radio_count_updating
import navic.composeapp.generated.resources.custom_radio_count_zero
import navic.composeapp.generated.resources.custom_radio_count_low
import navic.composeapp.generated.resources.custom_radio_filters
import navic.composeapp.generated.resources.custom_radio_done
import navic.composeapp.generated.resources.custom_radio_hint
import navic.composeapp.generated.resources.custom_radio_hint_no_variety
import navic.composeapp.generated.resources.custom_radio_song_selection
import navic.composeapp.generated.resources.custom_radio_variety
import navic.composeapp.generated.resources.custom_radio_variety_high
import navic.composeapp.generated.resources.custom_radio_variety_low
import navic.composeapp.generated.resources.custom_radio_variety_medium
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import paige.navic.domain.models.settings.StationCharacter
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Add
import paige.navic.icons.outlined.Check
import paige.navic.icons.outlined.Close
import paige.navic.icons.outlined.Info
import paige.navic.ui.components.common.CoverArt
import paige.navic.ui.components.common.LocalCoverAmbientBottom
import navic.composeapp.generated.resources.custom_radio_save_failed
import paige.navic.di.LocalSnackBarState
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * Tune (boards RadioTune, RadioTuneUpdating, RadioEmpty): the picked artists with a "+" back to
 * Pick, the radio's name, Artist variety (only when the hub obeys it), Song selection as three
 * dials, and the count above Done. The count is asked when the artists or the character change,
 * never for a variety tap; Done is disabled only by a count of 0 or "AudioMuse knows none of these".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TuneStep(
	viewModel: CustomRadioViewModel,
	onAddArtists: () -> Unit,
	onClose: () -> Unit,
	onDone: (String) -> Unit
) {
	val picked by viewModel.picked.collectAsStateWithLifecycle()
	val character by viewModel.character.collectAsStateWithLifecycle()
	val variety by viewModel.variety.collectAsStateWithLifecycle()
	val count by viewModel.count.collectAsStateWithLifecycle()
	val catalogue by viewModel.catalogue.collectAsStateWithLifecycle()
	val varietyOffered by viewModel.varietyOffered.collectAsStateWithLifecycle()
	val finishing by viewModel.finishing.collectAsStateWithLifecycle()
	val filterWords by viewModel.filterWords.collectAsStateWithLifecycle()
	val moodFilter by viewModel.moodFilter.collectAsStateWithLifecycle()
	val scope = rememberCoroutineScope()
	val snackBarState = LocalSnackBarState.current
	val saveFailedWords = stringResource(Res.string.custom_radio_save_failed)

	Scaffold(
		containerColor = Color.Transparent,
		contentWindowInsets = WindowInsets(0),
		topBar = {
			Row(
				Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				PickedAvatars(picked, onAddArtists)
				Spacer(Modifier.weight(1f))
				IconButton(onClick = onClose) {
					Icon(Icons.Outlined.Close, contentDescription = stringResource(Res.string.action_close))
				}
			}
		}
	) { inner ->
		Box(Modifier.fillMaxSize().padding(top = inner.calculateTopPadding())) {
			Column(
				Modifier
					.fillMaxSize()
					.verticalScroll(rememberScrollState())
					.padding(bottom = 160.dp)
			) {
				Text(
					radioTitle(picked),
					style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp, lineHeight = 31.sp),
					fontWeight = FontWeight.Bold,
					maxLines = 3,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier.padding(horizontal = 16.dp)
				)
				if (varietyOffered) {
					SectionLabel(stringResource(Res.string.custom_radio_variety))
					SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
						Variety.entries.forEachIndexed { index, option ->
							SegmentedButton(
								shape = SegmentedButtonDefaults.itemShape(index = index, count = Variety.entries.size),
								onClick = { viewModel.setVariety(option) },
								selected = variety == option,
								label = { Text(stringResource(option.label)) }
							)
						}
					}
				}
				SectionLabel(stringResource(Res.string.custom_radio_song_selection), top = 20.dp)
				Row(
					Modifier.fillMaxWidth().padding(horizontal = 16.dp),
					horizontalArrangement = Arrangement.SpaceEvenly
				) {
					StationCharacter.entries.forEach { option ->
						Dial(
							label = catalogue.infoFor(option).label.ifBlank { option.label },
							selected = character == option,
							onClick = { viewModel.setCharacter(option) },
							modifier = Modifier.weight(1f)
						)
					}
				}
				Text(
					stringResource(
						when (tuneHint(varietyOffered)) {
							TuneHint.WithVariety -> Res.string.custom_radio_hint
							TuneHint.WithoutVariety -> Res.string.custom_radio_hint_no_variety
						}
					),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp)
				)
				// Filters: drawn only for what the hub advertises (`filters`: "mood" since P7), so a hub
				// without it shows no section and no gap (P5 call 4). One mood at a time; Home's five
				// words, a thin divider, then Pump-up, Upbeat, Downbeat (board RadioTune).
				if (!filterWords.isEmpty) {
					SectionLabel(stringResource(Res.string.custom_radio_filters), top = 20.dp)
					// Wrapping, not one sideways row: the page has the room, and every word (the chosen
					// one included) stays in view (your note on the AVD build, 2026-10-21). The divider
					// sits inline between the two groups, as the board draws it.
					FlowRow(
						Modifier.fillMaxWidth().padding(horizontal = 16.dp),
						horizontalArrangement = Arrangement.spacedBy(8.dp),
						verticalArrangement = Arrangement.spacedBy(4.dp),
						itemVerticalAlignment = Alignment.CenterVertically
					) {
						filterWords.home.forEach { mood ->
							FilterWord(mood.label, mood.id == moodFilter) { viewModel.tapMoodFilter(mood.id) }
						}
						if (filterWords.home.isNotEmpty() && filterWords.extra.isNotEmpty()) {
							VerticalDivider(Modifier.height(24.dp).padding(horizontal = 2.dp))
						}
						filterWords.extra.forEach { mood ->
							FilterWord(mood.label, mood.id == moodFilter) { viewModel.tapMoodFilter(mood.id) }
						}
					}
				}
			}
			Column(
				Modifier
					.align(Alignment.BottomCenter)
					.fillMaxWidth()
					.background(
						// The page's own bottom colour (the wash's, as the bottom bar's scrim uses), so the
						// list passes under the button into the page rather than under a slab.
						Brush.verticalGradient(
							0f to Color.Transparent,
							0.3f to (LocalCoverAmbientBottom.current ?: MaterialTheme.colorScheme.surface)
						)
					)
					.navigationBarsPadding()
					.padding(start = 16.dp, end = 16.dp, top = 30.dp, bottom = 20.dp),
				horizontalAlignment = Alignment.CenterHorizontally
			) {
				CountLine(count, warnLow = moodFilter != null && filterWarning(count))
				Spacer(Modifier.height(10.dp))
				Button(
					onClick = {
						viewModel.finish(onSaved = onDone, onFailed = {
							scope.launch { snackBarState.showSnackbar(saveFailedWords) }
						})
					},
					enabled = !finishing && canFinish(picked.size, count),
					modifier = Modifier.fillMaxWidth().height(52.dp),
					shape = RoundedCornerShape(26.dp)
				) {
					if (finishing) {
						CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
					} else {
						Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(20.dp))
					}
					Spacer(Modifier.width(8.dp))
					Text(stringResource(Res.string.custom_radio_done), style = MaterialTheme.typography.titleMedium)
				}
			}
		}
	}
}

private val Variety.label: StringResource
	get() = when (this) {
		Variety.Low -> Res.string.custom_radio_variety_low
		Variety.Medium -> Res.string.custom_radio_variety_medium
		Variety.High -> Res.string.custom_radio_variety_high
	}

@Composable
private fun SectionLabel(text: String, top: androidx.compose.ui.unit.Dp = 16.dp) {
	Text(
		text,
		style = MaterialTheme.typography.labelLarge,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = top, bottom = 8.dp)
	)
}

/** "+" (back to Pick, keeping the choice) and the picked artists, overlapping. */
@Composable
private fun PickedAvatars(picked: List<PickedArtist>, onAdd: () -> Unit) {
	Row(verticalAlignment = Alignment.CenterVertically) {
		Box(
			// 44 dp drawn, 48 dp to touch (audit L1-12).
			Modifier
				.minimumInteractiveComponentSize()
				.size(44.dp)
				.background(MaterialTheme.colorScheme.onSurface, CircleShape)
				.clickable(role = Role.Button, onClick = onAdd),
			contentAlignment = Alignment.Center
		) {
			Icon(
				Icons.Outlined.Add,
				contentDescription = stringResource(Res.string.custom_radio_add_artists),
				tint = MaterialTheme.colorScheme.surface,
				modifier = Modifier.size(22.dp)
			)
		}
		Spacer(Modifier.width(4.dp))
		picked.forEachIndexed { index, artist ->
			Box(
				Modifier
					.offset(x = (-8 * index).dp)
					.zIndex((picked.size - index).toFloat())
					.size(44.dp)
					.border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
			) {
				CoverArt(
					modifier = Modifier.size(44.dp),
					coverArtId = artist.coverArtId.ifBlank { null },
					contentDescription = artist.name,
					shape = CircleShape,
					isArtist = true,
					placeholderName = artist.name
				)
			}
		}
	}
}

/** One character as a dial: rings when not chosen, filled with the page's accent when chosen. */
@Composable
private fun Dial(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
	val scheme = MaterialTheme.colorScheme
	val fill by animateFloatAsState(if (selected) 1f else 0f, label = "dial")
	val text by animateColorAsState(if (selected) scheme.onSurface else scheme.onSurfaceVariant, label = "dialText")
	Column(
		modifier = modifier
			.semantics { this.selected = selected }
			.clickable(role = Role.RadioButton, onClick = onClick)
			.padding(vertical = 4.dp),
		horizontalAlignment = Alignment.CenterHorizontally
	) {
		Box(
			Modifier
				.size(84.dp)
				.drawBehind {
					val line = scheme.outlineVariant
					val r = size.minDimension / 2
					// The rings fade out as the fill comes in.
					val ringAlpha = 1f - fill
					if (ringAlpha > 0f) {
						var ring = r - 1.5f
						val step = 6.dp.toPx()
						while (ring > step) {
							drawCircle(line.copy(alpha = line.alpha * ringAlpha), radius = ring, style = Stroke(1.dp.toPx()))
							ring -= step
						}
					}
					if (fill > 0f) {
						drawCircle(
							Brush.radialGradient(
								listOf(scheme.primary, scheme.primaryContainer),
								center = Offset(size.width * 0.3f, size.height * 0.2f),
								radius = r * 2f
							),
							radius = r,
							alpha = fill
						)
					}
				}
		)
		Spacer(Modifier.height(8.dp))
		Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = text)
	}
}

/** The count, one line for each [CountState], cross-faded as it changes. */
@Composable
private fun CountLine(count: CountState, warnLow: Boolean = false) {
	val fade = rememberFoldMotion()
	AnimatedContent(
		targetState = count,
		contentKey = { it::class },
		transitionSpec = { fadeIn(fade.effects()) togetherWith fadeOut(fade.effects()) },
		label = "count"
	) { state ->
		// Read out when it changes, without interrupting (audit L4b-11).
		Row(
			Modifier.heightIn(min = 20.dp).semantics { liveRegion = LiveRegionMode.Polite },
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp)
		) {
			val muted = MaterialTheme.colorScheme.onSurfaceVariant
			val style = MaterialTheme.typography.bodyMedium
			when (state) {
				CountState.Idle -> Text("", style = style)
				is CountState.Counting -> {
					CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = muted)
					state.previous?.let {
						Text(
							stringResource(Res.string.custom_radio_count, formatCount(it)),
							style = style, fontWeight = FontWeight.Bold, modifier = Modifier.alpha(0.45f)
						)
					}
					Text(stringResource(Res.string.custom_radio_count_updating), style = style, color = muted)
				}
				// In songs the radio can reach (audit L4a-05): the hub's `reach` when it sends one.
				is CountState.Known -> when (val text = countText(state, warnLow)) {
					CountText.Zero -> WarnLine(stringResource(Res.string.custom_radio_count_zero))
					CountText.Unfinished ->
						Text(stringResource(Res.string.custom_radio_count_unfinished), style = style, color = muted)
					// A filter that leaves little (P7 call 8): said, and Done stays enabled.
					is CountText.Low -> WarnLine(stringResource(Res.string.custom_radio_count_low, formatCount(text.songs)))
					is CountText.Partial -> Text(
						stringResource(Res.string.custom_radio_count_partial, formatCount(text.songs)),
						style = style, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
					)
					is CountText.Counted -> Text(
						stringResource(Res.string.custom_radio_count, formatCount(text.songs)),
						style = style, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
					)
				}
				CountState.Unknown -> WarnLine(stringResource(Res.string.custom_radio_count_unknown))
				CountState.Unavailable, CountState.Failed ->
					Text(stringResource(Res.string.custom_radio_count_unavailable), style = style, color = muted)
			}
		}
	}
}

/** One mood filter word, a chip like the queue's. */
@Composable
private fun FilterWord(text: String, selected: Boolean, onClick: () -> Unit) {
	FilterChip(
		selected = selected,
		onClick = onClick,
		label = { Text(text, maxLines = 1) },
		shape = MaterialTheme.shapes.small,
		leadingIcon = if (selected) {
			{ Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
		} else null
	)
}

@Composable
private fun WarnLine(text: String) {
	Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
	Text(
		text,
		style = MaterialTheme.typography.bodyMedium,
		fontWeight = FontWeight.Bold,
		color = MaterialTheme.colorScheme.error,
		textAlign = TextAlign.Center
	)
}

/** 1,084 rather than 1084: the count is the line's subject. */
internal fun formatCount(n: Int): String =
	n.toString().reversed().chunked(3).joinToString(",").reversed()
