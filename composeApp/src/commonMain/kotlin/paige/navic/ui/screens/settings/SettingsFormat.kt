package paige.navic.ui.screens.settings

import kotlin.math.absoluteValue
import kotlin.math.round
import kotlin.math.roundToInt

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

/**
 * The percentage printed beside the scrobble slider: the stored value itself. ScrobbleManager and
 * CastScrobbler compare a play against the stored fraction as it is, so a 0.37 stored before the slider
 * had steps counts a play at 37 % and must read 37 %, not the 40 % it would snap to. Only what the
 * person moves the slider to is snapped ([snapToTenPercent]).
 */
fun scrobblePercentShown(stored: Float): Int = (stored.coerceIn(0f, 1f) * 100).roundToInt()
