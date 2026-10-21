package paige.navic.ui.components.layouts

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import paige.navic.ui.components.common.HeaderAvatar

@Composable
fun RootTopBar(
	title: @Composable () -> Unit,
	scrollBehavior: TopAppBarScrollBehavior,
	actions: @Composable RowScope.() -> Unit = {},
) {
	MediumFlexibleTopAppBar(
		title = {
			CompositionLocalProvider(
				LocalTextStyle provides when (LocalTextStyle.current) {
					MaterialTheme.typography.headlineMedium -> MaterialTheme.typography.headlineSmall
					else -> MaterialTheme.typography.titleLarge
				}
			) {
				title()
			}
		},
		// P1: the account's avatar leads every root page; it opens the one menu that holds the rare
		// tools, Settings and Log out. Search is a bar button now, so the page keeps only its own
		// actions (sort, view mode...).
		// Its place is HeaderAvatar's, shared with the Search page's header (P6): the monogram's edge
		// is the title's, 16 dp in.
		navigationIcon = { HeaderAvatar() },
		actions = actions,
		scrollBehavior = scrollBehavior,
		// TRANSPARENT at rest, so the page's cover gradient runs unbroken up under the status
		// bar. The default `containerColor` is `surface` — the scheme's own neutral — which over
		// a gradient painted from `coverAmbientGradient` is a visibly darker, flatter band across
		// the top of the page, status bar included. The album page's bar has always been
		// transparent-until-scrolled for the same reason (`collection/components/TopBar.kt`), and
		// this is the browsing pages' version of it.
		//
		// Safe with cover theming off too: the bar then shows the Scaffold's `background`, which
		// in a generated scheme is the same tone as the `surface` it used to paint.
		// Fully transparent, both states: the page behind this bar is the cover ambient, which is
		// a gradient composited over blurred artwork — no flat colour can match it, and any
		// container colour draws a band across the top of the page. Nothing is lost by dropping
		// it: the collapsing bar takes up the scroll offset, so content does not end up under the
		// title. Content that paints an opaque page background WOULD break this, which is why the
		// list screens no longer do (see AlbumListScreen).
		colors = TopAppBarDefaults.topAppBarColors(
			containerColor = Color.Transparent,
			scrolledContainerColor = Color.Transparent
		),
	)
}
