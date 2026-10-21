package paige.navic.ui.components.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/*
 * navi-connect P6: the root pages' avatar in ONE place, so its edge is the same on every page.
 *
 * The top app bar puts its navigation slot [HeaderAvatarDefaults.SlotStart] in from the screen's edge
 * and centres nothing: the 48 dp [AvatarButton] (a 40 dp monogram inside) sits at the slot's start,
 * so [HeaderAvatarDefaults.Inset] more lines the monogram's left edge up with the title's, 16 dp in
 * (P5 round 3). The Search page draws its own header, so it places the avatar in a slot that starts
 * at the same x ([SearchHeaderAvatar]); it used to centre the button in a 56 dp box at the row's
 * start, which put the monogram 8 dp left of every other page's.
 */
object HeaderAvatarDefaults {
	/** Where the top app bar's navigation slot starts. */
	val SlotStart = 4.dp

	/** How far into the slot the 48 dp button sits: 4 + 8 + (48 - 40) / 2 = 16 dp to the monogram. */
	val Inset = 8.dp
}

/** The avatar as a top app bar's navigation icon (`RootTopBar`). */
@Composable
fun HeaderAvatar(modifier: Modifier = Modifier) {
	AvatarButton(modifier.padding(start = HeaderAvatarDefaults.Inset))
}

/** The avatar at the start of a header that is not a top app bar (Search's): the slot's start, then [HeaderAvatar]. */
@Composable
fun SearchHeaderAvatar(modifier: Modifier = Modifier) {
	Box(modifier.padding(start = HeaderAvatarDefaults.SlotStart)) {
		HeaderAvatar()
	}
}
