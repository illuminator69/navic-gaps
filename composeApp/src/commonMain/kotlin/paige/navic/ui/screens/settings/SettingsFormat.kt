package paige.navic.ui.screens.settings

import kotlin.math.absoluteValue
import kotlin.math.round

/** A gain in dB as the settings print it: "+1.5 dB", "-3.0 dB", "0.0 dB". */
fun decibelsLabel(decibels: Float): String {
	val rounded = round(decibels * 10) / 10
	val sign = if (rounded < 0) "-" else if (rounded > 0) "+" else ""
	return "$sign${rounded.absoluteValue} dB"
}

/** An equaliser band limit: the player reports it in millibels; people read decibels (100 mB = 1 dB). */
fun millibelsLabel(millibels: Float): String {
	val decibels = millibels / 100f
	return if (decibels == decibels.toInt().toFloat()) "${decibels.toInt()} dB" else "$decibels dB"
}

/** The scrobble threshold on its 10 % grid, so the slider and what is stored agree. */
fun snapToTenPercent(fraction: Float): Float = round(fraction.coerceIn(0f, 1f) * 10) / 10
