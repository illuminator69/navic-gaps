package paige.navic.domain.manager

import io.ktor.http.Url

/**
 * What the login form's Instance URL field holds, read without touching the network
 * (B-296, B-261, B-283, B-302).
 *
 * The Log in button used to hand the raw text to `Uri.host` and `InetAddress.getByName`, so a
 * typo ("http://127.0.0.1:4534sandbox", a name that does not resolve) threw out of the click's
 * coroutine and killed the app. Every address now goes through [normalizeInstanceUrl] first,
 * and nothing here throws or resolves a name.
 */
sealed interface InstanceUrlResult {
	/** [url] is what the session is created with: a scheme, a host, maybe a port and a path. */
	data class Ok(val url: String, val host: String) : InstanceUrlResult
	data class Invalid(val reason: InstanceUrlProblem) : InstanceUrlResult
}

enum class InstanceUrlProblem {
	/** Nothing typed, or only spaces. */
	BLANK,
	/** Something typed that is not a server address (a bad host or port, an unsupported scheme). */
	MALFORMED
}

/** A scheme typed by the person. Checked by hand: Ktor reads `nas:4533` as the scheme `nas`. */
private val TYPED_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
private val HOST_LABEL = Regex("^[A-Za-z0-9_]([A-Za-z0-9_-]*[A-Za-z0-9_])?$")
/**
 * An IPv6 literal as an address to log in to: hex, colons and a dotted IPv4 tail, and no zone id.
 * `fe80::1%wlan0` parses in Ktor and then fails in OkHttp's URL parser, as "Something went wrong."
 * (the review's finding 5), so a zone reads as not an address before anything dials.
 */
private val IPV6_BODY = Regex("^[0-9A-Fa-f:.]+$")

/**
 * Trim, give an address with no scheme one (the scheme rule, B-302), check the host and port,
 * and let Ktor parse the result.
 *
 * The scheme rule: a host typed with no scheme that is loopback ([isLoopbackHost]), on a private
 * range or a single-label name ([isPrivateHost]) gets `http://`, because that is a server on
 * this network and Navidrome serves plain http on 4533 by default; any other host gets
 * `https://`. A typed `http://` or `https://` is kept as typed; any other typed scheme is
 * [InstanceUrlProblem.MALFORMED], since the session speaks only those two.
 */
fun normalizeInstanceUrl(raw: String): InstanceUrlResult {
	val text = raw.trim()
	if (text.isEmpty()) return InstanceUrlResult.Invalid(InstanceUrlProblem.BLANK)
	if (text.any { it.isWhitespace() }) return InstanceUrlResult.Invalid(InstanceUrlProblem.MALFORMED)

	val typed = TYPED_SCHEME.find(text)
	val scheme = typed?.value?.removeSuffix("://")?.lowercase()
	if (scheme != null && scheme != "http" && scheme != "https") {
		return InstanceUrlResult.Invalid(InstanceUrlProblem.MALFORMED)
	}
	val rest = if (typed != null) text.substring(typed.value.length) else text

	val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
	val authority = rest.substring(0, authorityEnd).substringAfterLast('@')
	val tail = rest.substring(authorityEnd).trimEnd('/')
	val hostPort = splitHostPort(authority) ?: return InstanceUrlResult.Invalid(InstanceUrlProblem.MALFORMED)
	val (host, port) = hostPort

	val chosenScheme = scheme ?: if (isLoopbackHost(host) || isPrivateHost(host)) "http" else "https"
	val hostPart = if (host.contains(':')) "[$host]" else host
	val url = buildString {
		append(chosenScheme).append("://").append(hostPart)
		if (port != null) append(':').append(port)
		append(tail)
	}
	// Ktor is the parser the session's client uses: an address it cannot read is not one.
	val parsed = runCatching { Url(url) }.getOrNull() ?: return InstanceUrlResult.Invalid(InstanceUrlProblem.MALFORMED)
	if (parsed.host.isBlank()) return InstanceUrlResult.Invalid(InstanceUrlProblem.MALFORMED)
	return InstanceUrlResult.Ok(url, host)
}

/**
 * The host and port of an authority (`host`, `host:port`, `[v6]:port`, a bare v6 literal), or null
 * when either does not read as one. The host comes back without brackets.
 */
private fun splitHostPort(authority: String): Pair<String, Int?>? {
	if (authority.isEmpty()) return null
	val host: String
	val portText: String?
	if (authority.startsWith("[")) {
		val close = authority.indexOf(']')
		if (close < 0) return null
		host = authority.substring(1, close)
		val after = authority.substring(close + 1)
		portText = when {
			after.isEmpty() -> null
			after.startsWith(":") -> after.substring(1)
			else -> return null
		}
		if (!isIpv6Literal(host)) return null
	} else if (authority.count { it == ':' } >= 2) {
		// A v6 literal typed without brackets carries no port.
		if (!isIpv6Literal(authority)) return null
		host = authority
		portText = null
	} else {
		host = authority.substringBefore(':')
		portText = if (authority.contains(':')) authority.substringAfter(':') else null
		if (!isHostName(host)) return null
	}
	val port = when (portText) {
		null -> null
		else -> {
			if (portText.isEmpty() || portText.length > 5 || !portText.all { it.isDigit() }) return null
			portText.toInt().takeIf { it in 1..65535 } ?: return null
		}
	}
	return host to port
}

private fun isHostName(host: String): Boolean {
	if (host.isEmpty() || host.length > 253) return false
	val labels = host.removeSuffix(".").split('.')
	return labels.all { it.length in 1..63 && HOST_LABEL.matches(it) }
}

private fun isIpv6Literal(host: String): Boolean =
	host.contains(':') && IPV6_BODY.matches(host) && ipv6FirstHextet(host) != null

/** The four numbers of a dotted-quad IPv4 literal, or null for anything else. */
private fun ipv4Octets(host: String): List<Int>? {
	val parts = host.split('.')
	if (parts.size != 4) return null
	val octets = parts.map { part ->
		if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return null
		part.toInt().takeIf { it in 0..255 } ?: return null
	}
	return octets
}

/** The first 16 bits of an IPv6 literal (brackets and a zone id allowed), or null. */
private fun ipv6FirstHextet(host: String): Int? {
	val bare = host.removePrefix("[").removeSuffix("]").substringBefore('%')
	if (!bare.contains(':')) return null
	if (bare.startsWith("::")) return 0
	val first = bare.substringBefore(':')
	if (first.isEmpty() || first.length > 4) return null
	return first.toIntOrNull(16)
}

/** An IPv4 address mapped into IPv6 (`::ffff:192.168.1.2`), as its IPv4 part. */
private fun mappedIpv4(host: String): String? {
	val bare = host.removePrefix("[").removeSuffix("]").substringBefore('%').lowercase()
	return if (bare.startsWith("::ffff:")) bare.removePrefix("::ffff:").takeIf { ipv4Octets(it) != null } else null
}

/** This device: `localhost`, 127/8, `::1`. */
fun isLoopbackHost(host: String): Boolean {
	val h = host.trim().removeSuffix(".").lowercase()
	if (h == "localhost" || h.endsWith(".localhost")) return true
	ipv4Octets(h)?.let { return it[0] == 127 }
	mappedIpv4(h)?.let { return isLoopbackHost(it) }
	val bare = h.removePrefix("[").removeSuffix("]").substringBefore('%')
	return bare == "::1" || bare == "0:0:0:0:0:0:0:1"
}

/**
 * A host on this network rather than the internet (B-108): what Android 17's local network
 * permission covers, and what the scheme rule gives plain http.
 *
 * True for 10/8, 172.16/12, 192.168/16, 169.254/16 (link-local) and 100.64/10 (carrier-grade
 * NAT, which is also where Tailscale puts its addresses); fc00::/7 and fe80::/10; a `.local`
 * name; and a single-label name (`nas`), which only a local resolver answers. Loopback is not
 * private here: it never leaves the device ([isLoopbackHost]). A name with dots is judged by
 * its spelling alone: nothing here resolves it, so a public-looking name that resolves to a
 * home address reads as not private.
 *
 * This replaces `InetAddress.isLinkLocalAddress`, which covered 169.254/16 and fe80::/10 only, so
 * a Navidrome at 192.168.x never asked for the permission, and which resolved the name, so a
 * typo crashed the app.
 */
fun isPrivateHost(host: String): Boolean {
	val h = host.trim().removeSuffix(".").lowercase()
	if (h.isEmpty()) return false
	ipv4Octets(h)?.let { return isPrivateIpv4(it) }
	mappedIpv4(h)?.let { return isPrivateIpv4(ipv4Octets(it)!!) }
	if (h.contains(':')) {
		val first = ipv6FirstHextet(h) ?: return false
		return (first and 0xFE00) == 0xFC00 || (first and 0xFFC0) == 0xFE80
	}
	if (isLoopbackHost(h)) return false
	if (h.endsWith(".local")) return true
	return !h.contains('.') && isHostName(h)
}

private fun isPrivateIpv4(o: List<Int>): Boolean = when {
	o[0] == 10 -> true
	o[0] == 172 && o[1] in 16..31 -> true
	o[0] == 192 && o[1] == 168 -> true
	o[0] == 169 && o[1] == 254 -> true
	o[0] == 100 && o[1] in 64..127 -> true
	else -> false
}

/**
 * Is Android's local network protection what stands between this app and [host]? (B-108)
 *
 * True iff the system enforces the permission (Android 17, API 37, and later), it is not
 * granted, and the host is on this network ([isPrivateHost]). Callers ask this only after a
 * connection failed, so it decides which sentence explains the failure, never whether to try.
 * Same shape as cast discovery's `castDiscoveryAllowed`.
 */
fun localNetworkBlocked(sdk: Int, granted: Boolean, host: String?): Boolean =
	sdk >= LOCAL_NETWORK_PERMISSION_SDK && !granted && host != null && isPrivateHost(host)

/** The API level from which Android asks for `ACCESS_LOCAL_NETWORK`. */
const val LOCAL_NETWORK_PERMISSION_SDK = 37

/**
 * What the system says about the local network permission, for code that is not a screen
 * ([HubManager], [LoginManager]). [PermissionManager] cannot be that: it needs an activity when
 * it is built, and HubManager is built at process start. Each platform module registers one.
 */
interface LocalNetworkAccess {
	/** The platform's API level where it has one (Android's `SDK_INT`); 0 elsewhere. */
	val sdk: Int

	/** True when this app may reach the local network (always, below [LOCAL_NETWORK_PERMISSION_SDK]). */
	fun granted(): Boolean
}

/**
 * The host of a stored address such as the hub's `ws://10.0.2.2:4899`, or null. Never throws.
 * Ktor reads a string with no scheme (or an empty one) as a path on `localhost`, so one is required.
 */
fun hostOfUrl(url: String): String? {
	val text = url.trim()
	if (!TYPED_SCHEME.containsMatchIn(text)) return null
	return runCatching { Url(text).host }.getOrNull()?.takeIf { it.isNotBlank() }
		?.removePrefix("[")?.removeSuffix("]")
}
