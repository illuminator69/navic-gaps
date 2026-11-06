package paige.navic.domain.models.settings

import androidx.compose.runtime.Composable
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.option_quality_high
import navic.composeapp.generated.resources.option_quality_lossless
import navic.composeapp.generated.resources.option_quality_low
import navic.composeapp.generated.resources.option_quality_medium
import org.jetbrains.compose.resources.StringResource

enum class StreamingQuality(
	val displayName: StringResource,
	val bitrateAndroid: Int,
	val containerAndroid: String?
) {
	Low(
		displayName = Res.string.option_quality_low,
		bitrateAndroid = 80,
		containerAndroid = "opus"
	),
	Medium(
		displayName = Res.string.option_quality_medium,
		bitrateAndroid = 128,
		containerAndroid = "opus"
	),
	High(
		displayName = Res.string.option_quality_high,
		bitrateAndroid = 192,
		containerAndroid = "opus"
	),
	Lossless(
		displayName = Res.string.option_quality_lossless,
		bitrateAndroid = 0,
		containerAndroid = null
	)
}

@Composable
fun StreamingQuality.description(): String? {
	return if (containerAndroid != null) {
		"${bitrateAndroid}kbps, ${containerAndroid.uppercase()}"
	} else {
		null
	}
}
