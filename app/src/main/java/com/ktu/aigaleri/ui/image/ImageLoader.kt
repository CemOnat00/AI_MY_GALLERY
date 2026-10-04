package com.ktu.aigaleri.ui.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Ağ içermeyen küçük görsel yükleyici (MediaStore URI -> Bitmap). Coil/Glide yerine bilerek kendi
 * küçük çözümümüz: ek bağımlılık ve ağ katmanı yok. Decode işi IO dispatcher'ında; sonuçlar bellek
 * içi LRU önbelleğinde tutulur (boyut: uygulama belleğinin 1/8'i).
 *
 * Hatada (silinmiş dosya, izin yok, bozuk görsel) null döner; URI/yol loglanmaz.
 */
class ImageLoader(private val resolver: ContentResolver) {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Izgara küçük resmi; en uzun kenar yaklaşık [sizePx]. */
    suspend fun loadThumbnail(uri: String, sizePx: Int): Bitmap? = load("t$sizePx:$uri") {
        val parsed = Uri.parse(uri)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.loadThumbnail(parsed, Size(sizePx, sizePx), null)
        } else {
            decodeSampled(parsed, sizePx)
        }
    }

    /** Büyük görünüm için; en uzun kenar en fazla [maxPx] (bellek için örneklenir). */
    suspend fun loadFull(uri: String, maxPx: Int = FULL_MAX_PX): Bitmap? = load("f$maxPx:$uri") {
        val parsed = Uri.parse(uri)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, parsed)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > maxPx) {
                    val scale = maxPx.toFloat() / longest
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1),
                    )
                }
            }
        } else {
            decodeSampled(parsed, maxPx)
        }
    }

    private suspend fun load(key: String, decode: () -> Bitmap?): Bitmap? {
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                decode()?.also { cache.put(key, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun decodeSampled(uri: Uri, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxPx)
        }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    companion object {
        const val FULL_MAX_PX = 2048
    }
}

/**
 * Örneklenmiş en uzun kenarı [maxPx]'i aşmayacak en küçük 2'nin kuvveti oranı (en az 1).
 */
fun calculateInSampleSize(width: Int, height: Int, maxPx: Int): Int {
    require(maxPx > 0) { "maxPx > 0 olmalı" }
    var sample = 1
    var longest = maxOf(width, height)
    while (longest > maxPx) {
        sample *= 2
        longest /= 2
    }
    return sample
}
