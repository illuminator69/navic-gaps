package paige.navic.ui.components.common

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Check

/**
 * navi-connect: a one-of choice as a row of chips, drawn as upstream's search categories are
 * (`SearchScreenChips`): the small shape, a check on the selected chip, its width eased. The fork's
 * pages use it for every such choice (Downloads' sections, My Listening's tabs and periods, Fresh's
 * scope), so there is one chip style in the app rather than one per page.
 *
 * The row scrolls sideways only when the chips are wider than it, at a large font on a phone.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> PageChips(
	options: List<T>,
	selected: T,
	label: @Composable (T) -> String,
	onSelect: (T) -> Unit,
	modifier: Modifier = Modifier,
	enabled: (T) -> Boolean = { true },
	contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp)
) {
	Row(
		modifier = modifier
			.fillMaxWidth()
			.horizontalScroll(rememberScrollState())
			.padding(contentPadding)
			.selectableGroup(),
		horizontalArrangement = Arrangement.spacedBy(8.dp)
	) {
		options.forEach { option ->
			val isSelected = option == selected
			FilterChip(
				modifier = Modifier.animateContentSize(
					if (isSelected) MaterialTheme.motionScheme.fastSpatialSpec()
					else MaterialTheme.motionScheme.defaultEffectsSpec()
				),
				selected = isSelected,
				onClick = { onSelect(option) },
				enabled = enabled(option),
				label = { Text(label(option), maxLines = 1) },
				shape = MaterialTheme.shapes.small,
				leadingIcon = if (isSelected) {
					{
						Icon(
							imageVector = Icons.Outlined.Check,
							contentDescription = null,
							modifier = Modifier.size(FilterChipDefaults.IconSize)
						)
					}
				} else null
			)
		}
	}
}
