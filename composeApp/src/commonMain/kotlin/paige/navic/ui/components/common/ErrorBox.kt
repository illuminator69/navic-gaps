package paige.navic.ui.components.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.materialkolor.ktx.darken
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.info_error
import org.jetbrains.compose.resources.stringResource
import paige.navic.icons.Icons
import paige.navic.icons.outlined.KeyboardArrowDown
import paige.navic.icons.outlined.Refresh
import paige.navic.ui.core.UiState
import paige.navic.util.Logger

@Composable
fun <T> ErrorBox(
	error: UiState.Error<T>,
	padding: PaddingValues = PaddingValues(12.dp),
	bottomPadding: Dp = 24.dp,
	onRetry: (() -> Unit)? = null,
	modifier: Modifier = Modifier,
	// B-302: what failed, in one sentence, where the caller knows; else "Something went wrong.".
	message: String? = null,
	// Small text under the message (the login names the exception's class for an unknown kind).
	detail: String? = null,
	// One action under the message (the login's Open settings, B-108).
	actionLabel: String? = null,
	onAction: (() -> Unit)? = null
) {
	var expanded by rememberSaveable { mutableStateOf(false) }
	val iconScale by animateFloatAsState(
		targetValue = if (expanded) -1f else 1f,
		animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()
	)

	LaunchedEffect(error.error) {
		Logger.e("ErrorBox", "Printing stack trace for error", error.error)
	}

	Column(
		modifier = modifier
			.padding(padding)
			.padding(bottom = bottomPadding)
			.fillMaxWidth()
			.clip(MaterialTheme.shapes.largeIncreased)
	) {
		Surface(
			modifier = Modifier,
			color = MaterialTheme.colorScheme.errorContainer,
			shape = MaterialTheme.shapes.small
		) {
			Row(
				modifier = Modifier.padding(14.dp),
				horizontalArrangement = Arrangement.Center,
				verticalAlignment = Alignment.CenterVertically
			) {
				Column(modifier = Modifier.weight(1f)) {
					Text(message ?: stringResource(Res.string.info_error))
					detail?.let {
						Text(
							it,
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f)
						)
					}
					if (actionLabel != null && onAction != null) {
						TextButton(onClick = onAction) { Text(actionLabel) }
					}
				}
				onRetry?.let { onRetry ->
					IconButton(
						onClick = onRetry,
						content = { Icon(Icons.Outlined.Refresh, null) },
						colors = IconButtonDefaults.iconButtonColors(
							containerColor = MaterialTheme.colorScheme.errorContainer.darken(1.25f)
						)
					)
				}
				IconButton(
					onClick = { expanded = !expanded },
					content = {
						Icon(
							imageVector = Icons.Outlined.KeyboardArrowDown,
							contentDescription = null,
							modifier = Modifier.scale(scaleX = 1f, scaleY = iconScale)
						)
					},
					colors = IconButtonDefaults.iconButtonColors(
						containerColor = MaterialTheme.colorScheme.errorContainer.darken(1.25f)
					)
				)
			}
		}
		AnimatedVisibility(expanded) {
			Spacer(Modifier.height(3.dp))
		}
		AnimatedVisibility(expanded) {
			Surface(
				color = MaterialTheme.colorScheme.surfaceContainer,
				shape = MaterialTheme.shapes.small
			) {
				ErrorCodeBlock(error.error)
			}
		}
	}
}
