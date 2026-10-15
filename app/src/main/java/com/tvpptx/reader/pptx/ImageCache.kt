package com.tvpptx.reader.pptx

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache

/**
 * Decodes embedded pictures at roughly the size they are drawn (the projector has little RAM) and
 * keeps recent ones, since logos and backgrounds repeat on every slide.
 */
class ImageCache(private val pkg: PptxPackage) {
    private val cache = object : LruCache<String, Bitmap>(budget()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = HashSet<String>()
    private val sizes = HashMap<String, IntArray>()

    fun get(path: String, wantW: Int, wantH: Int): Bitmap? {
        if (path in failed) return null
        var bytes: ByteArray? = null
        val dims = sizes[path] ?: run {
            bytes = pkg.bytes(path)
            val b = bytes ?: return fail(path)
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(b, 0, b.size, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return fail(path) // EMF/WMF/TIFF: not decodable here
            intArrayOf(o.outWidth, o.outHeight).also { sizes[path] = it }
        }
        var sample = 1
        val tw = wantW.coerceAtLeast(1)
        val th = wantH.coerceAtLeast(1)
        while (dims[0] / (sample * 2) >= tw && dims[1] / (sample * 2) >= th) sample *= 2
        while (dims[0] / sample > 4096 || dims[1] / sample > 4096) sample *= 2
        val key = "$path@$sample"
        cache.get(key)?.let { return it }
        // A sharper copy that is already decoded is just as good.
        var s = sample / 2
        while (s >= 1) {
            cache.get("$path@$s")?.let { return it }
            s /= 2
        }
        val b = bytes ?: pkg.bytes(path) ?: return fail(path)
        val bmp = try {
            BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: OutOfMemoryError) {
            cache.evictAll()
            null
        } ?: return null
        cache.put(key, bmp)
        return bmp
    }

    private fun fail(path: String): Bitmap? {
        failed.add(path)
        return null
    }

    /** Pixel size of the stored image, once it has been looked at. */
    fun naturalSize(path: String): IntArray? = sizes[path]

    fun trim() = cache.evictAll()

    private fun budget(): Int = (Runtime.getRuntime().maxMemory() / 6).coerceAtMost(48L shl 20).toInt()
}
