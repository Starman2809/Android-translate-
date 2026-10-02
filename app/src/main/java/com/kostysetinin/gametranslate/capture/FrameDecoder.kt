package com.kostysetinin.gametranslate.capture

import android.graphics.Bitmap
import android.media.Image
import com.kostysetinin.gametranslate.prefs.CaptureRegion

data class DecodedFrame(
    val bitmap: Bitmap,
    val cropLeft: Float,
    val cropTop: Float,
    val scale: Float,
)

object FrameDecoder {
    private const val MAX_SIDE = 1600

    fun decode(image: Image, region: CaptureRegion): DecodedFrame {
        val full = imageToBitmap(image)
        val cropped = crop(full, region)
        if (cropped !== full) full.recycle()
        val longest = maxOf(cropped.width, cropped.height).toFloat().coerceAtLeast(1f)
        val scale = minOf(1f, MAX_SIDE / longest)
        val scaled = if (scale < 0.999f) {
            val width = (cropped.width * scale).toInt().coerceAtLeast(1)
            val height = (cropped.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(cropped, width, height, true)
        } else {
            cropped
        }
        if (scaled !== cropped) cropped.recycle()
        val cropLeft = region.left.coerceIn(0f, 1f) * image.width
        val cropTop = region.top.coerceIn(0f, 1f) * image.height
        return DecodedFrame(
            bitmap = scaled,
            cropLeft = cropLeft,
            cropTop = cropTop,
            scale = if (scale <= 0f) 1f else scale,
        )
    }

    fun looksBlank(bitmap: Bitmap): Boolean {
        if (bitmap.width < 2 || bitmap.height < 2) return true
        var sum = 0L
        var count = 0
        val stepX = (bitmap.width / 8).coerceAtLeast(1)
        val stepY = (bitmap.height / 8).coerceAtLeast(1)
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val color = bitmap.getPixel(x, y)
                val red = (color shr 16) and 0xFF
                val green = (color shr 8) and 0xFF
                val blue = color and 0xFF
                sum += (red + green + blue) / 3
                count++
                x += stepX
            }
            y += stepY
        }
        return count > 0 && sum / count < 4
    }

    private fun crop(bitmap: Bitmap, region: CaptureRegion): Bitmap {
        if (region.isFullScreen()) return bitmap
        val left = (region.left.coerceIn(0f, 1f) * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val top = (region.top.coerceIn(0f, 1f) * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val right = (region.right.coerceIn(0f, 1f) * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (region.bottom.coerceIn(0f, 1f) * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        buffer.rewind()
        val pixelStride = plane.pixelStride.coerceAtLeast(1)
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + (rowPadding / pixelStride).coerceAtLeast(0)
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        if (paddedWidth == image.width) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }
}
