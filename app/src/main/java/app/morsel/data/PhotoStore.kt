package app.morsel.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Which files count as meal photos, so nothing else in the app's storage is ever deleted. */
object MealPhotos {
    /**
     * True only for a file directly inside [mealDir]. A path from an imported backup is just text;
     * one pointing anywhere else (for example the database) is never followed or deleted.
     */
    fun isMealPhoto(path: String?, mealDir: File): Boolean {
        if (path.isNullOrBlank()) return false
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
        val dir = runCatching { mealDir.canonicalFile }.getOrNull() ?: return false
        return file.parentFile == dir
    }
}

class PhotoStore(private val context: Context) {
    private val captureDir get() = File(context.cacheDir, "captures").apply { mkdirs() }
    private val mealDir get() = File(context.filesDir, "meals").apply { mkdirs() }

    fun newCaptureFile() = File(captureDir, "capture_${System.nanoTime()}.jpg")

    suspend fun importUri(uri: Uri): File = withContext(Dispatchers.IO) {
        val file = newCaptureFile()
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { input.copyTo(it) }
        } ?: error("Couldn't open that photo")
        file
    }

    /** Downscaled JPEG for the AI: faster upload, fewer tokens, same accuracy. */
    suspend fun prepareForAi(file: File): ByteArray = withContext(Dispatchers.Default) {
        decode(file, maxSide = 1280).toJpeg(85)
    }

    /** Copy kept with the meal: big enough to view full screen, ~60–90 KB. */
    suspend fun saveThumbnail(file: File): String = withContext(Dispatchers.IO) {
        val out = File(mealDir, "meal_${System.currentTimeMillis()}.jpg")
        out.writeBytes(decode(file, maxSide = 900).toJpeg(78))
        out.absolutePath
    }

    fun clearCaptures() {
        captureDir.listFiles()?.forEach { it.delete() }
    }

    fun mealPhotos(): List<File> = mealDir.listFiles()?.toList().orEmpty()

    fun deleteAll() {
        clearCaptures()
        mealPhotos().forEach { it.delete() }
    }

    private fun decode(file: File, maxSide: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val scale = min(1f, maxSide.toFloat() / max(width, height))
            decoder.setTargetSize(
                (width * scale).roundToInt().coerceAtLeast(1),
                (height * scale).roundToInt().coerceAtLeast(1),
            )
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    private fun Bitmap.toJpeg(quality: Int): ByteArray =
        ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
}
