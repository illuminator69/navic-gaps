package paige.navic.ui.screens.search.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.action_clear_search
import navic.composeapp.generated.resources.action_navigate_back
import navic.composeapp.generated.resources.title_search
import org.jetbrains.compose.resources.stringResource
import paige.navic.di.LocalNavStack
import paige.navic.icons.Icons
import paige.navic.icons.outlined.ArrowBack
import paige.navic.icons.outlined.Close
import paige.navic.ui.components.common.SearchHeaderAvatar
import paige.navic.ui.theme.defaultFont

@Composable
fun SearchScreenTopBar(
	query: TextFieldState,
	nested: Boolean,
	onSearch: (String) -> Unit
) {
	val backStack = LocalNavStack.current

	val focusManager = LocalFocusManager.current
	val focusRequester = remember { FocusRequester() }

	LaunchedEffect(Unit) {
		focusRequester.requestFocus()
	}

	// System Back on an active root search clears it first, as the arrow does; only an empty
	// Search lets Back leave the page.
	NavigationBackHandler(
		state = rememberNavigationEventState(NavigationEventInfo.None),
		isBackEnabled = !nested && query.text.isNotEmpty(),
		onBackCompleted = {
			focusManager.clearFocus(true)
			query.clearText()
		}
	)

	Row(
		verticalAlignment = Alignment.CenterVertically
	) {
		// Leading slot: a nested Search has a back arrow; the root Search page leads with the avatar
		// like every other root page (P1). Its header is its own bar, so it places the avatar itself.
		// While a search is active (anything typed) the avatar steps aside for a back arrow that
		// clears the search, back to Browse (your note on P3: no avatar on an active search).
		if (!nested && query.text.isNotEmpty()) {
			Box(
				modifier = Modifier.size(56.dp),
				contentAlignment = Alignment.Center
			) {
				IconButton(
					onClick = {
						focusManager.clearFocus(true)
						query.clearText()
					}
				) {
					Icon(
						Icons.Outlined.ArrowBack,
						contentDescription = stringResource(Res.string.action_clear_search),
						tint = MaterialTheme.colorScheme.onSurfaceVariant
					)
				}
			}
		} else if (!nested) {
			// The same slot as every other root page's (HeaderAvatar, P6), not centred in a 56 dp box:
			// that put the monogram 8 dp left of the other pages'.
			SearchHeaderAvatar()
		} else {
			Box(
				modifier = Modifier.size(56.dp),
				contentAlignment = Alignment.Center
			) {
				IconButton(
					onClick = {
						focusManager.clearFocus(true)
						if (backStack.size > 1) backStack.removeLastOrNull()
					}
				) {
					Icon(
						Icons.Outlined.ArrowBack,
						contentDescription = stringResource(Res.string.action_navigate_back),
						tint = MaterialTheme.colorScheme.onSurfaceVariant
					)
				}
			}
		}
		BasicTextField(
			state = query,
			modifier = Modifier
				.weight(1f)
				.height(72.dp)
				.focusRequester(focusRequester),
			lineLimits = TextFieldLineLimits.SingleLine,
			keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
			onKeyboardAction = {
				focusManager.clearFocus()
				if (query.text.isNotBlank()) {
					onSearch(query.text.toString())
				}
			},
			textStyle = TextStyle(
				color = MaterialTheme.colorScheme.onSurface,
				fontFamily = defaultFont()
			),
			cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
			decorator = { innerTextField ->
				Box(contentAlignment = Alignment.CenterStart) {
					if (query.text.isEmpty()) {
						Text(
							text = stringResource(Res.string.title_search),
							color = MaterialTheme.colorScheme.onSurfaceVariant
						)
					}
					innerTextField()
				}
			}
		)
		Box(
			modifier = Modifier.size(56.dp),
			contentAlignment = Alignment.Center
		) {
			if (query.text.isNotEmpty()) {
				IconButton(
					modifier = Modifier.padding(horizontal = 8.dp),
					onClick = {
						query.clearText()
					}
				) {
					Icon(
						Icons.Outlined.Close,
						contentDescription = stringResource(Res.string.action_clear_search)
					)
				}
			}
		}
	}
}
