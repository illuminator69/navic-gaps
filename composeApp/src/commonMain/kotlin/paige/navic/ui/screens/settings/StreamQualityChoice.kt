package paige.navic.ui.screens.settings

import paige.navic.di.PlatformType
import paige.navic.domain.models.settings.StreamingQuality

/** The two networks a stream quality is chosen for. */
enum class StreamNetwork { Wifi, Cellular }

/** What one network streams at: one of the presets, or an exact bitrate and container. */
sealed interface StreamChoice {
	data class Preset(val quality: StreamingQuality) : StreamChoice

	/** [bitrate] in kbps, 0 = the server's own; [format] "" = the server's own container. */
	data class Custom(val bitrate: Int, val format: String) : StreamChoice
}

/** Bitrates the Custom dialog offers, kbps; 0 = Original. */
val CUSTOM_BITRATES = listOf(0, 320, 256, 192, 160, 128, 96)

/** Containers the Custom dialog offers. [value] is what Navidrome is asked for; "" = Original. */
enum class StreamFormat(val value: String) {
	Original(""),
	Opus("opus"),
	Mp3("mp3"),
	Aac("aac");

	companion object {
		/** The format a stored value names, or null for one the dialog does not offer. */
		fun of(value: String): StreamFormat? = entries.firstOrNull { it.value.equals(value.trim(), ignoreCase = true) }
	}
}

/** iOS cannot decode Opus. */
fun streamFormatsFor(platform: PlatformType): List<StreamFormat> =
	if (platform == PlatformType.IOS) StreamFormat.entries - StreamFormat.Opus else StreamFormat.entries

/** The bitrate and container a preset asks the server for on [platform], as the custom keys hold them. */
fun StreamingQuality.equivalent(platform: PlatformType): Pair<Int, String> =
	if (platform == PlatformType.IOS) bitrateIos to containerIos.orEmpty()
	else bitrateAndroid to containerAndroid.orEmpty()

/**
 * The stored keys behind the streaming choices, copied out so the mapping is a plain function.
 * `advanced` is ONE flag for both networks (the player reads it that way): while it is on, each
 * network streams at its own custom bitrate and format and the presets are not read.
 */
data class StreamingKeys(
	val presetWifi: StreamingQuality = StreamingQuality.Lossless,
	val presetCellular: StreamingQuality = StreamingQuality.Lossless,
	val advanced: Boolean = false,
	val bitrateWifi: Int = 0,
	val bitrateCellular: Int = 0,
	val formatWifi: String = "",
	val formatCellular: String = ""
)

/** What [network] streams at now. */
fun StreamingKeys.choice(network: StreamNetwork, platform: PlatformType): StreamChoice {
	if (!advanced) {
		return StreamChoice.Preset(if (network == StreamNetwork.Wifi) presetWifi else presetCellular)
	}
	val bitrate = if (network == StreamNetwork.Wifi) bitrateWifi else bitrateCellular
	val format = if (network == StreamNetwork.Wifi) formatWifi else formatCellular
	val preset = StreamingQuality.entries.firstOrNull { quality ->
		val (presetBitrate, presetFormat) = quality.equivalent(platform)
		presetBitrate == bitrate && presetFormat.equals(format.trim(), ignoreCase = true)
	}
	return if (preset != null) StreamChoice.Preset(preset) else StreamChoice.Custom(bitrate, format)
}

/**
 * The keys after [network] is set to [choice]. What the other network streams at never changes:
 *  - Custom with the flag off turns it on, and first copies the other network's preset into its
 *    custom keys (the same request), so it keeps streaming as it did;
 *  - a preset with the flag on is written into the network's custom keys (the preset's own request);
 *  - the flag is cleared, and the presets stored, as soon as both networks read as presets AND their
 *    stored custom text is exactly the preset's request. A network that reads as a preset only
 *    because "Opus" or " aac " matches ignoring case and spaces keeps the flag on: with it off the
 *    player would send the lowercase preset text instead of what is stored.
 */
fun StreamingKeys.choose(network: StreamNetwork, choice: StreamChoice, platform: PlatformType): StreamingKeys {
	val wifi = network == StreamNetwork.Wifi
	var keys = this
	if (!advanced && choice is StreamChoice.Custom) {
		val (otherBitrate, otherFormat) = (if (wifi) presetCellular else presetWifi).equivalent(platform)
		keys = if (wifi) keys.copy(bitrateCellular = otherBitrate, formatCellular = otherFormat)
		else keys.copy(bitrateWifi = otherBitrate, formatWifi = otherFormat)
	}
	val (bitrate, format) = when (choice) {
		is StreamChoice.Preset -> choice.quality.equivalent(platform)
		is StreamChoice.Custom -> choice.bitrate to choice.format
	}
	if (!advanced && choice is StreamChoice.Preset) {
		// Nothing custom to protect: the flag stays off and only the preset moves.
		return if (wifi) keys.copy(presetWifi = choice.quality) else keys.copy(presetCellular = choice.quality)
	}
	keys = if (wifi) keys.copy(advanced = true, bitrateWifi = bitrate, formatWifi = format)
	else keys.copy(advanced = true, bitrateCellular = bitrate, formatCellular = format)
	if (choice !is StreamChoice.Preset) return keys
	keys = if (wifi) keys.copy(presetWifi = choice.quality) else keys.copy(presetCellular = choice.quality)
	val nowWifi = keys.choice(StreamNetwork.Wifi, platform)
	val nowCellular = keys.choice(StreamNetwork.Cellular, platform)
	return if (nowWifi is StreamChoice.Preset && nowCellular is StreamChoice.Preset &&
		keys.storedExactly(StreamNetwork.Wifi, nowWifi.quality, platform) &&
		keys.storedExactly(StreamNetwork.Cellular, nowCellular.quality, platform)
	) {
		keys.copy(advanced = false, presetWifi = nowWifi.quality, presetCellular = nowCellular.quality)
	} else keys
}

/** Whether [network]'s stored custom bitrate and format are, character for character, what [quality] asks for. */
private fun StreamingKeys.storedExactly(network: StreamNetwork, quality: StreamingQuality, platform: PlatformType): Boolean {
	val (bitrate, format) = quality.equivalent(platform)
	return if (network == StreamNetwork.Wifi) bitrateWifi == bitrate && formatWifi == format
	else bitrateCellular == bitrate && formatCellular == format
}
