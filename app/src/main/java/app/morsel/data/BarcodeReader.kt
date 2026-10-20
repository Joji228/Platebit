package app.morsel.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Finds a product barcode (EAN-13, EAN-8, UPC-A, UPC-E) in a photo, on the phone, with ZXing.
 * Tries the photo as taken and turned a quarter, so a barcode photographed sideways still reads.
 */
object BarcodeReader {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E),
        DecodeHintType.TRY_HARDER to true,
    )

    suspend fun read(photo: File): String? = withContext(Dispatchers.Default) {
        val bitmap = load(photo) ?: return@withContext null
        try {
            decode(bitmap) ?: rotated(bitmap)?.let { turned -> decode(turned).also { turned.recycle() } }
        } finally {
            bitmap.recycle()
        }
    }

    private fun decode(bitmap: Bitmap): String? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val image = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))
        return try {
            MultiFormatReader().decode(image, hints).text?.takeIf { it.all(Char::isDigit) }
        } catch (_: NotFoundException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    /** Scaled down to about 1600 px on the long side: plenty for a barcode, quick to scan. */
    private fun load(photo: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photo.path, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= 1600) sample *= 2
        return BitmapFactory.decodeFile(photo.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun rotated(bitmap: Bitmap): Bitmap? =
        runCatching { Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(90f) }, true) }.getOrNull()
}
