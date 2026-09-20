package paige.navic.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.mikepenz.aboutlibraries.ui.compose.produceLibraries
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.title_acknowledgements
import org.jetbrains.compose.resources.stringResource
import paige.navic.ui.components.dialogs.LinkConfirmationDialog
import paige.navic.ui.components.layouts.NestedTopBar
import paige.navic.ui.components.sheets.LibrarySheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAcknowledgementsScreen() {
	val libraries by produceLibraries {
		Res.readBytes("files/acknowledgements.json").decodeToString()
	}
	var selectedLibrary by remember { mutableStateOf<Library?>(null) }
	var linkToOpen by rememberSaveable { mutableStateOf<String?>(null) }

	Scaffold(
		topBar = { NestedTopBar({ Text(stringResource(Res.string.title_acknowledgements)) }) }
	) { innerPadding ->
		LibrariesContainer(
			libraries = libraries,
			modifier = Modifier.fillMaxSize(),
			contentPadding = innerPadding,
			onLibraryClick = { library ->
				selectedLibrary = library
				// consume
				return@LibrariesContainer true
			}
		)
	}

	if (selectedLibrary != null) {
		LibrarySheet(
			library = selectedLibrary!!,
			onDismissRequest = { selectedLibrary = null },
			onOpenLink = { linkToOpen = it }
		)
	}

	if (linkToOpen != null) {
		LinkConfirmationDialog(
			linkToOpen = linkToOpen!!,
			onDismissRequest = { linkToOpen = null }
		)
	}
}
