package paige.navic.exoplayer

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import coil3.ImageLoader
import com.google.common.util.concurrent.ListenableFuture
import paige.navic.di.sizedCoverKey
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

	override fun supportsMimeType(mimeType: String): Boolean {
		return bitmapLoader.supportsMimeType(mimeType)
	}

	override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
		return bitmapLoader.decodeBitmap(data)
	}

	override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
		// this expects to be the coverArtId from Subsonic's API, since currently the cache is keyed against it
		val coverId = uri.getQueryParameter("id")

		if (coverId != null) {
			try {
				val diskCache = imageLoader.diskCache
				// The bare id (a download's, or the cover-art quality's), else the sized entries
				// the tiles keep since row 9, the ones nearest the cap first.
				val snapshot = notificationArtworkKeys(coverId).firstNotNullOfOrNull { diskCache?.openSnapshot(it) }
				val imageBytes = snapshot.use {
					it?.data?.toFile()?.readBytes()
				}
				if (imageBytes != null) {
					return this.decodeBitmap(imageBytes)
				}
			} catch (e: Exception) {
				Logger.w("BitmapLoader", "could not read artwork data", e)
			}
		}

		return bitmapLoader.loadBitmap(uri)
	}
}

/** The notification's artwork is decoded to at most this many pixels on its longer side. */
internal const val NOTIFICATION_ARTWORK_MAX_PX = 512

/** The disk keys a cover's artwork is looked for under, best first for a [NOTIFICATION_ARTWORK_MAX_PX] decode. */
internal fun notificationArtworkKeys(coverId: String): List<String> =
	listOf(coverId) + listOf(512, 1024, 200, 100, 50).map { sizedCoverKey(coverId, it) }
