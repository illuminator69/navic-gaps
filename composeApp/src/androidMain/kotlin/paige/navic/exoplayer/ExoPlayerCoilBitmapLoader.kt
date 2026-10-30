package paige.navic.exoplayer

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import coil3.ImageLoader
import coil3.disk.DiskCache
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.guava.future
import paige.navic.di.parseCoverArtUrl
import paige.navic.di.sizedCoverKey
import paige.navic.di.withCoverSize
import paige.navic.util.Logger

/**
 * Small util for attempting to load cached artworks from Coil first,
 * before resorting to doing the normal behavior of ExoPlayer (which just fetches the provided image url)
 */
@UnstableApi
class ExoPlayerCoilBitmapLoader(
	context: Context,
	private val imageLoader: ImageLoader,
): BitmapLoader {
	// Row 9: the notification's artwork is decoded to at most this, whatever the file on disk
	// or the URL hold (it decoded the user's cover-art quality, 4096 px by default, uncapped).
	private val bitmapLoader = DataSourceBitmapLoader.Builder(context)
		.setMaximumOutputDimension(NOTIFICATION_ARTWORK_MAX_PX)
		.build()

	// media3 calls `loadBitmap` on the session's application thread: the disk read and the
	// decode it leads to are done here instead, and the call only hands back the future.
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	override fun supportsMimeType(mimeType: String): Boolean {
		return bitmapLoader.supportsMimeType(mimeType)
	}

	override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
		return bitmapLoader.decodeBitmap(data)
	}

	override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = scope.future {
		// this expects to be the coverArtId from Subsonic's API, since currently the cache is keyed against it
		val imageBytes = uri.getQueryParameter("id")?.let { readCachedArtwork(imageLoader.diskCache, it) }
		if (imageBytes != null) {
			bitmapLoader.decodeBitmap(imageBytes).await()
		} else {
			bitmapLoader.loadBitmap(notificationArtworkUrl(uri.toString()).toUri()).await()
		}
	}
}

/** The bytes of the first of [coverId]'s [notificationArtworkKeys] on disk, or null. Blocking. */
internal fun readCachedArtwork(diskCache: DiskCache?, coverId: String): ByteArray? = try {
	notificationArtworkKeys(coverId).firstNotNullOfOrNull { diskCache?.openSnapshot(it) }
		.use { it?.data?.toFile()?.readBytes() }
} catch (e: Exception) {
	Logger.w("BitmapLoader", "could not read artwork data", e)
	null
}

/** The notification's artwork is decoded to at most this many pixels on its longer side. */
internal const val NOTIFICATION_ARTWORK_MAX_PX = 512

/**
 * The disk keys a cover's artwork is looked for under, best first for a [NOTIFICATION_ARTWORK_MAX_PX]
 * decode: the 512 and 1024 entries the tiles keep since row 9, then the bare id (a download's, or
 * the cover-art quality's). Never a 200, 100 or 50 entry: the shade and Android Auto draw the
 * artwork far larger than that, and a stretched thumbnail is what they showed.
 */
internal fun notificationArtworkKeys(coverId: String): List<String> =
	listOf(sizedCoverKey(coverId, 512), sizedCoverKey(coverId, 1024), coverId)

/**
 * The URL the artwork is fetched from when no entry is on disk: a Subsonic cover asked for at
 * [NOTIFICATION_ARTWORK_MAX_PX] (the cover-art quality is 4096 by default, which is then decoded
 * down to 512 anyway), or at the URL's own size when that is smaller. Any other URL as it is.
 */
internal fun notificationArtworkUrl(url: String): String {
	val (_, size) = parseCoverArtUrl(url) ?: return url
	return if (size != null && size <= NOTIFICATION_ARTWORK_MAX_PX) url
	else withCoverSize(url, NOTIFICATION_ARTWORK_MAX_PX)
}
