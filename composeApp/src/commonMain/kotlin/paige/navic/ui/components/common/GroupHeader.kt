package paige.navic.ui.components.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * navi-connect: a group's title inside a fork list page (Downloads' groups, Fresh's weeks), in the
 * library home's `header()` style, with its count and its actions at the end.
 */
@Composable
fun GroupHeader(
	title: String,
	count: Int? = null,
	modifier: Modifier = Modifier,
	actions: @Composable RowScope.() -> Unit = {}
) {
	Row(
		modifier = modifier
			.fillMaxWidth()
			.heightIn(min = 48.dp)
			.padding(start = 4.dp, top = 12.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		Text(
			title,
			style = MaterialTheme.typography.titleMediumEmphasized,
			fontWeight = FontWeight(600),
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier
				.weight(1f, fill = false)
				.semantics { heading() }
		)
		if (count != null) {
			Text(
				"  $count",
				style = MaterialTheme.typography.titleMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
		}
		Spacer(Modifier.weight(1f))
		actions()
	}
}
