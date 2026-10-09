package ph.edu.bsit.tcc.ojtdtr.proof

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File

internal object ProofImage {
    fun load(file: File): Bitmap? = readProofImage(file) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(it.path, bounds)
        // Camera JPEGs only. Bound dimensions before allocating; null means failed capture.
        if (bounds.outMimeType != "image/jpeg" || bounds.outWidth !in 1..16384 || bounds.outHeight !in 1..16384)
            return@readProofImage null
        val exif = ExifInterface(it.path)
        val raw = exif.getAttribute(ExifInterface.TAG_ORIENTATION)
        val orientation = if (raw == null) 1 else raw.toIntOrNull() ?: return@readProofImage null
        if (orientation !in 0..8) return@readProofImage null
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (bounds.outWidth / inSampleSize > 800 || bounds.outHeight / inSampleSize > 800) inSampleSize *= 2
        }
        val image = BitmapFactory.decodeFile(it.path, options) ?: return@readProofImage null
        var result: Bitmap? = null
        try {
            val matrix = Matrix()
            if (orientation in listOf(2, 4, 5, 7)) matrix.postScale(-1f, 1f)
            matrix.postRotate(when (orientation) { 3, 4 -> 180f; 5, 6 -> 90f; 7, 8 -> 270f; else -> 0f })
            result = Bitmap.createBitmap(image, 0, 0, image.width, image.height, matrix, true)
            result
        } finally { if (result !== image) image.recycle() }
    }
    fun valid(file: File): Boolean = load(file)?.let { it.recycle(); true } ?: false
}
