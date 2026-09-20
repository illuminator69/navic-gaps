package paige.navic.domain.models.settings

import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.option_list_view_mode_grid
import navic.composeapp.generated.resources.option_list_view_mode_list
import org.jetbrains.compose.resources.StringResource

enum class ListViewMode(val displayName: StringResource) {
	Grid(Res.string.option_list_view_mode_grid),
	List(Res.string.option_list_view_mode_list)
}
