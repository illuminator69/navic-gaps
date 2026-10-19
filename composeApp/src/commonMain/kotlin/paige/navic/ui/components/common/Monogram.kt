package paige.navic.ui.components.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Person
import paige.navic.ui.theme.defaultFont

/**
 * A circle with a name's first two characters, or a person where the name has none (an account
 * whose username is empty). 56 dp unless the caller gives it a size: the first size modifier wins.
 */
@Composable
fun Monogram(
	modifier: Modifier = Modifier,
	text: String
) {
	val initials = monogramInitials(text)
	Surface(
		modifier = modifier.size(56.dp),
		color = MaterialTheme.colorScheme.secondary,
		shape = CircleShape
	) {
		Box(
			modifier = Modifier.fillMaxSize(),
			contentAlignment = Alignment.Center
		) {
			if (initials.isEmpty()) {
				Icon(
					imageVector = Icons.Outlined.Person,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.onSecondary,
					modifier = Modifier.fillMaxSize(0.55f)
				)
			} else {
				Text(
					text = initials,
					fontFamily = defaultFont(grade = 100, round = 100f),
					fontSize = 18.sp,
					modifier = Modifier.semantics { hideFromAccessibility() }
				)
			}
		}
	}
}

/**
 * The first two characters of [text], upper-cased. Empty for an empty or blank name. A character
 * outside the basic plane (an emoji) is taken whole, never as half of its surrogate pair.
 */
internal fun monogramInitials(text: String): String {
	val name = text.trim()
	val out = StringBuilder()
	var i = 0
	var taken = 0
	while (i < name.length && taken < 2) {
		val c = name[i]
		if (c.isHighSurrogate() && i + 1 < name.length && name[i + 1].isLowSurrogate()) {
			out.append(c).append(name[i + 1])
			i += 2
		} else {
			out.append(c)
			i++
		}
		taken++
	}
	return out.toString().uppercase()
}
