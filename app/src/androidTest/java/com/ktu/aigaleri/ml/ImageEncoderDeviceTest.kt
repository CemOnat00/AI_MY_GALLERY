package com.ktu.aigaleri.ml

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ktu.aigaleri.ui.GalleryPermission
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GERÇEK modelle (assets/models/vision_model_quantized.onnx, `tools/fetch_models.sh`) görüntü kodlama yolunu sınar:
 * [OnnxImageEncoder.encode], [ContentResolverImageLoader] (BitmapFactory örnekleme + EXIF), [OrtImageRunner],
 * oturum açma/kapama ve metin oturumuyla tek-oturum değişimi.
 *
 * DURUM: YAZILDI, DERLENDİ, ÇALIŞTIRILMADI (cihaz/emülatör yok). Model dosyası APK'da yoksa test başarısız olur.
 * Referans vektör `src/test/resources/ml/image_embedding_reference.tsv`: Python onnxruntime masaüstü, aynı int8 dosya,
 * [ImagePreprocessorTest] ile aynı sentetik girdi. ARM int8 çekirdekleriyle küçük sayısal fark beklenir (eşik 0.99).
 */
@RunWith(AndroidJUnit4::class)
class ImageEncoderDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store = ModelStore(context.filesDir, AssetManagerOpener(context.assets))
    private var inserted: Uri? = null

    @Before
    fun setUp() {
        // Her test kendi oturum anahtarını (slot) temiz başlatır.
        runBlocking { SingleSessionSlot.shared.closeAll() }
    }

    @After
    fun tearDown() {
        runBlocking { SingleSessionSlot.shared.closeAll() }
        inserted?.let { context.contentResolver.delete(it, null, null) }
    }

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

    private fun encoder(loader: PixelLoader) = OnnxImageEncoder(store, loader)

    private fun encodeOrExplain(e: OnnxImageEncoder, uri: String): FloatArray = runBlocking {
        try {
            e.encode(uri)
        } catch (ex: ModelException.Missing) {
            fail("Model dosyası APK'da yok: tools/fetch_models.sh çalıştırıp yeniden derleyin (${ex.assetPath})")
            error("unreachable")
        }
    }

    private fun norm(v: FloatArray) = sqrt(v.sumOf { it.toDouble() * it })

    private fun cosine(a: FloatArray, b: FloatArray): Double = a.indices.sumOf { a[it].toDouble() * b[it] } / (norm(a) * norm(b))

    private fun reference(w: Int, h: Int): FloatArray {
        val text = instrumentation.context.assets.open("ml/image_embedding_reference.tsv").use { it.readBytes().toString(Charsets.UTF_8) }
        val line = text.split('\n').filter { it.isNotEmpty() && !it.startsWith("#") }
            .single { it.startsWith("$w\t$h\t") }
        return line.split('\t')[2].trim().split(' ').map { it.toFloat() }.toFloatArray()
    }

    @Test
    fun encode_returns512DimUnitVector_matchingTextSpec() {
        val e = encoder { synthetic(640, 480) }
        val v = encodeOrExplain(e, "ignored")
        assertEquals(512, v.size)
        assertEquals(1.0, norm(v), 1e-4)
        assertEquals(ModelManifest.EMBEDDING_SPEC, e.embeddingSpec)
        runBlocking { e.release() }
    }

    @Test
    fun encode_matchesPythonReference_forSyntheticImages() {
        for ((w, h) in listOf(640 to 480, 480 to 640)) {
            val e = encoder { synthetic(w, h) }
            val c = cosine(encodeOrExplain(e, "ignored"), reference(w, h))
            assertTrue("$w x $h kosinüs $c", c > 0.99)
            runBlocking { e.release() }
        }
    }

    @Test
    fun encode_isDeterministic_andReleaseThenEncodeReopensSession() {
        val e = encoder { synthetic(300, 300) }
        val a = encodeOrExplain(e, "x")
        runBlocking { e.release() }
        val b = encodeOrExplain(e, "x")
        assertTrue(cosine(a, b) > 0.9999)
        runBlocking { e.release() }
    }

    @Test
    fun textAndImageSessions_swapThroughSingleSlot() {
        val image = encoder { synthetic(300, 300) }
        val text = OnnxTextEncoder.create(context)
        val v1 = encodeOrExplain(image, "x")
        val t = runBlocking { text.encode("plajda gülen çocuk") }
        val v2 = encodeOrExplain(image, "x") // metin oturumu image'ı kapatmıştı; yeniden açılır
        assertEquals(512, t.size)
        assertTrue(cosine(v1, v2) > 0.9999)
        runBlocking { text.release(); image.release() }
    }

    @Test
    fun loader_decodesMediaStoreJpeg_smallAndUpright() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, GalleryPermission.requiredPermission())
        val bmp = Bitmap.createBitmap(1800, 1200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 30, 30)) }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "aigaleri_enc_${System.nanoTime()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AiGaleriTest")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        assertNotNull(uri)
        inserted = uri
        context.contentResolver.openOutputStream(uri!!)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()

        val image = ContentResolverImageLoader(context.contentResolver).load(uri.toString())
        // 1200 / 2 = 600 >= 448 ve 1200 / 4 = 300 < 448 -> örnekleme 2: 900x600.
        assertEquals(900, image.width)
        assertEquals(600, image.height)
        assertEquals(image.width * image.height, image.pixels.size)

        val v = encodeOrExplain(encoder(ContentResolverImageLoader(context.contentResolver)), uri.toString())
        assertEquals(512, v.size)
        assertEquals(1.0, norm(v), 1e-4)
    }

    @Test
    fun loader_unreadableUri_isDecodeException_notCrash() {
        val loader = ContentResolverImageLoader(context.contentResolver)
        try {
            loader.load("content://media/external/images/media/${Long.MAX_VALUE}")
            fail("ImageDecodeException beklenir")
        } catch (_: ImageDecodeException) {
        } catch (_: SecurityException) {
        }
    }
}
