package paige.navic.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.home_moods_clear
import org.jetbrains.compose.resources.stringResource
import paige.navic.domain.manager.MoodEntry
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Close

/**
 * Home's mood chips (P7, board HomeMood): the hub's ten Home moods in its order, one row that scrolls
 * sideways, the selected one filled. While one is selected a close chip leads the row, and the row
 * goes back to its start so the selection and the way out are both in view. Drawn only while the
 * catalogue says AudioMuse is there (`chipsVisible`); otherwise Home has no row and no gap.
 */
@Composable
fun MoodChipRow(
	moods: List<MoodEntry>,
	selected: String?,
	onTap: (String) -> Unit,
	onClear: () -> Unit,
	modifier: Modifier = Modifier
) {
	val state = rememberLazyListState()
	LaunchedEffect(selected != null) {
		if (selected != null) state.animateScrollToItem(0)
	}
	val clear = stringResource(Res.string.home_moods_clear)
	LazyRow(
		modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
		state = state,
		contentPadding = PaddingValues(horizontal = 16.dp),
		horizontalArrangement = Arrangement.spacedBy(8.dp)
	) {
		if (selected != null) {
			item(key = "clear") {
				FilterChip(
					modifier = Modifier.animateItem(),
					selected = false,
					onClick = onClear,
					label = {
						Icon(
							Icons.Outlined.Close,
							contentDescription = clear,
							modifier = Modifier.size(FilterChipDefaults.IconSize)
						)
					},
					shape = MaterialTheme.shapes.small
				)
			}
		}
		items(moods, key = { it.id }) { mood ->
			FilterChip(
				modifier = Modifier.animateItem(),
				selected = mood.id == selected,
				onClick = { onTap(mood.id) },
				label = { Text(mood.label, maxLines = 1) },
				shape = MaterialTheme.shapes.small
			)
		}
	}
}
