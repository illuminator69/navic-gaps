package paige.navic.util

import io.ktor.client.HttpClientConfig
import io.ktor.client.call.replaceResponse
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.statement.HttpReceivePipeline
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Fill in the `coverArt` a Subsonic album or song left out, before the client library parses it.
 *
 * `dev.zt64.subsonic` declares `coverArt` REQUIRED on its `Album` model, but a server omits it
 * for an album with no artwork — which is exactly what a freshly placed lb-bot download often
 * is. One such album failed its whole 500-album `getAlbumList2` page with a
 * MissingFieldException, and that aborted the entire library sync, every time, for as long as
 * the album existed. Navidrome serves cover art by the item's own id (a placeholder when
 * there is none), so the id is a faithful stand-in rather than an invented value.
 *
 * Only `album`, `song` and `entry` objects are touched, and only where the field is absent.
 * Artists and playlists are left alone: a missing cover there is not known to break parsing.
 */
@OptIn(InternalAPI::class)
fun HttpClientConfig<*>.installSubsonicResponseRepair() {
	install(
		createClientPlugin("SubsonicResponseRepair") {
			client.receivePipeline.intercept(HttpReceivePipeline.After) { response ->
				// JSON only: stream/getCoverArt bodies are audio and images and must never be buffered.
				val type = response.contentType() ?: return@intercept
				if (!type.match(ContentType.Application.Json)) return@intercept
				val bytes = response.rawContent.readRemaining().readByteArray()
				val repaired = repairSubsonicJson(bytes.decodeToString())?.encodeToByteArray() ?: bytes
				val headers = Headers.build {
					appendAll(response.headers)
					remove(HttpHeaders.ContentLength)
				}
				proceedWith(response.call.replaceResponse(headers) { ByteReadChannel(repaired) }.response)
			}
		}
	)
}

private val ART_KEYS = setOf("album", "song", "entry")

/** The repaired document, or null when nothing needed repairing (the common case). */
internal fun repairSubsonicJson(text: String): String? {
	if (ART_KEYS.none { "\"$it\"" in text }) return null
	val root = try {
		Json.parseToJsonElement(text)
	} catch (_: Exception) {
		return null // not ours to judge; let the real parser report it
	}
	var changed = false

	fun withArt(item: JsonElement): JsonElement {
		if (item !is JsonObject || "coverArt" in item) return item
		val id = (item["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return item
		changed = true
		return JsonObject(item + ("coverArt" to JsonPrimitive(id)))
	}

	fun walk(element: JsonElement): JsonElement = when (element) {
		is JsonObject -> JsonObject(element.mapValues { (key, value) ->
			val inner = walk(value)
			if (key !in ART_KEYS) inner
			else when (inner) {
				// A song's "album" is its album NAME, a string — withArt leaves non-objects alone.
				is JsonObject -> withArt(inner)
				is JsonArray -> JsonArray(inner.map(::withArt))
				else -> inner
			}
		})
		is JsonArray -> JsonArray(element.map(::walk))
		else -> element
	}

	val out = walk(root)
	return if (changed) out.toString() else null
}
