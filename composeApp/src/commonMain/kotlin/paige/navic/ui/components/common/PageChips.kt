package paige.navic.ui.components.common

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Check

/**
 * navi-connect: a one-of choice as a row of chips, drawn as upstream's search categories are
 * (`SearchScreenChips`): the small shape, a check on the selected chip, its width eased (not when
 * the row wraps). The fork's pages use it for every such choice (Downloads' sections, Stats' tabs
 * and periods, Fresh's scope), so there is one chip style in the app rather than one per page.
 *
 * The row scrolls sideways only when the chips are wider than it, at a large font on a phone;
 * with [wrap] (inside a sheet, where every choice should be in view) it wraps onto more lines.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun <T> PageChips(
	options: List<T>,
	selected: T,
	label: @Composable (T) -> String,
	onSelect: (T) -> Unit,
	modifier: Modifier = Modifier,
	enabled: (T) -> Boolean = { true },
	contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
	wrap: Boolean = false
) {
	val chips: @Composable () -> Unit = {
		options.forEach { option ->
			val isSelected = option == selected
			FilterChip(
				modifier = if (wrap) Modifier else Modifier.animateContentSize(
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
	if (wrap) {
		// Where the chips wrap, a chip's width changes at once. Material's FilterChip eases its
		// check in and out itself, the arriving one on a bouncy spring and the leaving one more
		// slowly, so for a moment the two together were wider than before and after; in a row
		// that is full (the new mix form's characters: 1283 of 1284 px) the last chip broke onto
		// another line and came back. The check still fades in.
		val motion = MaterialTheme.motionScheme
		MaterialExpressiveTheme(
			colorScheme = MaterialTheme.colorScheme,
			motionScheme = remember(motion) { SizeAtOnceMotion(motion) },
			shapes = MaterialTheme.shapes,
			typography = MaterialTheme.typography
		) {
			FlowRow(
				modifier = modifier
					.fillMaxWidth()
					.padding(contentPadding)
					.selectableGroup(),
				horizontalArrangement = Arrangement.spacedBy(8.dp)
			) { chips() }
		}
	} else {
		Row(
			modifier = modifier
				.fillMaxWidth()
				.horizontalScroll(rememberScrollState())
				.padding(contentPadding)
				.selectableGroup(),
			horizontalArrangement = Arrangement.spacedBy(8.dp)
		) { chips() }
	}
}

/**
 * [base] with no easing of size: the spatial specs, and the default effects spec Material's chip
 * shrinks a leaving icon by, snap.
 */
private class SizeAtOnceMotion(base: MotionScheme) : MotionScheme by base {
	override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = snap()
	override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = snap()
	override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = snap()
	override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = snap()
}
