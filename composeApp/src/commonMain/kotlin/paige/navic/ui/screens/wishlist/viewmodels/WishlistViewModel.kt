package paige.navic.ui.screens.wishlist.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import paige.navic.domain.manager.LbBotManager
import paige.navic.domain.manager.LbWishlistItem

data class WishlistUi(
	val loading: Boolean = true,
	/** False when lb-bot is absent or the hub does not carry the route. */
	val supported: Boolean = true,
	val items: List<LbWishlistItem> = emptyList()
)

/**
 * The wishlist: releases nobody was sharing, which lb-bot keeps looking for.
 *
 * Entirely lb-bot-owned — there is no local copy and no optimistic write. A remove
 * re-reads rather than mutating the list in place, because the authoritative answer
 * is one round trip away and a local edit that disagreed with it would be
 * indistinguishable from a removal that silently failed.
 */
class WishlistViewModel(
	private val lbBotManager: LbBotManager
) : ViewModel() {
	private val _state = MutableStateFlow(WishlistUi())
	val state = _state.asStateFlow()

	init {
		load()
		// A landed fill is exactly what empties this list, and it arrives as the
		// hub's `library` frame rather than as anything this screen did.
		viewModelScope.launch {
			lbBotManager.libraryRevision.collect { if (!_state.value.loading) load() }
		}
	}

	fun load() {
		viewModelScope.launch {
			_state.value = _state.value.copy(loading = true)
			val up = lbBotManager.ensureAvailability() && lbBotManager.supportsWishlist
			if (!up) {
				_state.value = WishlistUi(loading = false, supported = false)
				return@launch
			}
			_state.value = WishlistUi(
				loading = false,
				supported = true,
				items = lbBotManager.wishlist().sortedByDescending { it.addedAt }
			)
		}
	}

	/**
	 * Remove, and adopt the list lb-bot hands back.
	 *
	 * No re-read and no optimistic local edit: the write route answers with the whole
	 * updated wishlist, so the authoritative list is already in hand. A failed remove
	 * returns null and the current list is left exactly as it was — a row that did not
	 * go must not vanish, or the next refresh brings it back and reads as a bug.
	 */
	fun remove(rgid: String) {
		viewModelScope.launch {
			val updated = lbBotManager.wishlistRemove(rgid) ?: return@launch
			_state.value = _state.value.copy(items = updated.sortedByDescending { it.addedAt })
		}
	}
}
