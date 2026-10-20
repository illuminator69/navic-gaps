package paige.navic.ui.screens.radio.components

import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.ui.Modifier
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.info_no_radios
import org.jetbrains.compose.resources.stringResource
import paige.navic.domain.models.DomainRadio
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Radio
import paige.navic.ui.components.common.ContentUnavailable
import paige.navic.ui.components.common.SegmentedListItemDefaults.segmentedShapes
import paige.navic.ui.core.UiState

fun LazyGridScope.radioListScreenContent(
	state: UiState<List<DomainRadio>>,
	onRadioClick: (DomainRadio) -> Unit,
	onRadioMenu: (DomainRadio) -> Unit
) {
	val data = state.data.orEmpty()

	if (data.isNotEmpty()) {
		itemsIndexed(data, key = { _, it -> it.id }) { index, radio ->
			RadioRow(
				modifier = Modifier.animateItem(),
				radio = radio,
				shapes = segmentedShapes(index, data.size),
				onPlay = { onRadioClick(radio) },
				onMenu = { onRadioMenu(radio) }
			)
		}
	} else if (state !is UiState.Loading) {
		item(span = { GridItemSpan(maxLineSpan) }) {
			ContentUnavailable(
				icon = Icons.Outlined.Radio,
				label = stringResource(Res.string.info_no_radios)
			)
		}
	}
}
