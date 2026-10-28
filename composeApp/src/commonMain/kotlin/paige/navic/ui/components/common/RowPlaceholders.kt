package paige.navic.ui.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Waiting rows: a list that has not loaded yet draws these, never an empty line or nothing. Grey on
 * any page (the owner's call, 2026-10-28): a list row has no cover of its own to tint the box from,
 * so the tinted boxes behind covers (`placeholderBrush`) read as colour for its own sake here.
 */

/** How many rows a waiting list draws. */
const val ROW_PLACEHOLDERS = 3

/** A waiting row's colour: neutral grey over the page, white on a dark one and black on a light one. */
fun rowPlaceholderColor(darkPage: Boolean): Color =
	if (darkPage) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)

/** One waiting row, a song row's height by default. */
@Composable
fun RowPlaceholder(modifier: Modifier = Modifier, height: Dp = 72.dp) {
	val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
	Box(
		modifier
			.fillMaxWidth()
			.height(height)
			.clip(MaterialTheme.shapes.medium)
			.background(rowPlaceholderColor(dark))
	)
}

/** [count] waiting rows as lazy items, keyed under [key]. */
fun LazyListScope.rowPlaceholders(key: String, count: Int = ROW_PLACEHOLDERS, height: Dp = 72.dp) {
	items(count, key = { "$key-placeholder-$it" }) { RowPlaceholder(height = height) }
}
