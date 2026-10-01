package com.tunisianprayertimes.tv.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import java.io.File
import java.io.FileOutputStream

/**
 * Images from a USB key, kept at the screen's size: a 48-megapixel phone photo of 12 MB becomes a
 * 1920-pixel one of a few hundred KB, so twenty of them do not fill a small box and the wall does not
 * decode a whole photo at every change of background. An image already that small is kept byte for byte.
 */
object ScreenImage {

    /** The longest side kept: a Full HD screen's width. */
    const val MAX_SIDE = 1920

    private const val QUALITY = 85

    /**
     * Stores [source] as [target], scaled down to [MAX_SIDE] in its own format (its name still says what
     * it holds) and turned upright as the camera recorded it. False when Android cannot decode it (a
     * broken file behind a correct header): the caller drops [target]. Throws when [source] cannot be
     * read: a key pulled out is a failed copy, not a broken image.
     */
    fun store(source: File, target: File): Boolean {
        // Read whole first: the decoder takes a read error for a broken file, and says nothing.
        LocalMediaManager.copySynced(source, target)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(target.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= MAX_SIDE) return true
        // Decoded at the power-of-two scale just above the screen's size (little memory), then scaled to it.
        var sample = 1
        while (longest / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeFile(target.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        val scale = MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        // The copy loses the photo's orientation tag: the pixels are turned instead.
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postConcat(orientation(target))
        }
        val shown = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        try {
            // Over the whole copy, now in memory.
            FileOutputStream(target).use { out ->
                check(shown.compress(format(source), QUALITY, out)) { "cannot encode ${source.name}" }
                out.fd.sync()
            }
        } finally {
            if (shown !== decoded) shown.recycle()
            decoded.recycle()
        }
        return true
    }

    private fun format(source: File): Bitmap.CompressFormat = when (source.extension.lowercase()) {
        "png" -> Bitmap.CompressFormat.PNG
        "webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        else -> Bitmap.CompressFormat.JPEG
    }

    /** The turn a camera recorded in the photo's EXIF tag (a phone held upright), as a matrix. */
    private fun orientation(photo: File): Matrix = Matrix().apply {
        val tag = runCatching {
            ExifInterface(photo.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        when (tag) {
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                postRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                postRotate(270f)
                postScale(-1f, 1f)
            }
        }
    }
}
