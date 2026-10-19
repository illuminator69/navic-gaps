package paige.navic.ui.util

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/*
 * navi-connect P1: a cover tile is a tinted box from its first frame, the real cover fading in over
 * it, so a slow image leaves a coloured tile and never a hole. The tint is a pure function of the
 * name: the same album is the same colour every time, and different albums are usually different.
 */

/** 32-bit FNV-1a over the lower-cased name, modulo 360. */
fun placeholderHue(name: String): Int {
	var hash = 0x811C9DC5L
	for (ch in name.lowercase()) {
		hash = hash xor ch.code.toLong()
		hash = (hash * 0x01000193L) and 0xFFFFFFFFL
	}
	return (hash % 360L).toInt()
}

/** The two ends of the placeholder's gradient: the name's hue, then the hue 30 degrees on and darker. */
internal fun placeholderColors(name: String): Pair<Color, Color> {
	val hue = placeholderHue(name)
	return Color.hsl(hue.toFloat(), 0.30f, 0.32f) to
		Color.hsl(((hue + 30) % 360).toFloat(), 0.30f, 0.20f)
}

/** A 135 degree linear gradient between [placeholderColors]. */
fun placeholderBrush(name: String): Brush {
	val (from, to) = placeholderColors(name)
	return Brush.linearGradient(
		colors = listOf(from, to),
		start = Offset.Zero,
		end = Offset.Infinite
	)
}
