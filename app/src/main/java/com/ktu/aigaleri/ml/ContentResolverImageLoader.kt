package com.ktu.aigaleri.ml

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import java.io.IOException

/** Fotoğrafı piksel dizisine çözer (blokleyici; ana thread dışında çağrılmalı). Testte sahte verilir. */
fun interface PixelLoader {
    /** @throws ImageDecodeException fotoğraf çözülemiyorsa (fotoğrafa özgü). */
    fun load(uri: String): PixelImage
}

/**
 * [PixelLoader]'ın Android gerçeklemesi: `BitmapFactory` ile örnekleyerek (kısa kenar >= 448 kalacak en büyük
 * 2'nin kuvveti, bkz. [ImagePreprocessor.sampleSize]) küçük decode eder, EXIF yönünü piksellere uygular ve
 * Bitmap'i HEMEN `recycle()` eder; geriye yalnızca [PixelImage] kalır. Örn. 12 MP fotoğraf 1000x750 (~3 MB)
 * olarak çözülür. Tam çözünürlük hiçbir zaman belleğe alınmaz. Bellek yetmezse (OOM) fotoğraf atlanır.
 *
 * EXIF/başlık okuma hatası fotoğrafı düşürmez (yön 1 sayılır). URI, yol ve içerik log'a yazılmaz; istisna
 * mesajları genel metindir. Cihaz üstünde doğrulanmadı (cihaz yok); `ImageEncoderDeviceTest` yazıldı.
 */
class ContentResolverImageLoader(private val resolver: ContentResolver) : PixelLoader {
    override fun load(uri: String): PixelImage {
        val parsed = uri.toUri()
        val orientation = readOrientation(parsed)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(parsed).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ImageDecodeException("görüntü boyutu okunamadı")

        val options = BitmapFactory.Options().apply {
            inSampleSize = ImagePreprocessor.sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = try {
            open(parsed).use { BitmapFactory.decodeStream(it, null, options) }
        } catch (e: OutOfMemoryError) {
            throw ImageDecodeException("bellek yetersiz", e)
        } ?: throw ImageDecodeException("görüntü çözülemedi")

        val image = try {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            PixelImage(pixels, w, h)
        } catch (e: OutOfMemoryError) {
            throw ImageDecodeException("bellek yetersiz", e)
        } finally {
            bitmap.recycle()
        }
        return try {
            ImagePreprocessor.applyExifOrientation(image, orientation)
        } catch (e: OutOfMemoryError) {
            throw ImageDecodeException("bellek yetersiz", e)
        }
    }

    private fun open(uri: Uri) =
        try {
            resolver.openInputStream(uri) ?: throw ImageDecodeException("görüntü açılamadı")
        } catch (e: IOException) {
            throw ImageDecodeException("görüntü okunamadı", e)
        }

    private fun readOrientation(uri: Uri): Int =
        try {
            open(uri).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        } catch (e: ImageDecodeException) {
            throw e
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
}
