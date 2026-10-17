package paige.navic.ui.navigation

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * Which full-screen sheets hide everything under them (I-041, performance).
 *
 * The now-playing sheet and the queue sheet are ModalBottomSheets, i.e. windows of their own, and a
 * window that is covered keeps drawing. With the queue open over the player, the player's rotating
 * 80 dp blur and its moving wave, and the mini player's progress under both, kept the one
 * RenderThread busy on every frame, and the queue got what was left: measured on the emulator in
 * one boot, the queue window drew 94-104 frames in an 8-fling scroll while the two windows under it
 * drew ~570; with them held, 229-230 while they drew none. Each sheet reports here
 * whether it is fully open over the whole screen; what sits under one ([rememberCoveredBySheet])
 * holds its endless animations until it can be seen again. Nothing visible stops: a sheet stops
 * covering on the first frame it moves off the top.
 */
object SheetCover {
	/** The depth ([LocalSheetDepth]) of every sheet that currently covers the whole screen. */
	private val covering = mutableStateMapOf<Int, Unit>()

	internal fun report(depth: Int, covers: Boolean) {
		if (covers) covering[depth] = Unit else covering.remove(depth)
	}

	/** Whether a sheet above [depth] (opened later, on top of it) covers the whole screen. */
	fun coveredAbove(depth: Int): Boolean = covering.keys.any { it > depth }
}

/**
 * How many back-stack entries lie under the sheet this content is composed in: 0 for the pages of
 * the main window, the sheet scene's `previousEntries.size` inside a sheet.
 */
val LocalSheetDepth = compositionLocalOf { 0 }

/** True while a sheet opened on top of this content hides it completely ([SheetCover]). */
@Composable
fun rememberCoveredBySheet(): Boolean {
	val depth = LocalSheetDepth.current
	val covered by remember(depth) { derivedStateOf { SheetCover.coveredAbove(depth) } }
	return covered
}

/**
 * [value] while this content can be seen, and the value it last showed while a full-screen sheet
 * hides it ([rememberCoveredBySheet]). For what changes on every playback tick (the position): the
 * hidden window then stops redrawing five times a second, and shows the live value again on the
 * frame the sheet moves off.
 */
@Composable
fun <T> heldWhileCoveredBySheet(value: T): T {
	val covered = rememberCoveredBySheet()
	val shown = remember { HeldValue(value) }
	if (!covered) shown.value = value
	return shown.value
}

private class HeldValue<T>(var value: T)

/**
 * Reports to [SheetCover] whether the sheet driven by [sheetState] covers the whole screen: open
 * and settled, its top at the top of the screen, as wide as the screen ([sheetWidthPx], the
 * sheet's content width; a tablet's narrower sheet leaves the page beside it showing), and
 * [opaque] (the lyrics sheet is see-through).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReportSheetCover(depth: Int, sheetState: SheetState, sheetWidthPx: () -> Int, opaque: Boolean = true) {
	val windowInfo = LocalWindowInfo.current
	val covers by remember(sheetState, opaque, windowInfo) {
		derivedStateOf {
			opaque &&
				sheetState.currentValue == SheetValue.Expanded &&
				sheetState.targetValue == SheetValue.Expanded &&
				(runCatching { sheetState.requireOffset() }.getOrNull() ?: Float.MAX_VALUE) <= 1f &&
				sheetWidthPx() >= windowInfo.containerSize.width - 1
		}
	}
	DisposableEffect(depth, covers) {
		SheetCover.report(depth, covers)
		onDispose { SheetCover.report(depth, false) }
	}
}
