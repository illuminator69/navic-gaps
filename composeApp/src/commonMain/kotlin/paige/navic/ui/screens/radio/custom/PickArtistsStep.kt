package paige.navic.ui.screens.radio.custom

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_close
import navic.composeapp.generated.resources.custom_radio_max
import navic.composeapp.generated.resources.custom_radio_most_played
import navic.composeapp.generated.resources.custom_radio_next
import navic.composeapp.generated.resources.custom_radio_next_none
import navic.composeapp.generated.resources.custom_radio_no_match
import navic.composeapp.generated.resources.custom_radio_pick_title
import navic.composeapp.generated.resources.custom_radio_search
import org.jetbrains.compose.resources.stringResource
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Check
import paige.navic.icons.outlined.ChevronForward
import paige.navic.icons.outlined.Close
import paige.navic.icons.outlined.Search
import paige.navic.domain.models.DomainArtist
import paige.navic.ui.components.common.CoverArt
import paige.navic.ui.util.arrive
import paige.navic.ui.util.rememberArrivalGate

/**
 * Pick (board RadioPick): the library's artists, the most played first, three to a row, a local
 * search, and "Next, N picked" pinned at the foot. At [CUSTOM_RADIO_MAX_ARTISTS] the rest dim and
 * a line says so; a picked one can always be unpicked.
 */
@Composable
internal fun PickArtistsStep(
	viewModel: CustomRadioViewModel,
	onNext: () -> Unit,
	onClose: () -> Unit
) {
	val artists by viewModel.artists.collectAsStateWithLifecycle()
	val picked by viewModel.picked.collectAsStateWithLifecycle()
	var query by rememberSaveable { mutableStateOf("") }
	val pickedIds = remember(picked) { picked.mapTo(HashSet()) { it.id } }
	val full = picked.size >= CUSTOM_RADIO_MAX_ARTISTS
	val shown = remember(artists, query) {
		val q = query.trim()
		artists.orEmpty().filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
	}
	val gate = rememberArrivalGate()

	Scaffold(
		containerColor = Color.Transparent,
		contentWindowInsets = WindowInsets(0),
		topBar = {
			Row(
				Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 8.dp),
				verticalAlignment = Alignment.CenterVertically
			) {
				Text(
					stringResource(Res.string.custom_radio_pick_title),
					style = MaterialTheme.typography.headlineSmall,
					fontWeight = FontWeight.Bold,
					modifier = Modifier.weight(1f)
				)
				IconButton(onClick = onClose) {
					Icon(Icons.Outlined.Close, contentDescription = stringResource(Res.string.action_close))
				}
			}
		}
	) { inner ->
		Box(Modifier.fillMaxSize().padding(top = inner.calculateTopPadding())) {
			LazyVerticalGrid(
				columns = GridCells.Fixed(3),
				modifier = Modifier.fillMaxSize(),
				contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				verticalArrangement = Arrangement.spacedBy(16.dp)
			) {
				item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
					TextField(
						value = query,
						onValueChange = { query = it },
						modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
						placeholder = { Text(stringResource(Res.string.custom_radio_search)) },
						leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
						singleLine = true,
						shape = RoundedCornerShape(28.dp),
						keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
						colors = TextFieldDefaults.colors(
							focusedIndicatorColor = Color.Transparent,
							unfocusedIndicatorColor = Color.Transparent,
							disabledIndicatorColor = Color.Transparent
						)
					)
				}
				item(key = "label", span = { GridItemSpan(maxLineSpan) }) {
					Text(
						if (full) stringResource(Res.string.custom_radio_max, CUSTOM_RADIO_MAX_ARTISTS)
						else stringResource(Res.string.custom_radio_most_played),
						style = MaterialTheme.typography.labelLarge,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.padding(top = 4.dp)
					)
				}
				if (artists != null && shown.isEmpty()) {
					item(key = "none", span = { GridItemSpan(maxLineSpan) }) {
						Text(
							stringResource(Res.string.custom_radio_no_match),
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							textAlign = TextAlign.Center,
							modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp)
						)
					}
				}
				itemsIndexed(shown, key = { _, it -> it.id }) { index, artist ->
					val isPicked = artist.id in pickedIds
					PickTile(
						artist = artist,
						picked = isPicked,
						dimmed = full && !isPicked,
						onClick = { viewModel.toggle(artist) },
						modifier = Modifier.arrive(gate, artist.id, index).animateItem()
					)
				}
			}
			// The pinned button, over a fade into the page so the grid passes under it.
			Box(
				Modifier
					.align(Alignment.BottomCenter)
					.fillMaxWidth()
					.background(
						Brush.verticalGradient(
							0f to Color.Transparent,
							0.4f to MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
						)
					)
					.navigationBarsPadding()
					.padding(start = 16.dp, end = 16.dp, top = 30.dp, bottom = 20.dp)
			) {
				Button(
					onClick = onNext,
					enabled = picked.isNotEmpty(),
					modifier = Modifier.fillMaxWidth().height(52.dp),
					shape = RoundedCornerShape(26.dp)
				) {
					Text(
						if (picked.isEmpty()) stringResource(Res.string.custom_radio_next_none)
						else stringResource(Res.string.custom_radio_next, picked.size),
						style = MaterialTheme.typography.titleMedium
					)
					Spacer(Modifier.width(6.dp))
					Icon(Icons.Outlined.ChevronForward, contentDescription = null, modifier = Modifier.size(20.dp))
				}
			}
		}
	}
}

/** One artist: a round picture with a ring and a check when picked, the name under it. */
@Composable
private fun PickTile(
	artist: DomainArtist,
	picked: Boolean,
	dimmed: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier
) {
	val ring by animateColorAsState(
		if (picked) MaterialTheme.colorScheme.primary else Color.Transparent, label = "ring"
	)
	val alpha by animateFloatAsState(if (dimmed) 0.38f else 1f, label = "dim")
	Column(
		modifier = modifier
			.alpha(alpha)
			.semantics { selected = picked }
			.clickable(enabled = !dimmed, role = Role.Checkbox, onClick = onClick),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(8.dp)
	) {
		Box {
			Box(
				Modifier
					.size(98.dp)
					.border(3.dp, ring, CircleShape)
					.padding(6.dp)
			) {
				CoverArt(
					modifier = Modifier.size(86.dp),
					coverArtId = artist.coverArtId,
					contentDescription = null,
					shape = CircleShape,
					isArtist = true,
					placeholderName = artist.name
				)
			}
			if (picked) {
				Box(
					Modifier
						.align(Alignment.TopEnd)
						.size(26.dp)
						.background(MaterialTheme.colorScheme.primary, CircleShape),
					contentAlignment = Alignment.Center
				) {
					Icon(
						Icons.Outlined.Check,
						contentDescription = null,
						tint = MaterialTheme.colorScheme.onPrimary,
						modifier = Modifier.size(16.dp)
					)
				}
			}
		}
		Text(
			artist.name,
			style = MaterialTheme.typography.labelLarge,
			fontWeight = FontWeight.SemiBold,
			textAlign = TextAlign.Center,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis
		)
	}
}
