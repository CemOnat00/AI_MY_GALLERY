package com.ktu.aigaleri.ml

import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * T-006: görüntü ön işleme matematiği. Referans: Python/Pillow (`Image.resize(BICUBIC)` + merkez kırpma, HF
 * `CLIPImageProcessor` ile aynı çıktı, fark 0 doğrulandı) -> `ml/image_preprocess_reference.tsv`. Girdi görüntüleri
 * aşağıdaki formülle (Python'daki ile aynı) üretilir; referans her 211. uint8 değeri ve toplamdır.
 */
class ImagePreprocessorTest {
    private fun synthetic(w: Int, h: Int): PixelImage {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var rgb = 0xFF
            for (c in 0 until 3) {
                var v = x * (3 + c) + y * (5 + 2 * c) + ((x * y) % 97) * 3
                if (((x / 16) + (y / 16)) % 2 == 0) v += 40
                rgb = (rgb shl 8) or (v % 256)
            }
            px[y * w + x] = rgb
        }
        return PixelImage(px, w, h)
    }

    private fun solid(w: Int, h: Int, r: Int, g: Int, b: Int) =
        PixelImage(IntArray(w * h) { (0xFF shl 24) or (r shl 16) or (g shl 8) or b }, w, h)

    @Test
    fun matchesPillowReference_forAllCases() {
        val lines = javaClass.getResourceAsStream("/ml/image_preprocess_reference.tsv")!!
            .bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(10, lines.size)
        for (line in lines) {
            val f = line.split('\t')
            val w = f[0].toInt()
            val h = f[1].toInt()
            val image = synthetic(w, h)
            val rgb = ImagePreprocessor.resizeAndCrop(image)
            assertEquals("$w x $h boyut", 224 * 224 * 3, rgb.size)
            assertEquals("$w x $h toplam", f[2].toLong(), rgb.sumOf { (it.toInt() and 0xFF).toLong() })
            val expected = f[3].split(',').map { it.toInt() }
            expected.forEachIndexed { i, e ->
                assertEquals("$w x $h uint8 örnek $i", e, rgb[i * STRIDE].toInt() and 0xFF)
            }
            val tensor = ImagePreprocessor.toModelInput(image)
            assertEquals(ImagePreprocessor.TENSOR_SIZE, tensor.size)
            f[4].split(',').map { it.toFloat() }.forEachIndexed { i, e ->
                assertEquals("$w x $h normalize örnek $i", e, tensor[i * STRIDE], 1e-5f)
            }
        }
    }

    @Test
    fun constants_matchModelResearch() {
        assertArrayEquals(floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f), ImagePreprocessor.MEAN, 0f)
        assertArrayEquals(floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f), ImagePreprocessor.STD, 0f)
        assertEquals(224, ImagePreprocessor.SIZE)
    }

    @Test
    fun normalize_boundsAndChannelOrder_areChw() {
        // Piksel (0,0): R=0, G=255, B=128; diğerleri 0.
        val rgb = ByteArray(224 * 224 * 3)
        rgb[0] = 0; rgb[1] = 255.toByte(); rgb[2] = 128.toByte()
        val t = ImagePreprocessor.normalize(rgb)
        val plane = 224 * 224
        assertEquals((0f - 0.48145466f) / 0.26862954f, t[0], 1e-6f)
        assertEquals((1f - 0.4578275f) / 0.26130258f, t[plane], 1e-6f)
        assertEquals((128f / 255f - 0.40821073f) / 0.27577711f, t[2 * plane], 1e-6f)
        // Diğer piksel (1,0): hepsi 0.
        assertEquals((0f - 0.48145466f) / 0.26862954f, t[1], 1e-6f)
    }

    @Test
    fun solidImage_staysSolid_afterResizeAndCrop() {
        // Bicubic katsayıları toplamı 1: düz renk her boyutta aynı kalır (taşma/yuvarlama hatası yok).
        for ((w, h) in listOf(224 to 224, 640 to 480, 100 to 80, 33 to 47, 1000 to 300)) {
            val rgb = ImagePreprocessor.resizeAndCrop(solid(w, h, 200, 7, 255))
            for (i in 0 until 224 * 224) {
                assertEquals("$w x $h", 200, rgb[i * 3].toInt() and 0xFF)
                assertEquals(7, rgb[i * 3 + 1].toInt() and 0xFF)
                assertEquals(255, rgb[i * 3 + 2].toInt() and 0xFF)
            }
        }
    }

    @Test
    fun alreadySize_isIdentity() {
        val img = synthetic(224, 224)
        val rgb = ImagePreprocessor.resizeAndCrop(img)
        for (i in 0 until 224 * 224) {
            val p = img.pixels[i]
            assertEquals((p shr 16) and 0xFF, rgb[i * 3].toInt() and 0xFF)
            assertEquals((p shr 8) and 0xFF, rgb[i * 3 + 1].toInt() and 0xFF)
            assertEquals(p and 0xFF, rgb[i * 3 + 2].toInt() and 0xFF)
        }
    }

    @Test
    fun centerCrop_picksMiddle_forWideImage() {
        // 448x224: kısa kenar zaten 224, yalnızca kırpma; x in [112, 336) kalır (sol = (448-224)/2).
        val w = 448
        val h = 224
        val img = PixelImage(IntArray(w * h) { i -> (0xFF shl 24) or ((i % w) and 0xFF) }, w, h)
        val rgb = ImagePreprocessor.resizeAndCrop(img)
        assertEquals(112 and 0xFF, rgb[2].toInt() and 0xFF) // B kanalı = x, ilk kırpılmış sütun
        assertEquals(335 and 0xFF, rgb[223 * 3 + 2].toInt() and 0xFF)
    }

    @Test
    fun resizedSide_truncates_likeHuggingFace() {
        // 33x47: kısa=33, uzun 224*47/33 = 319.03 -> 319, kırpma üst = (319-224)/2 = 47 (aşağı).
        // Referans dosyası bu durumu kapsar; burada boyutlar bozulmadan 224x224 kırpıldığını doğrula.
        assertEquals(224 * 224 * 3, ImagePreprocessor.resizeAndCrop(synthetic(33, 47)).size)
    }

    @Test
    fun extremeAspectRatio_isRejected_withoutHugeAllocation() {
        try {
            ImagePreprocessor.resizeAndCrop(PixelImage(IntArray(30000), 1, 30000))
            fail("ImageDecodeException beklenir")
        } catch (_: ImageDecodeException) {
        }
        // Sınırın hemen altı (50:1) kabul edilir.
        val ok = PixelImage(IntArray(10 * 500), 10, 500)
        assertEquals(224 * 224 * 3, ImagePreprocessor.resizeAndCrop(ok).size)
    }

    @Test
    fun pixelImage_validatesSize() {
        for ((w, h, n) in listOf(Triple(0, 1, 0), Triple(1, 0, 0), Triple(2, 2, 3))) {
            try {
                PixelImage(IntArray(n), w, h)
                fail()
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun sampleSize_keepsShortSideAtLeast448_andIsPowerOfTwo() {
        assertEquals(1, ImagePreprocessor.sampleSize(448, 448))
        assertEquals(1, ImagePreprocessor.sampleSize(895, 900)) // 895/2 = 447 < 448
        assertEquals(2, ImagePreprocessor.sampleSize(896, 1200))
        assertEquals(4, ImagePreprocessor.sampleSize(4000, 3000)) // 3000/4 = 750 >= 448, 3000/8 = 375 < 448
        assertEquals(1, ImagePreprocessor.sampleSize(100, 100))
        assertEquals(1, ImagePreprocessor.sampleSize(224, 50_000)) // kısa kenara göre
        assertEquals(64, ImagePreprocessor.sampleSize(1_000_000, 1_000_000)) // üst sınır
        for (w in listOf(1, 447, 448, 449, 1000, 12_000)) for (h in listOf(1, 700, 4000)) {
            val s = ImagePreprocessor.sampleSize(w, h)
            assertTrue(s >= 1 && (s and (s - 1)) == 0)
            if (s > 1) assertTrue(minOf(w, h) / s >= 448)
        }
        try {
            ImagePreprocessor.sampleSize(0, 10)
            fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    // ---- EXIF yönü: 2x3 görüntü, değerler konumu belirtir ----

    private fun grid(): PixelImage = PixelImage(intArrayOf(1, 2, 3, 4, 5, 6), 3, 2) // 3 geniş, 2 yüksek: [1 2 3; 4 5 6]

    @Test
    fun exif_normalAndInvalid_returnSameInstance() {
        val g = grid()
        for (o in listOf(0, 1, 9, -1, 100)) assertSame(g, ImagePreprocessor.applyExifOrientation(g, o))
    }

    @Test
    fun exif_allEightOrientations() {
        val expected = mapOf(
            2 to Triple(3, 2, intArrayOf(3, 2, 1, 6, 5, 4)),       // yatay ayna
            3 to Triple(3, 2, intArrayOf(6, 5, 4, 3, 2, 1)),       // 180
            4 to Triple(3, 2, intArrayOf(4, 5, 6, 1, 2, 3)),       // dikey ayna
            5 to Triple(2, 3, intArrayOf(1, 4, 2, 5, 3, 6)),       // transpoze
            6 to Triple(2, 3, intArrayOf(4, 1, 5, 2, 6, 3)),       // 90 saat yönü
            7 to Triple(2, 3, intArrayOf(6, 3, 5, 2, 4, 1)),       // transverse
            8 to Triple(2, 3, intArrayOf(3, 6, 2, 5, 1, 4)),       // 270 saat yönü
        )
        for ((o, e) in expected) {
            val out = ImagePreprocessor.applyExifOrientation(grid(), o)
            assertEquals("yön $o genişlik", e.first, out.width)
            assertEquals("yön $o yükseklik", e.second, out.height)
            assertArrayEquals("yön $o", e.third, out.pixels)
        }
    }

    @Test
    fun exif_doesNotMutateInput() {
        val g = grid()
        ImagePreprocessor.applyExifOrientation(g, 6)
        assertArrayEquals(intArrayOf(1, 2, 3, 4, 5, 6), g.pixels)
    }

    @Test
    fun resizeResult_isDeterministic() {
        val a = ImagePreprocessor.toModelInput(synthetic(500, 375))
        val b = ImagePreprocessor.toModelInput(synthetic(500, 375))
        assertArrayEquals(a, b, 0f)
        assertTrue(a.all { abs(it) < 10f && !it.isNaN() })
    }

    private companion object {
        const val STRIDE = 211
    }
}
