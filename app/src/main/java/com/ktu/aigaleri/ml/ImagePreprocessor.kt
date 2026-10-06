package com.ktu.aigaleri.ml

import kotlin.math.ceil

/** Çözülmüş görüntü: satır sıralı ARGB_8888 (alfa yok sayılır). Bitmap burada tutulmaz; piksel dizisi kısa ömürlüdür. */
class PixelImage(val pixels: IntArray, val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "boyut > 0 olmalı" }
        require(pixels.size.toLong() == width.toLong() * height) { "piksel sayısı width*height olmalı" }
    }
}

/** Tek bir fotoğrafın çözülememesi/bozuk olması (fotoğrafa özgü; indeksleyici atlar). Mesaj yol/içerik içermez. */
class ImageDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * OpenAI CLIP görüntü önişlemesi (saf Kotlin, JVM'de test edilebilir; model-research.md): en kısa kenar 224'e
 * BICUBIC yeniden boyutlandırma, merkez kırpma 224x224, `x/255` sonra mean/std normalizasyonu, CHW float.
 *
 * Yeniden boyutlandırma Pillow `Image.resize(BICUBIC)` ile (HF `CLIPImageProcessor` bunu kullanır) aynı
 * algoritmadır: ayrık (önce yatay, sonra dikey) evrişim, küçültmede destek penceresi ölçekle genişler
 * (antialias), Pillow'un 8 bit sabit noktalı (22 bit) katsayıları ve ara sonucun uint8'e yuvarlanması.
 * Python/Pillow referansıyla karşılaştırma testi `ImagePreprocessorTest`'te.
 * Çıktı boyutu HF gibi: kısa kenar 224, uzun kenar `int(224 * uzun / kısa)` (kesme), kırpma `(fark) / 2` (aşağı).
 *
 * Bellek: girdi (örneklenmiş, kısa kenar ~448-900 piksel) dışında geçici olarak yatay geçiş çıktısı
 * (`W' x H x 3` bayt) ve 224x224x3 float (~588 KB) ayrılır.
 */
object ImagePreprocessor {
    const val SIZE = 224
    val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
    val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

    /** Çıktı tensörü uzunluğu: 3 * 224 * 224. */
    const val TENSOR_SIZE = 3 * SIZE * SIZE

    /** Aşırı en-boy oranında (ör. 1x10000) ara görüntünün bellek patlamasını önler; aşan fotoğraf atlanır. */
    const val MAX_RESIZED_SIDE = SIZE * 50

    private const val PRECISION_BITS = 32 - 8 - 2
    private const val BICUBIC_A = -0.5
    private const val BICUBIC_SUPPORT = 2.0

    /** Girdiyi modelin `pixel_values` tensörüne çevirir (CHW, `[3][224][224]`). */
    fun toModelInput(image: PixelImage): FloatArray {
        val rgb = resizeAndCrop(image)
        return normalize(rgb)
    }

    /**
     * Resize + merkez kırpma sonrası, satır sıralı RGB (piksel başına 3 bayt, 224x224x3) uint8 görüntü.
     */
    fun resizeAndCrop(image: PixelImage): ByteArray {
        val w = image.width
        val h = image.height
        // HF get_resize_output_image_size: kısa kenar 224, uzun kenar int(224 * uzun / kısa).
        val rw: Int
        val rh: Int
        if (w <= h) {
            rw = SIZE
            rh = (SIZE.toLong() * h / w).toInt()
        } else {
            rh = SIZE
            rw = (SIZE.toLong() * w / h).toInt()
        }
        if (maxOf(rw, rh) > MAX_RESIZED_SIDE) throw ImageDecodeException("en-boy oranı desteklenmiyor")
        val resized = resize(image, rw, rh)
        val left = (rw - SIZE) / 2
        val top = (rh - SIZE) / 2
        val out = ByteArray(SIZE * SIZE * 3)
        for (y in 0 until SIZE) {
            System.arraycopy(resized, ((top + y) * rw + left) * 3, out, y * SIZE * 3, SIZE * 3)
        }
        return out
    }

    /** uint8 RGB (HWC) -> `(x/255 - mean) / std`, CHW float. */
    fun normalize(rgb: ByteArray): FloatArray {
        require(rgb.size == SIZE * SIZE * 3) { "girdi 224x224x3 bayt olmalı" }
        val out = FloatArray(TENSOR_SIZE)
        val plane = SIZE * SIZE
        for (i in 0 until plane) {
            for (c in 0 until 3) {
                val v = (rgb[i * 3 + c].toInt() and 0xFF) / 255f
                out[c * plane + i] = (v - MEAN[c]) / STD[c]
            }
        }
        return out
    }

    /**
     * EXIF yönünü (1..8; geçersiz değer 1 sayılır) piksellere uygular. 1 ise aynı nesne döner (kopya yok);
     * diğerlerinde yeni dizi ayrılır (5-8'de genişlik/yükseklik yer değiştirir).
     */
    fun applyExifOrientation(image: PixelImage, orientation: Int): PixelImage {
        if (orientation !in 2..8) return image
        val w = image.width
        val h = image.height
        val swap = orientation >= 5
        val nw = if (swap) h else w
        val nh = if (swap) w else h
        val src = image.pixels
        val dst = IntArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                // (nx, ny): kaynak (x, y) pikselinin hedefteki konumu.
                val nx: Int
                val ny: Int
                when (orientation) {
                    2 -> { nx = w - 1 - x; ny = y }                 // yatay ayna
                    3 -> { nx = w - 1 - x; ny = h - 1 - y }         // 180
                    4 -> { nx = x; ny = h - 1 - y }                 // dikey ayna
                    5 -> { nx = y; ny = x }                         // transpoze
                    6 -> { nx = h - 1 - y; ny = x }                 // 90 saat yönü
                    7 -> { nx = h - 1 - y; ny = w - 1 - x }         // transverse
                    else -> { nx = y; ny = w - 1 - x }              // 8: 270 saat yönü
                }
                dst[ny * nw + nx] = src[y * w + x]
            }
        }
        return PixelImage(dst, nw, nh)
    }

    /**
     * Çözücü örnekleme katsayısı (2'nin kuvveti): kısa kenar [minShortSide]'ın altına düşmeyen en büyük
     * katsayı. Örneklenmiş kısa kenar >= 2*224 kalır, böylece son küçültme antialiaslı yapılır ve
     * örnekleme kalitesi önişlemeyi bozmaz. Bellek için üst sınır [maxSample].
     */
    fun sampleSize(width: Int, height: Int, minShortSide: Int = 2 * SIZE, maxSample: Int = 64): Int {
        require(width > 0 && height > 0 && minShortSide > 0 && maxSample >= 1) { "geçersiz boyut" }
        val shortSide = minOf(width, height)
        var sample = 1
        while (sample * 2 <= maxSample && shortSide / (sample * 2) >= minShortSide) sample *= 2
        return sample
    }

    // ---- Pillow uyumlu BICUBIC yeniden boyutlandırma ----

    /** [image]'i (outW x outH) boyutuna getirir; çıktı satır sıralı RGB uint8. */
    private fun resize(image: PixelImage, outW: Int, outH: Int): ByteArray {
        val inW = image.width
        val inH = image.height
        // Pillow: yalnızca boyutu değişen eksen işlenir; önce yatay, sonra dikey.
        var cur = rgbBytes(image.pixels)
        var curW = inW
        var curH = inH
        if (outW != inW) {
            cur = resampleHorizontal(cur, curW, curH, outW)
            curW = outW
        }
        if (outH != inH) {
            cur = resampleVertical(cur, curW, curH, outH)
            curH = outH
        }
        return cur
    }

    private fun rgbBytes(pixels: IntArray): ByteArray {
        val out = ByteArray(pixels.size * 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            out[i * 3] = (p shr 16).toByte()
            out[i * 3 + 1] = (p shr 8).toByte()
            out[i * 3 + 2] = p.toByte()
        }
        return out
    }

    private class Coeffs(val bounds: IntArray, val kernel: IntArray, val ksize: Int)

    private fun bicubic(xIn: Double): Double {
        val x = if (xIn < 0.0) -xIn else xIn
        return when {
            x < 1.0 -> ((BICUBIC_A + 2.0) * x - (BICUBIC_A + 3.0)) * x * x + 1.0
            x < 2.0 -> (((x - 5.0) * x + 8.0) * x - 4.0) * BICUBIC_A
            else -> 0.0
        }
    }

    /** Pillow `precompute_coeffs` + `normalize_coeffs_8bpc`. bounds: (xmin, xcount) çiftleri. */
    private fun precomputeCoeffs(inSize: Int, outSize: Int): Coeffs {
        val scale = inSize.toDouble() / outSize
        val filterScale = if (scale < 1.0) 1.0 else scale
        val support = BICUBIC_SUPPORT * filterScale
        val ksize = ceil(support).toInt() * 2 + 1
        val bounds = IntArray(outSize * 2)
        val kernel = IntArray(outSize * ksize)
        val kk = DoubleArray(ksize)
        val ss = 1.0 / filterScale
        for (xx in 0 until outSize) {
            val center = (xx + 0.5) * scale
            var xmin = (center - support + 0.5).toInt()
            if (xmin < 0) xmin = 0
            var xmax = (center + support + 0.5).toInt()
            if (xmax > inSize) xmax = inSize
            xmax -= xmin
            var ww = 0.0
            for (x in 0 until xmax) {
                val w = bicubic((x + xmin - center + 0.5) * ss)
                kk[x] = w
                ww += w
            }
            for (x in 0 until xmax) {
                if (ww != 0.0) kk[x] = kk[x] / ww
                val v = kk[x] * (1 shl PRECISION_BITS)
                kernel[xx * ksize + x] = if (v < 0) (-0.5 + v).toInt() else (0.5 + v).toInt()
            }
            bounds[xx * 2] = xmin
            bounds[xx * 2 + 1] = xmax
        }
        return Coeffs(bounds, kernel, ksize)
    }

    private fun clip8(v: Int): Byte {
        val s = v shr PRECISION_BITS
        return (if (s < 0) 0 else if (s > 255) 255 else s).toByte()
    }

    private fun resampleHorizontal(src: ByteArray, inW: Int, h: Int, outW: Int): ByteArray {
        val co = precomputeCoeffs(inW, outW)
        val out = ByteArray(outW * h * 3)
        val init = 1 shl (PRECISION_BITS - 1)
        for (y in 0 until h) {
            val rowIn = y * inW * 3
            val rowOut = y * outW * 3
            for (xx in 0 until outW) {
                val xmin = co.bounds[xx * 2]
                val xcount = co.bounds[xx * 2 + 1]
                var s0 = init
                var s1 = init
                var s2 = init
                for (x in 0 until xcount) {
                    val k = co.kernel[xx * co.ksize + x]
                    val idx = rowIn + (xmin + x) * 3
                    s0 += (src[idx].toInt() and 0xFF) * k
                    s1 += (src[idx + 1].toInt() and 0xFF) * k
                    s2 += (src[idx + 2].toInt() and 0xFF) * k
                }
                val o = rowOut + xx * 3
                out[o] = clip8(s0)
                out[o + 1] = clip8(s1)
                out[o + 2] = clip8(s2)
            }
        }
        return out
    }

    private fun resampleVertical(src: ByteArray, w: Int, inH: Int, outH: Int): ByteArray {
        val co = precomputeCoeffs(inH, outH)
        val out = ByteArray(w * outH * 3)
        val init = 1 shl (PRECISION_BITS - 1)
        val stride = w * 3
        for (yy in 0 until outH) {
            val ymin = co.bounds[yy * 2]
            val ycount = co.bounds[yy * 2 + 1]
            for (i in 0 until stride) {
                var s = init
                for (y in 0 until ycount) {
                    s += (src[(ymin + y) * stride + i].toInt() and 0xFF) * co.kernel[yy * co.ksize + y]
                }
                out[yy * stride + i] = clip8(s)
            }
        }
        return out
    }
}
