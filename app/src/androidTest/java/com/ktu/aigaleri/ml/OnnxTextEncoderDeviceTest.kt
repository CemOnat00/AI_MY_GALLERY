package com.ktu.aigaleri.ml

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ktu.aigaleri.domain.InvalidQueryException
import kotlin.math.sqrt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GERÇEK modelle (assets/models/, `tools/fetch_models.sh`) ONNX yolunu sınar: [OnnxTextEncoder.encode],
 * [OrtHiddenStateRunner], oturum açma, [OnnxTextEncoder.release] ve slot eviction.
 *
 * DURUM: YAZILDI, DERLENDİ, ÇALIŞTIRILMADI (cihaz/emülatör yok). Model dosyaları APK'da yoksa test başarısız
 * olur (sessiz atlama yok). Referans vektörler `src/test/resources/ml/embedding_reference.tsv` (Python
 * onnxruntime masaüstü, aynı int8 dosya); cihazda ARM int8 çekirdekleriyle küçük sayısal fark beklenir,
 * bu yüzden kosinüs eşiği 0.99.
 */
@RunWith(AndroidJUnit4::class)
class OnnxTextEncoderDeviceTest {
    private lateinit var encoder: OnnxTextEncoder

    @Before
    fun setUp() {
        encoder = OnnxTextEncoder.create(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = runBlocking { encoder.release() }

    private fun encodeOrExplain(q: String): FloatArray = runBlocking {
        try {
            encoder.encode(q)
        } catch (e: ModelException.Missing) {
            fail("Model dosyası APK'da yok: tools/fetch_models.sh çalıştırıp yeniden derleyin (${e.assetPath})")
            error("unreachable")
        }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return dot / (sqrt(na) * sqrt(nb))
    }

    private fun norm(v: FloatArray) = sqrt(v.sumOf { it.toDouble() * it })

    private fun references(): List<Pair<String, FloatArray>> {
        val text = InstrumentationRegistry.getInstrumentation().context.assets
            .open("ml/embedding_reference.tsv").use { it.readBytes().toString(Charsets.UTF_8) }
        return text.split('\n').filter { it.isNotEmpty() }.map { line ->
            val p = line.split('\t')
            p[0] to p[2].trim().split(' ').map { it.toFloat() }.toFloatArray()
        }
    }

    @Test
    fun encode_returns512DimUnitVector() {
        val v = encodeOrExplain("plajda gülen çocuk")
        assertEquals(512, v.size)
        assertEquals(1.0, norm(v), 1e-4)
        assertEquals(encoder.embeddingSpec.dimension, v.size)
    }

    @Test
    fun encode_isCloseToPythonReferenceVectors() {
        val refs = references()
        assertTrue(refs.size >= 3)
        for ((query, expected) in refs) {
            val got = encodeOrExplain(query)
            assertTrue("kosinüs düşük: ${cosine(got, expected)}", cosine(got, expected) >= 0.99)
        }
    }

    @Test
    fun uppercaseTurkishQuery_matchesLowercaseQuery() {
        val upper = encodeOrExplain("IŞIK ALTINDA KEDİ")
        val lower = encodeOrExplain("ışık altında kedi")
        assertTrue(cosine(upper, lower) > 0.9999)
    }

    @Test
    fun release_thenEncode_reopensSession() {
        val first = encodeOrExplain("dağda kar")
        runBlocking { encoder.release() }
        val second = encodeOrExplain("dağda kar")
        assertTrue(cosine(first, second) > 0.9999)
    }

    @Test
    fun slotEviction_byAnotherModel_thenEncodeStillWorks() {
        encodeOrExplain("kedi")
        runBlocking {
            // T-006 görüntü oturumunun yerini tutan sahte oturum, metin oturumunu slot'tan çıkarır.
            SingleSessionSlot.shared.withSession("vision-placeholder", open = { AutoCloseable { } }) { }
        }
        assertEquals(512, encodeOrExplain("kedi").size)
    }

    @Test
    fun concurrentEncodes_areSerializedAndConsistent() = runBlocking {
        val a = encodeOrExplain("sahilde yürüyen insanlar")
        val results = (1..6).map { async { encoder.encode("sahilde yürüyen insanlar") } }.awaitAll()
        for (r in results) assertTrue(cosine(a, r) > 0.9999)
    }

    @Test
    fun invalidQuery_isRejected() = runBlocking {
        try {
            encoder.encode("   ")
            fail()
        } catch (_: InvalidQueryException) {
        }
    }
}
