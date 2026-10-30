package paige.navic.di

import coil3.disk.DiskCache
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.size.Dimension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * The sizes a Subsonic cover is asked for at (adapted from upstream `6dcfa9f4`, `9793592a`,
 * `d3a0a6de`): a few, so tiles of nearly the same width share one file on disk.
 */
internal val COVER_SIZE_BUCKETS = listOf(50, 100, 200, 512, 1024)

/**
 * The size to ask for a cover drawn [widthPx] wide: the smallest bucket at or above it, so nothing
 * is ever stretched, and never one at or above [cap] (the size the URL already asks for, the
 * user's cover-art quality). Null = ask as the URL does: the target is as wide as the cap, or
 * wider than every bucket under it.
 */
internal fun coverSizeBucket(widthPx: Int, cap: Int?): Int? =
	COVER_SIZE_BUCKETS.firstOrNull { it >= widthPx && (cap == null || it < cap) }

/** The disk and memory key of a cover asked for at [size]. The bare id is the cap's (B-038). */
internal fun sizedCoverKey(id: String, size: Int): String = "$id#size=$size"

private val COVER_ART_PATH = Regex("""/rest/getCoverArt(\.view)?(\?|$)""")
private val ID_PARAM = Regex("""[?&]id=([^&#]*)""")
private val SIZE_PARAM = Regex("""([?&])size=(\d*)""")

/** A Subsonic `getCoverArt` URL's id and its `size`, or null for any other URL. */
internal fun parseCoverArtUrl(url: String): Pair<String, Int?>? {
	if (!COVER_ART_PATH.containsMatchIn(url)) return null
	val id = ID_PARAM.find(url)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() } ?: return null
	return decodePercent(id) to SIZE_PARAM.find(url)?.groupValues?.get(2)?.toIntOrNull()
}

/** [url] asking for [size]: its `size` replaced, or added when it has none. */
internal fun withCoverSize(url: String, size: Int): String =
	if (SIZE_PARAM.containsMatchIn(url)) SIZE_PARAM.replace(url) { "${it.groupValues[1]}size=$size" }
	else "$url&size=$size"

private fun decodePercent(value: String): String {
	if ('%' !in value && '+' !in value) return value
	val out = ByteArray(value.length * 4)
	var n = 0
	var i = 0
	while (i < value.length) {
		val c = value[i]
		val hex = if (c == '%' && i + 2 < value.length) value.substring(i + 1, i + 3).toIntOrNull(16) else null
		when {
			hex != null -> { out[n++] = hex.toByte(); i += 3 }
			c == '+' -> { out[n++] = ' '.code.toByte(); i++ }
			else -> { c.toString().encodeToByteArray().forEach { out[n++] = it }; i++ }
		}
	}
	return out.copyOf(n).decodeToString()
}

/**
 * What a cover request should read: [sizedKey]'s file when it is on disk, else the smallest larger
 * bucket's ([largerKeys], in ascending order), else the bare id's ([baseKey]: the full-quality
 * file a download or an older build left, which B-038's offline fallback reads), else a fetch at
 * the bucket under [sizedKey].
 */
internal fun pickCoverKey(
	sizedKey: String,
	largerKeys: List<String>,
	baseKey: String,
	onDisk: (String) -> Boolean
): String = (listOf(sizedKey) + largerKeys + baseKey).firstOrNull(onDisk) ?: sizedKey

/**
 * Row 9: a Subsonic cover is asked for at the bucket its target needs, not always at the user's
 * cover-art quality (4096 px by default), and kept under `id#size=N`. Only `getCoverArt` URLs
 * are touched: lb-bot's and the Cover Art Archive's pictures (`RemoteCoverArt`, `LbArtImage`)
 * and anything asked for at `Size.ORIGINAL` go through as they were. The palette fetch (§6
 * rule 2) is its own Ktor path and never comes here.
 */
class CoverSizeInterceptor(private val diskCache: () -> DiskCache?) : Interceptor {
	override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
		val request = chain.request
		val url = request.data as? String ?: return chain.proceed()
		val width = (chain.size.width as? Dimension.Pixels)?.px ?: return chain.proceed()
		val (id, cap) = parseCoverArtUrl(url) ?: return chain.proceed()
		val bucket = coverSizeBucket(width, cap) ?: return chain.proceed()

		val baseKey = request.diskCacheKey ?: id
		val larger = COVER_SIZE_BUCKETS.filter { it > bucket && (cap == null || it < cap) }
		val cache = diskCache()
		val key = withContext(Dispatchers.IO) {
			pickCoverKey(sizedCoverKey(id, bucket), larger.map { sizedCoverKey(id, it) }, baseKey) { key ->
				cache?.openSnapshot(key)?.use { true } == true
			}
		}
		val data = when (key) {
			baseKey -> url
			else -> withCoverSize(url, key.substringAfterLast("#size=").toIntOrNull() ?: bucket)
		}
		val builder = request.newBuilder().data(data).diskCacheKey(key)
		if (request.memoryCacheKey != null) builder.memoryCacheKey(key)
		return chain.withRequest(builder.build()).proceed()
	}
}
