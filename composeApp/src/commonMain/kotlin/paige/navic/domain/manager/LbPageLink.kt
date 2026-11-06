package paige.navic.domain.manager

/*
 * Worbus P4 N4 (design §6.4, §6.5): lb-bot's failed imports reach Needs attention as one row with a
 * link to lb-bot's own list. No import action is taken here; the page asks for its own sign-in.
 */

/** lb-bot's SPA route for "Imports that need you". */
const val LB_IMPORTS_ROUTE = "#/needs/imports"

/** The port lb-bot's page is published on beside the hub in the packaged server. */
const val LB_PAGE_PORT = 8899

/**
 * Where "Open in lb-bot" goes for the imports list, or null when there is nowhere to go:
 *  - the hub's [webUrl] (`LBBOT_WEB_URL`) when it is non-empty;
 *  - else, only while [activeAddress] (the address the socket is on) is private by [isPrivate]
 *    (project 3's test), `http://<that address's host>:8899`: the packaged page lives on the
 *    hub's own host;
 *  - else (away from home with no `LBBOT_WEB_URL`, or no socket) null.
 */
fun lbPageLink(webUrl: String?, activeAddress: String?, isPrivate: (String) -> Boolean): String? {
	val base = webUrl?.trim()?.trimEnd('/')?.ifEmpty { null }
	if (base != null) return "$base/$LB_IMPORTS_ROUTE"
	val address = activeAddress?.trim()?.ifEmpty { null } ?: return null
	if (!isPrivate(address)) return null
	val host = hubAddressHost(address) ?: return null
	val shownHost = if (':' in host) "[$host]" else host
	return "http://$shownHost:$LB_PAGE_PORT/$LB_IMPORTS_ROUTE"
}

/** Needs attention's imports row: how many, and where its button goes (null: no button, the home-network line). */
data class ImportsRow(val count: Int, val link: String?)

/** The row, only while lb-bot counts at least one import that needs the user. */
fun importsRow(page: AttentionPage.Rows, link: String?): ImportsRow? =
	page.imports.takeIf { it > 0 }?.let { ImportsRow(it, link) }

/** The Server screen's version line's value: `welcome.server.version`, or null for no line. */
fun serverVersionShown(version: String?): String? = version?.trim()?.ifEmpty { null }
