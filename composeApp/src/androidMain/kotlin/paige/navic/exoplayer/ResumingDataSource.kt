package paige.navic.exoplayer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import paige.navic.util.Logger
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Wraps the player's HTTP source so that a stream which breaks mid-transfer is reopened at the
 * exact byte it stopped at, instead of ending the song.
 *
 * A song's connection does break mid-transfer, routinely. ExoPlayer pauses a load, connection
 * held open, whenever the buffer ahead is full, and phones, routers and the Cloudflare tunnel all
 * drop a connection left idle long enough (measured: a 57–89 MB FLAC idled 400 s through the
 * tunnel was reset at 16 MiB). Two things made that silent rather than a hiccup:
 *
 * - media3's `KtorDataSource` returns end-of-input whenever the body channel closes, without
 *   checking the bytes it got against the declared `Content-Length`. A connection that ENDS early
 *   is therefore read as the end of the file, and ExoPlayer finishes the song on its standalone
 *   clock in silence. This wrapper counts, and treats a short end as a break.
 * - An error, where one does surface, costs ExoPlayer a retry. For a stream of unknown length (an
 *   uncached transcode) ExoPlayer treats it as live and restarts from the beginning. Reopening here
 *   with a Range request hides the break from ExoPlayer entirely: Navidrome answers a range on a
 *   file or a cached transcode with a 206, and on a transcode still in progress with a 200 from
 *   byte 0, which `KtorDataSource` skips forward through.
 *
 * Not recoverable here: a stream of unknown length that ends cleanly but early. Nothing
 * distinguishes it from the real end. Streaming over HTTP/1.1 (see PlaybackService's
 * streamingClient) is what makes that case surface as an error: a chunked body cut off before its
 * last chunk throws.
 *
 * A resume that makes no progress counts against [MAX_RESUMES_WITHOUT_PROGRESS]; past that the
 * error goes to ExoPlayer as it would have without this wrapper.
 */
@UnstableApi
class ResumingDataSource private constructor(
	private val upstreamFactory: DataSource.Factory
) : DataSource {

	class Factory(private val upstreamFactory: DataSource.Factory) : DataSource.Factory {
		override fun createDataSource(): DataSource = ResumingDataSource(upstreamFactory)
	}

	private val transferListeners = mutableListOf<TransferListener>()
	private var upstream: DataSource? = null
	private var dataSpec: DataSpec? = null

	/** Bytes delivered since [open], i.e. the offset into [dataSpec] a resume starts from. */
	private var delivered = 0L

	/** Bytes still owed for the whole opened range, or [C.LENGTH_UNSET] when unknown. */
	private var bytesRemaining = C.LENGTH_UNSET.toLong()
	private var resumesWithoutProgress = 0

	override fun addTransferListener(transferListener: TransferListener) {
		transferListeners += transferListener
		upstream?.addTransferListener(transferListener)
	}

	override fun open(dataSpec: DataSpec): Long {
		this.dataSpec = dataSpec
		delivered = 0
		resumesWithoutProgress = 0
		val length = openUpstream(dataSpec)
		bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else length
		return length
	}

	override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
		if (length == 0) return 0
		while (true) {
			val read = try {
				checkNotNull(upstream).read(buffer, offset, length)
			} catch (e: IOException) {
				if (!isBreak(e)) throw e
				resume(e.toString(), e)
				continue
			}
			if (read == C.RESULT_END_OF_INPUT) {
				if (bytesRemaining == C.LENGTH_UNSET.toLong() || bytesRemaining <= 0) return read
				resume("ended $bytesRemaining bytes short of the declared length", null)
				continue
			}
			delivered += read
			if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
			resumesWithoutProgress = 0
			return read
		}
	}

	/**
	 * A dropped or reset connection. Not one: an HTTP error status, a bad response, or an
	 * interruption — ExoPlayer cancels a load by interrupting its thread, which KtorDataSource
	 * reports as a read error caused by [InterruptedIOException], and resuming that would keep
	 * reopening a load the player is trying to stop.
	 */
	private fun isBreak(e: IOException): Boolean =
		e is HttpDataSource.HttpDataSourceException &&
			e !is HttpDataSource.InvalidResponseCodeException &&
			e !is HttpDataSource.InvalidContentTypeException &&
			e.type == HttpDataSource.HttpDataSourceException.TYPE_READ &&
			e.cause !is InterruptedIOException &&
			!Thread.currentThread().isInterrupted

	private fun resume(why: String, cause: IOException?) {
		val spec = checkNotNull(dataSpec)
		if (++resumesWithoutProgress > MAX_RESUMES_WITHOUT_PROGRESS) {
			throw cause ?: HttpDataSource.HttpDataSourceException(
				"Stream ended $bytesRemaining bytes early, and resuming it made no progress",
				spec,
				PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
				HttpDataSource.HttpDataSourceException.TYPE_READ
			)
		}
		Logger.w(TAG, "resuming ${spec.uri.lastPathSegment} at byte ${spec.position + delivered}: $why")
		closeUpstreamQuietly()
		openUpstream(spec.subrange(delivered))
	}

	private fun openUpstream(spec: DataSpec): Long {
		val source = upstreamFactory.createDataSource()
		transferListeners.forEach(source::addTransferListener)
		upstream = source
		return source.open(spec)
	}

	private fun closeUpstreamQuietly() {
		try {
			upstream?.close()
		} catch (_: IOException) {
		}
		upstream = null
	}

	override fun getUri(): Uri? = upstream?.uri ?: dataSpec?.uri

	override fun getResponseHeaders(): Map<String, List<String>> =
		upstream?.responseHeaders ?: emptyMap()

	override fun close() {
		try {
			upstream?.close()
		} finally {
			upstream = null
			dataSpec = null
		}
	}

	private companion object {
		const val TAG = "ResumingDataSource"
		const val MAX_RESUMES_WITHOUT_PROGRESS = 3
	}
}
