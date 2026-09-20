package paige.navic.util.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.runtime.staticCompositionLocalOf

@OptIn(ExperimentalMaterial3Api::class)
val LocalSheetState = staticCompositionLocalOf<SheetState> {
	error("LocalSheetState used outside of a sheet")
}
