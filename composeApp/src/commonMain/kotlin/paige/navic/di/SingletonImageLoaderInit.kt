package paige.navic.di

import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import coil3.serviceLoaderEnabled
import okio.FileSystem
import coil3.PlatformContext as CoilPlatformContext

/** ONE disk cache for both loaders; `lazy` (synchronized), as two loaders can ask for it at once. */
private val sharedDiskCache: DiskCache by lazy {
	DiskCache.Builder()
		.directory(FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "image_cache")
		.maxSizeBytes(2L shl 30)
		.build()
}

private fun getDiskCache(): DiskCache = sharedDiskCache

/** The static loader's memory cache: the washes' 256 px decodes only, so a small share. */
internal const val STATIC_MEMORY_CACHE_PERCENT = 0.05

fun initializeSingletonImageLoader(context: CoilPlatformContext): ImageLoader {
	// Built here, not left to Coil, so the interceptor can read it without suspending (row 9).
	val memoryCache = MemoryCache.Builder().maxSizePercent(context).build()
	return ImageLoader.Builder(context)
		.components {
			add(KtorNetworkFetcherFactory())
			// Row 9: Subsonic covers at the bucket their target needs (B-038's bare id kept).
			add(CoverSizeInterceptor({ getDiskCache() }, { memoryCache }))
		}
		.memoryCache { memoryCache }
		.diskCache { getDiskCache() }
		.crossfade(true)
		.build()
}

private var sharedStaticImageLoader: ImageLoader? = null

/**
 * image loader which doesn't animate GIFs
 *
 * only used in `BlendBackground.kt` right now. ONE for the process (adapted from upstream
 * `6947d2f9`): it used to be built per `BlendBackground`, so each of the three washes on screen
 * had its own memory cache and decoded the same cover again. Its memory cache is its own and
 * capped at [STATIC_MEMORY_CACHE_PERCENT]: a second default one (a quarter of the app's memory)
 * beside the main loader's was room for far more washes than are ever on screen.
 */
fun getStaticImageLoader(context: CoilPlatformContext): ImageLoader {
	return sharedStaticImageLoader ?: run {
		val memoryCache = MemoryCache.Builder().maxSizePercent(context, STATIC_MEMORY_CACHE_PERCENT).build()
		ImageLoader.Builder(context)
			.serviceLoaderEnabled(false)
			.components {
				add(KtorNetworkFetcherFactory())
				// Row 9: Subsonic covers at the bucket their target needs (B-038's bare id kept).
				add(CoverSizeInterceptor({ getDiskCache() }, { memoryCache }))
			}
			.memoryCache { memoryCache }
			.diskCache { getDiskCache() }
			.crossfade(true)
			.build()
	}.also { sharedStaticImageLoader = it }
}
