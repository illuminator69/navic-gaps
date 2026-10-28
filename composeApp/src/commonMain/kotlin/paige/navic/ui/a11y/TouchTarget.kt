package paige.navic.ui.a11y

/** The least a tappable may measure each way, dp (Material, Android's accessibility scanner). */
const val MIN_TOUCH_DP = 48f

/** What a tappable of [widthDp] by [heightDp] must take: at least [MIN_TOUCH_DP] each way (audit L1-12). */
fun touchTarget(widthDp: Float, heightDp: Float): Pair<Float, Float> =
	maxOf(widthDp, MIN_TOUCH_DP) to maxOf(heightDp, MIN_TOUCH_DP)
