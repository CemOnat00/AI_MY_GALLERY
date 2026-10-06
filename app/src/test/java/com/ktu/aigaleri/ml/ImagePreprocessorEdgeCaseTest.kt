package com.ktu.aigaleri.ml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * T-006 (QA): ImagePreprocessor sınır durumları. Üretim koduna dokunmaz; yalnızca davranışı sabitler.
 * 1x1, tam 224x224, 50:1 en-boy sınırı, tek renk, saydam piksel, çok büyük boyut, kırpma yuvarlaması, EXIF kombinasyonları.
 */
class ImagePreprocessorEdgeCaseTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun solid(w: Int, h: Int, color: Int) = PixelImage(IntArray(w * h) { color }, w, h)

    private fun u8(b: Byte) = b.toInt() and 0xFF

    private fun rejected(w: Int, h: Int): Boolean =
        try {
            ImagePreprocessor.resizeAndCrop(PixelImage(IntArray(w * h), w, h))
            false
        } catch (_: ImageDecodeException) {
            true
        }

    // ---- boyut sınırları ----

    @Test
    fun oneByOne_upscalesToSolidTile_forAnyColor_andAlphaIsIgnored() {
        for (color in listOf(argb(255, 0, 0, 0), argb(255, 255, 255, 255), argb(255, 255, 0, 0), argb(255, 12, 200, 99))) {
            val rgb = ImagePreprocessor.resizeAndCrop(solid(1, 1, color))
            assertEquals(224 * 224 * 3, rgb.size)
            for (i in 0 until 224 * 224) {
                assertEquals(((color shr 16) and 0xFF), u8(rgb[i * 3]))
                assertEquals(((color shr 8) and 0xFF), u8(rgb[i * 3 + 1]))
                assertEquals((color and 0xFF), u8(rgb[i * 3 + 2]))
            }
        }
        val t = ImagePreprocessor.toModelInput(solid(1, 1, argb(255, 10, 20, 30)))
        assertEquals(ImagePreprocessor.TENSOR_SIZE, t.size)
        assertTrue(t.none { it.isNaN() || it.isInfinite() })
    }

    @Test
    fun exact224_isIdentity_and224x225_only_resizesOneAxis() {
        val w = 224
        val img = PixelImage(IntArray(w * w) { argb(255, it % 256, (it / 7) % 256, (it / 3) % 256) }, w, w)
        val rgb = ImagePreprocessor.resizeAndCrop(img)
        for (i in img.pixels.indices) {
            val p = img.pixels[i]
            assertEquals((p shr 16) and 0xFF, u8(rgb[i * 3]))
            assertEquals((p shr 8) and 0xFF, u8(rgb[i * 3 + 1]))
            assertEquals(p and 0xFF, u8(rgb[i * 3 + 2]))
        }
        // 224x225 (ve 225x224): kısa kenar zaten 224, uzun 225; yalnızca kırpma (sol/üst = 0), kayıpsız.
        val tall = PixelImage(IntArray(224 * 225) { argb(255, it % 251, 0, 0) }, 224, 225)
        val rgbTall = ImagePreprocessor.resizeAndCrop(tall)
        assertEquals(tall.pixels[0] shr 16 and 0xFF, u8(rgbTall[0]))
        assertEquals(tall.pixels[224 * 223 + 5] shr 16 and 0xFF, u8(rgbTall[(223 * 224 + 5) * 3]))
    }

    @Test
    fun centerCrop_offsetFloors_forOddDifference() {
        // 227x224: sol = (227-224)/2 = 1 (aşağı yuvarlama); çıktı sütun 0 = kaynak x=1.
        val w = 227
        val h = 224
        val img = PixelImage(IntArray(w * h) { argb(255, 0, 0, (it % w)) }, w, h)
        val rgb = ImagePreprocessor.resizeAndCrop(img)
        assertEquals(1, u8(rgb[2]))
        assertEquals(224, u8(rgb[223 * 3 + 2]))
        // 224x227 (dikey): üst = 1.
        val tall = PixelImage(IntArray(h * w) { argb(255, 0, 0, (it / h)) }, h, w)
        val rgbTall = ImagePreprocessor.resizeAndCrop(tall)
        assertEquals(1, u8(rgbTall[2]))
        assertEquals(224, u8(rgbTall[223 * 224 * 3 + 2]))
    }

    @Test
    fun aspectRatioLimit_isExactlyFiftyToOne_forNarrowAndWide() {
        // MAX_RESIZED_SIDE = 224 * 50 = 11200; uzun kenar = int(224 * uzun / kısa) > 11200 ise ret.
        assertFalse("1x50 kabul", rejected(1, 50))
        assertTrue("1x51 ret", rejected(1, 51))
        assertFalse("50x1 kabul", rejected(50, 1))
        assertTrue("51x1 ret", rejected(51, 1))
        assertFalse("3x150 kabul", rejected(3, 150))
        assertTrue("3x151 ret", rejected(3, 151)) // 224*151/3 = 11274
        assertFalse("10x500 kabul", rejected(10, 500))
        assertTrue("10x501 ret", rejected(10, 501))
        assertTrue("2x101 ret", rejected(2, 101)) // 224*101/2 = 11312
        assertFalse("2x100 kabul", rejected(2, 100))
        assertEquals(ImagePreprocessor.SIZE * 50, ImagePreprocessor.MAX_RESIZED_SIDE)
    }

    @Test
    fun rejectedImage_messageHasNoPathOrContent_andIsFotografaOzgu() {
        try {
            ImagePreprocessor.resizeAndCrop(PixelImage(IntArray(1000), 1, 1000))
            fail()
        } catch (e: ImageDecodeException) {
            assertFalse(ModelFailures.isModelLevel(e))
            assertFalse(e.message!!.contains("content://"))
            assertFalse(e.message!!.contains("/"))
        }
    }

    @Test
    fun narrowButAccepted_50to1_producesFullTensor_withoutOverflow() {
        val tall = PixelImage(IntArray(4 * 200) { argb(255, (it / 4) % 256, 0, 0) }, 4, 200) // 224*200/4 = 11200
        val rgb = ImagePreprocessor.resizeAndCrop(tall)
        assertEquals(224 * 224 * 3, rgb.size)
        // Merkez kırpma: ortadaki satırlar (kaynak ~100. satır civarı) alınır, uçlar (0 ve 199) değil.
        val mid = u8(rgb[(112 * 224) * 3])
        assertTrue("orta satır ~100, bulundu $mid", mid in 90..110)
    }

    // ---- tek renk, kanal sırası, taşma ----

    @Test
    fun channelOrder_isRgb_andCoversHighBitPixels() {
        // Alfa 0xFF -> Int negatif; kaydırma işareti taşımamalı.
        val red = ImagePreprocessor.resizeAndCrop(solid(8, 8, argb(255, 255, 0, 0)))
        assertEquals(255, u8(red[0])); assertEquals(0, u8(red[1])); assertEquals(0, u8(red[2]))
        val blue = ImagePreprocessor.resizeAndCrop(solid(8, 8, argb(255, 0, 0, 255)))
        assertEquals(0, u8(blue[0])); assertEquals(0, u8(blue[1])); assertEquals(255, u8(blue[2]))
    }

    @Test
    fun solidBlackAndWhite_mapToExactNormalizationBounds() {
        val black = ImagePreprocessor.toModelInput(solid(300, 200, argb(255, 0, 0, 0)))
        val white = ImagePreprocessor.toModelInput(solid(300, 200, argb(255, 255, 255, 255)))
        val plane = 224 * 224
        for (c in 0 until 3) {
            val lo = (0f - ImagePreprocessor.MEAN[c]) / ImagePreprocessor.STD[c]
            val hi = (1f - ImagePreprocessor.MEAN[c]) / ImagePreprocessor.STD[c]
            for (i in listOf(0, plane / 2, plane - 1)) {
                assertEquals(lo, black[c * plane + i], 1e-6f)
                assertEquals(hi, white[c * plane + i], 1e-6f)
            }
        }
    }

    @Test
    fun hardEdge_ringing_isClipped_noWraparound() {
        // Yarısı siyah yarısı beyaz keskin kenar: bicubic taşması 0..255'e kırpılmalı (sarma = ters renk olurdu).
        val w = 600
        val h = 448
        val img = PixelImage(IntArray(w * h) { if ((it % w) < w / 2) argb(255, 0, 0, 0) else argb(255, 255, 255, 255) }, w, h)
        val rgb = ImagePreprocessor.resizeAndCrop(img)
        // Kırpma alanı kaynakta x in [~188, ~412): kenar çıktının ortasında. Uçlar saf siyah/beyaz kalmalı.
        assertEquals(0, u8(rgb[0]))
        assertEquals(255, u8(rgb[223 * 3]))
        val t = ImagePreprocessor.toModelInput(img)
        for (c in 0 until 3) {
            val lo = (0f - ImagePreprocessor.MEAN[c]) / ImagePreprocessor.STD[c]
            val hi = (1f - ImagePreprocessor.MEAN[c]) / ImagePreprocessor.STD[c]
            for (i in 0 until 224 * 224) assertTrue(t[c * 224 * 224 + i] in (lo - 1e-5f)..(hi + 1e-5f))
        }
    }

    // ---- saydam piksel ----

    @Test
    fun transparentPixels_alphaIsIgnored_sameTensorAsOpaque() {
        val base = IntArray(64 * 48) { argb(255, (it * 3) % 256, (it * 5) % 256, (it * 7) % 256) }
        val opaque = PixelImage(base, 64, 48)
        for (alpha in listOf(0, 1, 0x80, 0xFE)) {
            val translucent = PixelImage(IntArray(base.size) { (base[it] and 0x00FFFFFF) or (alpha shl 24) }, 64, 48)
            assertArrayEquals("alfa $alpha", ImagePreprocessor.toModelInput(opaque), ImagePreprocessor.toModelInput(translucent), 0f)
        }
    }

    @Test
    fun fullyTransparentBlack_isTreatedAsBlack_notAsError() {
        val t = ImagePreprocessor.toModelInput(PixelImage(IntArray(10 * 10), 10, 10)) // 0x00000000
        val lo = (0f - ImagePreprocessor.MEAN[0]) / ImagePreprocessor.STD[0]
        assertEquals(lo, t[0], 1e-6f)
        assertTrue(t.none { it.isNaN() })
    }

    // ---- çok büyük boyut ----

    @Test
    fun hugeImage_12MP_resizesWithoutError_andStaysSolid() {
        val w = 4032
        val h = 3024
        val rgb = ImagePreprocessor.resizeAndCrop(solid(w, h, argb(255, 90, 180, 30)))
        assertEquals(224 * 224 * 3, rgb.size)
        for (i in listOf(0, 224 * 112 + 100, 224 * 224 - 1)) {
            assertEquals(90, u8(rgb[i * 3])); assertEquals(180, u8(rgb[i * 3 + 1])); assertEquals(30, u8(rgb[i * 3 + 2]))
        }
    }

    @Test
    fun sampleSize_neverExceedsCap_forExtremeAndDegenerateInputs() {
        assertEquals(64, ImagePreprocessor.sampleSize(Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(1, ImagePreprocessor.sampleSize(1, 1))
        assertEquals(1, ImagePreprocessor.sampleSize(1, Int.MAX_VALUE)) // kısa kenara göre
        assertEquals(1, ImagePreprocessor.sampleSize(Int.MAX_VALUE, 447))
        assertEquals(1, ImagePreprocessor.sampleSize(5000, 5000, maxSample = 1))
        assertEquals(8, ImagePreprocessor.sampleSize(8000, 6000)) // 6000/8 = 750 -> 1000x750 çözülür
        assertEquals(2, ImagePreprocessor.sampleSize(5000, 5000, maxSample = 3)) // 2'nin kuvveti, üst sınırı aşmaz
        for (bad in listOf(Triple(-1, 5, 448), Triple(5, -1, 448), Triple(5, 5, 0))) {
            try {
                ImagePreprocessor.sampleSize(bad.first, bad.second, bad.third)
                fail("$bad")
            } catch (_: IllegalArgumentException) {
            }
        }
        try {
            ImagePreprocessor.sampleSize(5, 5, maxSample = 0)
            fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun pixelImage_sizeCheckUsesLong_noIntOverflow() {
        // 65536 * 65536 Int'te 0'a sarar; boş dizi kabul edilmemeli.
        try {
            PixelImage(IntArray(0), 65_536, 65_536)
            fail()
        } catch (_: IllegalArgumentException) {
        }
        try {
            PixelImage(IntArray(1), -1, -1)
            fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    // ---- girdiyi değiştirmeme, normalize sınırları ----

    @Test
    fun resizeAndCrop_doesNotMutateInput() {
        val px = IntArray(37 * 91) { argb(255, it % 256, (it * 3) % 256, (it * 5) % 256) }
        val copy = px.copyOf()
        ImagePreprocessor.toModelInput(PixelImage(px, 37, 91))
        assertArrayEquals(copy, px)
    }

    @Test
    fun normalize_allZeroAndAll255_andWrongSizes() {
        val plane = 224 * 224
        val zero = ImagePreprocessor.normalize(ByteArray(plane * 3))
        val full = ImagePreprocessor.normalize(ByteArray(plane * 3) { 0xFF.toByte() })
        for (c in 0 until 3) {
            assertEquals((0f - ImagePreprocessor.MEAN[c]) / ImagePreprocessor.STD[c], zero[c * plane], 1e-6f)
            assertEquals((1f - ImagePreprocessor.MEAN[c]) / ImagePreprocessor.STD[c], full[c * plane + plane - 1], 1e-6f)
        }
        for (n in listOf(0, plane * 3 - 1, plane * 3 + 1)) {
            try {
                ImagePreprocessor.normalize(ByteArray(n))
                fail("n=$n")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    // ---- EXIF sınırları ----

    @Test
    fun exif_onOneByOneAndSingleRow_allOrientationsKeepPixelCount() {
        for (o in 1..8) {
            val one = ImagePreprocessor.applyExifOrientation(PixelImage(intArrayOf(7), 1, 1), o)
            assertEquals(1, one.width); assertEquals(1, one.height); assertEquals(7, one.pixels[0])
            val row = ImagePreprocessor.applyExifOrientation(PixelImage(intArrayOf(1, 2, 3, 4), 4, 1), o)
            assertEquals(4, row.pixels.size)
            assertEquals(4L, row.width.toLong() * row.height)
            assertEquals(setOf(1, 2, 3, 4), row.pixels.toSet())
            if (o >= 5) { assertEquals(1, row.width); assertEquals(4, row.height) }
        }
    }

    @Test
    fun exif_groupProperties_rotationsComposeToIdentity() {
        val src = PixelImage(IntArray(5 * 3) { it + 1 }, 5, 3)
        var r = src
        repeat(4) { r = ImagePreprocessor.applyExifOrientation(r, 6) }
        assertArrayEquals(src.pixels, r.pixels); assertEquals(5, r.width)
        r = src
        repeat(4) { r = ImagePreprocessor.applyExifOrientation(r, 8) }
        assertArrayEquals(src.pixels, r.pixels)
        for (o in listOf(2, 3, 4, 5, 7)) { // evrimsel (involution) dönüşümler
            val twice = ImagePreprocessor.applyExifOrientation(ImagePreprocessor.applyExifOrientation(src, o), o)
            assertArrayEquals("yön $o", src.pixels, twice.pixels)
            assertEquals(src.width, twice.width)
        }
        // 6 sonra 8 = kimlik.
        val back = ImagePreprocessor.applyExifOrientation(ImagePreprocessor.applyExifOrientation(src, 6), 8)
        assertArrayEquals(src.pixels, back.pixels)
    }

    @Test
    fun exifThenPreprocess_portraitPhotoWithRotation6_endsUpCenteredOnLongAxis() {
        // Kamera yatay 600x300 saklar, EXIF 6 ile dikey 300x600 görünür; sonuç dikey kırpma.
        val w = 600
        val h = 300
        val stored = PixelImage(IntArray(w * h) { argb(255, (it % w) * 255 / (w - 1), 0, 0) }, w, h)
        val oriented = ImagePreprocessor.applyExifOrientation(stored, 6)
        assertEquals(300, oriented.width); assertEquals(600, oriented.height)
        assertNotNull(ImagePreprocessor.toModelInput(oriented))
        // Dikeyde kırpma: görünür üst = depolanan sol; oriented 300x600 -> 224x448, kırpma satır 112..335 (oranın 0.25..0.75'i) => R ~ 64..191.
        val rgb = ImagePreprocessor.resizeAndCrop(oriented)
        val topR = u8(rgb[0])
        val bottomR = u8(rgb[(223 * 224) * 3])
        assertTrue("üst R $topR < alt R $bottomR", topR < bottomR)
        assertTrue("üst R $topR, alt R $bottomR", topR in 55..80 && bottomR in 175..200)
    }
}
