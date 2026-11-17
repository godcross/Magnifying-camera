package com.godcross.magnifier

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Scores how sharp a JPEG is using the variance of the Laplacian over the centre of the frame.
 * Higher = more fine detail = better focus and less motion blur.
 */
object Sharpness {

    fun score(jpeg: ByteArray): Double {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return 0.0

        // Work on roughly 1600 px images: detailed enough to see focus, fast enough to run per shot.
        var sample = 1
        while (longest / (sample * 2) >= 1600) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return 0.0
        try {
            return laplacianVariance(bmp)
        } finally {
            bmp.recycle()
        }
    }

    private fun laplacianVariance(bmp: Bitmap): Double {
        // Centre 50% of the frame, where the subject almost always is.
        val w = bmp.width / 2
        val h = bmp.height / 2
        if (w < 3 || h < 3) return 0.0
        val x0 = bmp.width / 4
        val y0 = bmp.height / 4
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, x0, y0, w, h)

        val gray = FloatArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val lap = (gray[i - 1] + gray[i + 1] + gray[i - w] + gray[i + w] - 4f * gray[i]).toDouble()
                sum += lap
                sumSq += lap * lap
                n++
            }
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return sumSq / n - mean * mean
    }
}
