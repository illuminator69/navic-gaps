package paige.navic.ui.util

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToLong

/*
 * navi-connect P1 (the shell and Home), brief section 6: how a page's rows and tiles arrive.
 *
 * Colour is in the first frame (I-043); structure then assembles in sequence. A tile rises 8 dp and
 * fades in over 240 ms, the first five 30 ms apart so the page reads as a left-to-right sweep, and
 * everything after them together at 150 ms, because a stagger down a long list is a wait. Pages P2 to
 * P4 reuse this, so it lives in ui/util and knows nothing about Home.
 *
 * Reduced motion is the system animation scale: Compose reads it into the coroutine's
 * [MotionDurationScale], so a scale of 0 snaps and any other scale stretches the durations (and, here,
 * the stagger, which `delay` would not stretch on its own).
 */

/** How far a tile travels while it fades in. */
const val ARRIVAL_RISE_DP = 8

/** How long one tile takes to arrive. */
const val ARRIVAL_MS = 240

/** The stagger between neighbours, for the first [ARRIVAL_STAGGERED] of them. */
const val ARRIVAL_STAGGER_MS = 30

/** How many tiles are staggered; the rest arrive together. */
const val ARRIVAL_STAGGERED = 5

/** When everything after the staggered ones starts. */
const val ARRIVAL_REST_MS = 150

/** A real cover fades in over its tinted placeholder (`CoverArt.crossfadeMs`). */
const val COVER_FADE_MS = 280

val ArrivalEasing = CubicBezierEasing(0.2f, 0.7f, 0.2f, 1f)

/** Folds and rolled-out boxes (Fold.kt); the brief's `cubic-bezier(.3,.7,.2,1)`. */
val FoldEasing = CubicBezierEasing(0.3f, 0.7f, 0.2f, 1f)

/** Milliseconds before the tile at [index] starts arriving; a negative index is the first. */
fun arrivalDelayMs(index: Int): Int = when {
	index < 0 -> 0
	index < ARRIVAL_STAGGERED -> index * ARRIVAL_STAGGER_MS
	else -> ARRIVAL_REST_MS
}

/**
 * Which tiles have arrived on this page. A tile's identity is its key (a cover id, a release-group
 * id), never its position, so a list that reorders or refreshes does not replay its arrival.
 * Keys are compared by their string form, so the gate can be saved with the page.
 */
class ArrivalGate private constructor(private val seen: MutableSet<String>) {
	constructor() : this(mutableSetOf())

	/** True the first time [key] is claimed, false after. */
	fun claim(key: Any): Boolean = seen.add(key.toString())

	/**
	 * Marks every key as arrived. A shelf calls it once its first frame is up, so a tile that only
	 * scrolls into view later appears at once instead of playing an arrival of its own.
	 */
	fun claimAll(keys: Iterable<Any>) {
		keys.forEach { seen.add(it.toString()) }
	}

	fun toSaved(): List<String> = seen.toList()

	companion object {
		fun fromSaved(saved: List<String>): ArrivalGate = ArrivalGate(saved.toMutableSet())
	}
}

/**
 * One gate per page visit. It is saved with the entry, so a pushed page that covers the page and a
 * Back that brings it back do not replay the arrival; a tab switch clears the back stack and so
 * starts a new visit.
 */
@Composable
fun rememberArrivalGate(): ArrivalGate = rememberSaveable(
	saver = listSaver(save = { it.toSaved() }, restore = { ArrivalGate.fromSaved(it) })
) { ArrivalGate() }

/**
 * Rise-and-fade arrival for the tile [key] at [index] in its row, the first time the page shows it.
 * A key the gate has already seen is fully visible at once. The progress is read in the graphics
 * layer, so the animation recomposes nothing.
 */
@Composable
fun Modifier.arrive(gate: ArrivalGate, key: Any, index: Int): Modifier {
	// The scope in composition carries the same MotionDurationScale as the effect below, so a scale
	// of 0 is known before the first frame: without it the tile starts at alpha 0 and the effect, a
	// frame later, snaps it on, which is one frame of missing content for someone on reduced motion
	// (seen on the AVD, 2026-10-19).
	val scope = rememberCoroutineScope()
	val plays = remember(gate, key) {
		// Claimed even when motion is off: the tile has arrived, whatever the setting does next.
		gate.claim(key) && scope.coroutineContext[MotionDurationScale]?.scaleFactor != 0f
	}
	val progress = remember(gate, key) { Animatable(if (plays) 0f else 1f) }
	if (plays) {
		LaunchedEffect(gate, key) {
			val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
			if (scale == 0f) {
				progress.snapTo(1f)
			} else {
				delay((arrivalDelayMs(index) * scale).roundToLong())
				progress.animateTo(1f, tween(ARRIVAL_MS, easing = ArrivalEasing))
			}
		}
	}
	val risePx = with(LocalDensity.current) { ARRIVAL_RISE_DP.dp.toPx() }
	return graphicsLayer {
		val p = progress.value
		alpha = p
		translationY = (1f - p) * risePx
	}
}
