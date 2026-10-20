package app.morsel.ui.scan

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer

/**
 * Watches the viewfinder for a product barcode, like MyFitnessPal does. Kept light on purpose:
 * it looks at about three small frames a second (the rest are dropped at once), only at the middle of
 * the frame, and only the brightness plane the camera already provides, so no extra copies of
 * full images are made. Runs on its own background thread; [onCode] is called with the digits.
 */
class LiveBarcodeAnalyzer(
    /** False while watching is paused (a photo being taken, analysis running): frames are just dropped. */
    private val isActive: () -> Boolean = { true },
    private val onCode: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                // Light mode (no TRY_HARDER): a few rows per frame is plenty when the barcode fills the middle.
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E),
            ),
        )
    }
    private var lastFrameAt = 0L
    private var frame = 0
    private var buffer = ByteArray(0)

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.uptimeMillis()
            if (!isActive() || now - lastFrameAt < FRAME_GAP_MS) return
            lastFrameAt = now
            decode(image)?.let(onCode)
        } finally {
            image.close()
        }
    }

    private fun decode(image: ImageProxy): String? {
        val plane = image.planes[0]
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        val data = plane.buffer.let { buf ->
            if (buffer.size != buf.remaining()) buffer = ByteArray(buf.remaining())
            buf.rewind()
            buf.get(buffer)
            buffer
        }
        // The middle of what the user sees: most of its width, half its height. On a phone held
        // upright the sensor image is turned a quarter, so that box is tall in sensor terms.
        val upright = image.imageInfo.rotationDegrees % 180 != 0
        val cropW = if (upright) width / 2 else width * 4 / 5
        val cropH = if (upright) height * 4 / 5 else height / 2
        val left = (width - cropW) / 2
        val top = (height - cropH) / 2
        // Alternate frames: barcode across the screen (the usual way) and along it.
        frame++
        val turn = upright == (frame % 2 == 0)
        val source = if (turn) {
            // Transposed copy of the box, so bars across the screen become columns for the 1D reader.
            val out = ByteArray(cropW * cropH)
            for (y in 0 until cropH) {
                val row = (top + y) * rowStride + left
                for (x in 0 until cropW) out[x * cropH + y] = data[row + x]
            }
            PlanarYUVLuminanceSource(out, cropH, cropW, 0, 0, cropH, cropW, false)
        } else {
            PlanarYUVLuminanceSource(data, rowStride, height, left, top, cropW, cropH, false)
        }
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text?.takeIf { it.length >= 8 && it.all(Char::isDigit) }
        } catch (_: ReaderException) {
            null
        } catch (_: RuntimeException) {
            null
        } finally {
            reader.reset()
        }
    }

    private companion object {
        const val FRAME_GAP_MS = 330L
    }
}
