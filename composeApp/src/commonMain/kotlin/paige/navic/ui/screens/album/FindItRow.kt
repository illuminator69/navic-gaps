package paige.navic.ui.screens.album

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import navic.composeapp.generated.resources.Res
import navic.composeapp.generated.resources.find_it_search
import navic.composeapp.generated.resources.find_it_service_amazon
import navic.composeapp.generated.resources.find_it_service_apple_music
import navic.composeapp.generated.resources.find_it_service_bandcamp
import navic.composeapp.generated.resources.find_it_service_beatport
import navic.composeapp.generated.resources.find_it_service_deezer
import navic.composeapp.generated.resources.find_it_service_qobuz
import navic.composeapp.generated.resources.find_it_service_soundcloud
import navic.composeapp.generated.resources.find_it_service_spotify
import navic.composeapp.generated.resources.find_it_service_tidal
import navic.composeapp.generated.resources.find_it_service_youtube_music
import navic.composeapp.generated.resources.find_it_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import paige.navic.domain.manager.LbFindItLink
import paige.navic.icons.Icons
import paige.navic.icons.outlined.Link
import paige.navic.icons.outlined.Search

/*
 * Worbus P1 (N4): "Find it" on a missing album's page. lb-bot's `findIt` (`/lb/meta/album`) lists where
 * the record can be found: links MusicBrainz confirms on the release group's releases, then a search on
 * each of six services that has no confirmed link. Confirmed links come first and say the service's name;
 * a search says it is one ("Search Bandcamp"). Its own section under the About row, never inside the
 * About sheet (which draws nothing without an article), so a late answer moves nothing above it; no
 * `findIt` is no section. A tap opens the browser.
 */

/** What a Find-it chip says: the service's name, a confirmed `other` link's host, or a search on a service. */
sealed interface FindItLabel {
	data class Name(val name: StringResource) : FindItLabel
	data class Host(val host: String) : FindItLabel
	data class Search(val name: StringResource) : FindItLabel
}

data class FindItEntry(val service: String, val url: String, val confirmed: Boolean, val label: FindItLabel)

/** The service ids lb-bot sends, as names. `other` has none: it says its host. */
private val FIND_IT_SERVICES: Map<String, StringResource> = mapOf(
	"bandcamp" to Res.string.find_it_service_bandcamp,
	"qobuz" to Res.string.find_it_service_qobuz,
	"apple-music" to Res.string.find_it_service_apple_music,
	"spotify" to Res.string.find_it_service_spotify,
	"deezer" to Res.string.find_it_service_deezer,
	"youtube-music" to Res.string.find_it_service_youtube_music,
	"tidal" to Res.string.find_it_service_tidal,
	"amazon" to Res.string.find_it_service_amazon,
	"beatport" to Res.string.find_it_service_beatport,
	"soundcloud" to Res.string.find_it_service_soundcloud
)

/**
 * The host of a web link, lowercased and without `www.`: `https://www.discogs.com/release/9` →
 * `discogs.com`, `https://user@host:8080/x` → `host`. "" when [url] is not an `http`/`https` link with a
 * host: the scheme is checked here too, so a label never names a host a chip will not open.
 */
internal fun hostOf(url: String): String {
	val schemeEnd = url.indexOf("://")
	if (schemeEnd <= 0) return ""
	val scheme = url.substring(0, schemeEnd).lowercase()
	if (scheme != "http" && scheme != "https") return ""
	// The authority ends at the path, the query or the fragment; a browser reads `\` as `/` too.
	val authority = url.substring(schemeEnd + 3).takeWhile { it !in "/?#\\" }
	val hostPort = authority.substringAfterLast('@')
	val host = if (hostPort.startsWith('[')) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
	return host.lowercase().removePrefix("www.")
}

/**
 * Whether a Find-it link may be opened: an `http` or `https` link (any case) with a host, and nothing in
 * it a browser or Android's URI parser could read differently from this check (spaces, control characters,
 * a backslash, which a browser reads as `/` and a URI parser does not). lb-bot builds these
 * from MusicBrainz's URL relations, which anyone can edit: a `javascript:`, `intent:` or `file:` link
 * must never reach the system's URI handler.
 */
internal fun findItUrlSafe(url: String): Boolean =
	url.none { it.code <= 0x20 || it.code == 0x7f || it == '\\' } && hostOf(url).let { it.isNotBlank() && it != "[]" }

/**
 * The chips, in order: confirmed links, then searches, each in lb-bot's order. Null or empty is none. A
 * link without a URL or that is not a web link ([findItUrlSafe]), a kind this build does not know, and a
 * search on a service it cannot name are left out.
 */
fun findItEntries(links: List<LbFindItLink>?): List<FindItEntry> {
	if (links.isNullOrEmpty()) return emptyList()
	val entries = links.mapNotNull { link ->
		if (link.url.isBlank() || !findItUrlSafe(link.url)) return@mapNotNull null
		val name = FIND_IT_SERVICES[link.service]
		when (link.kind) {
			"confirmed" -> FindItEntry(
				link.service, link.url, confirmed = true,
				label = name?.let { FindItLabel.Name(it) } ?: FindItLabel.Host(hostOf(link.url))
			)
			"search" -> name?.let { FindItEntry(link.service, link.url, confirmed = false, label = FindItLabel.Search(it)) }
			else -> null
		}
	}
	return entries.filter { it.confirmed } + entries.filterNot { it.confirmed }
}

@Composable
private fun FindItLabel.text(): String = when (this) {
	is FindItLabel.Name -> stringResource(name)
	is FindItLabel.Host -> host
	is FindItLabel.Search -> stringResource(Res.string.find_it_search, stringResource(name))
}

/**
 * The section as one lazy item ("find-it"), under the About row. Nothing at all when [links] has no
 * entry. It arrives with the item's own fade (its slot below the About row is new), never into a slot
 * something else was drawn in.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun LazyListScope.findItRow(links: List<LbFindItLink>?) {
	val entries = findItEntries(links)
	if (entries.isEmpty()) return
	item(key = "find-it") {
		val uriHandler = LocalUriHandler.current
		Column(
			modifier = Modifier
				.animateItem()
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 8.dp)
		) {
			// The About row's heading style: one page, one kind of heading.
			Text(
				stringResource(Res.string.find_it_title),
				style = MaterialTheme.typography.titleMediumEmphasized,
				fontWeight = FontWeight(600),
				color = MaterialTheme.colorScheme.primary,
				modifier = Modifier.padding(bottom = 4.dp)
			)
			FlowRow(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(8.dp)
			) {
				entries.forEach { entry ->
					AssistChip(
						onClick = { runCatching { uriHandler.openUri(entry.url) } },
						label = { Text(entry.label.text(), maxLines = 1) },
						leadingIcon = {
							Icon(
								if (entry.confirmed) Icons.Outlined.Link else Icons.Outlined.Search,
								contentDescription = null,
								modifier = Modifier.size(18.dp)
							)
						}
					)
				}
			}
		}
	}
}
