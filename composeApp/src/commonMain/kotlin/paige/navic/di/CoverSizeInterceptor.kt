package paige.navic.di

import coil3.disk.DiskCache
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.request.ErrorResult
import coil3.request.ImageRequest
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
 * The px a cover request is bucketed by: the larger of its width and height, whichever are known
 * (a tile drawn 150 wide and 300 tall needs the 512 file, not the 200). Null = neither is known
 * (`Size.ORIGINAL`, or undefined both ways): the request goes through as it is.
 */
internal fun coverTargetPx(width: Int?, height: Int?): Int? =
	listOfNotNull(width, height).maxOrNull()

/**
 * The sized keys under [bucket], largest first: what a failed fetch falls back to (B-038 offline),
 * a smaller file stretched being better than the error box.
 */
internal fun smallerCoverKeys(id: String, bucket: Int): List<String> =
	COVER_SIZE_BUCKETS.filter { it < bucket }.sortedDescending().map { sizedCoverKey(id, it) }

/**
 * Which key each (sized key, cap) was given, so a cover's disk entries are probed once per
 * process and every later request for it (a scroll back, a Back to the list) is answered without
 * leaving the caller's thread. Shared by both loaders, which share one disk cache. Bounded: the
 * eldest choice is dropped past [maxEntries], and a dropped one is only probed again.
 *
 * A remembered choice can go stale (the disk cache evicts its file): the request then fetches
 * under that key, as any miss does, and a failed fetch still falls back ([smallerCoverKeys]).
 */
internal class CoverKeyMemo(private val maxEntries: Int = 4096) {
	private val lock = Any()
	private val chosen = LinkedHashMap<String, String>()

	operator fun get(memoKey: String): String? = synchronized(lock) { chosen[memoKey] }

	operator fun set(memoKey: String, key: String) {
		synchronized(lock) {
			chosen.remove(memoKey)
			chosen[memoKey] = key
			while (chosen.size > maxEntries) chosen.remove(chosen.keys.first())
		}
	}
}

/**
 * The key a request reads without leaving its thread, or null when only the disk can tell:
 * a candidate already decoded in memory at least [targetPx] wide or tall ([inMemory]), in
 * [candidates]' order, else the key remembered for [memoKey]. Neither touches the disk.
 */
internal fun fastCoverKey(
	candidates: List<String>,
	memoKey: String,
	memo: CoverKeyMemo,
	inMemory: (String) -> Boolean
): String? = candidates.firstOrNull(inMemory) ?: memo[memoKey]

/** The process's one memo, for both loaders (they share [getDiskCache]'s files). */
internal val coverKeyMemo = CoverKeyMemo()

/**
 * Row 9: a Subsonic cover is asked for at the bucket its target needs, not always at the user's
 * cover-art quality (4096 px by default), and kept under `id#size=N`. Only `getCoverArt` URLs
 * are touched: lb-bot's and the Cover Art Archive's pictures (`RemoteCoverArt`, `LbArtImage`)
 * and anything asked for at `Size.ORIGINAL` go through as they were. The palette fetch (§6
 * rule 2) is its own Ktor path and never comes here.
 *
 * Coil runs this before its memory cache (`EngineInterceptor` is last), so a request must not
 * suspend here when its cover is already at hand: a cover in [memoryCache] under one of its keys,
 * or one whose key was chosen before ([coverKeyMemo]), goes straight on, on the caller's thread,
 * and a cover already seen draws in the frame it is composed in. Only a cover's first request in
 * the process probes the disk, on IO.
 */
class CoverSizeInterceptor internal constructor(
	private val diskCache: () -> DiskCache?,
	private val memoryCache: () -> MemoryCache? = { null },
	private val memo: CoverKeyMemo = coverKeyMemo
) : Interceptor {
	override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
		val request = chain.request
		val url = request.data as? String ?: return chain.proceed()
		val target = coverTargetPx(
			(chain.size.width as? Dimension.Pixels)?.px,
			(chain.size.height as? Dimension.Pixels)?.px
		) ?: return chain.proceed()
		val (id, cap) = parseCoverArtUrl(url) ?: return chain.proceed()
		val bucket = coverSizeBucket(target, cap) ?: return chain.proceed()

		val baseKey = request.diskCacheKey ?: id
		val sizedKey = sizedCoverKey(id, bucket)
		val largerKeys = COVER_SIZE_BUCKETS.filter { it > bucket && (cap == null || it < cap) }
			.map { sizedCoverKey(id, it) }
		val memoKey = "$sizedKey|$cap|$baseKey"
		val keepsMemoryKey = request.memoryCacheKey != null
		val memory = if (keepsMemoryKey) memoryCache() else null
		val extras = request.memoryCacheKeyExtras
		val fast = fastCoverKey(listOf(sizedKey) + largerKeys + baseKey, memoKey, memo) { key ->
			val image = memory?.get(MemoryCache.Key(key, extras))?.image
			image != null && (image.width >= target || image.height >= target)
		}
		val cache = diskCache()
		val key = fast ?: withContext(Dispatchers.IO) {
			pickCoverKey(sizedKey, largerKeys, baseKey) { cache.has(it) }
		}.also { memo[memoKey] = it }

		val result = chain.withRequest(request.sizedTo(url, key, baseKey, bucket, keepsMemoryKey)).proceed()
		if (result !is ErrorResult) return result
		// B-038 offline: the fetch failed; a smaller file of the same cover on disk beats the error
		// box (and the album fallback `CoverArt` tries after it).
		val smaller = withContext(Dispatchers.IO) {
			smallerCoverKeys(id, bucket).firstOrNull { it != key && cache.has(it) }
		} ?: return result
		return chain.withRequest(request.sizedTo(url, smaller, baseKey, bucket, keepsMemoryKey)).proceed()
	}

	private fun DiskCache?.has(key: String): Boolean = this?.openSnapshot(key)?.use { true } == true

	private fun ImageRequest.sizedTo(url: String, key: String, baseKey: String, bucket: Int, keepsMemoryKey: Boolean): ImageRequest {
		val data = when (key) {
			baseKey -> url
			else -> withCoverSize(url, key.substringAfterLast("#size=").toIntOrNull() ?: bucket)
		}
		val builder = newBuilder().data(data).diskCacheKey(key)
		if (keepsMemoryKey) builder.memoryCacheKey(key)
		return builder.build()
	}
}
