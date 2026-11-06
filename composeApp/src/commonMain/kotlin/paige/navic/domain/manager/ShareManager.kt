package paige.navic.domain.manager

import androidx.compose.ui.graphics.ImageBitmap

expect class ShareManager {
	suspend fun shareImage(bitmap: ImageBitmap, fileName: String)
	suspend fun saveImage(bitmap: ImageBitmap, fileName: String)
	suspend fun shareString(string: String)

	/** Shares [text] as a `text/plain` file named [fileName] through the system's share sheet (Worbus P4 N3). */
	suspend fun shareTextFile(text: String, fileName: String)
}
