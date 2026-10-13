package com.ktu.aigaleri.ml

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-026: GERÇEK [OnnxTextEncoder] ile 60 altın sorgunun vektörleri, `tools/golden_text.py`'nin ORİJİNAL model
 * dosyalarıyla ürettiği altın vektörlere kosinüs >= [MIN_COSINE] olmalı (gerekçe: docs/golden-text.md).
 * Altın dosya `src/test/resources/ml/golden_text_vectors.tsv`, androidTest assets olarak paketlenir (build.gradle.kts).
 *
 * DURUM: YAZILDI ve DERLENİR, ÇALIŞTIRILMADI (cihaz/emülatör yok). Model dosyaları APK'da yoksa test başarısız olur
 * (sessiz atlama yok). UYARI: eşik masaüstü (Apple Silicon arm64) referansına karşı Android ARM cihazda uygulanır; farklı çekirdek yolu (ör.
 * int8 MatMul/LayerNorm) kosinüsü düşürebilir (masaüstünde graf optimizasyonu kapatınca 1-kosinüs ~2e-3 ölçüldü,
 * cihaz tabanı ÖLÇÜLMEDİ). Bu test cihazda ilk kez düşerse eşiği körlemesine gevşetmeyin; en kötü kosinüs ve
 * dağılım docs/golden-text.md'ye işlenip karar verilir. Sorgu metni ve vektör log'a yazılmaz (yalnızca sıra no ve kosinüs).
 */
@RunWith(AndroidJUnit4::class)
class GoldenTextVectorsDeviceTest {
    private lateinit var encoder: OnnxTextEncoder

    @Before
    fun setUp() {
        encoder = OnnxTextEncoder.create(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() = runBlocking { encoder.release() }

    private fun goldenVectors(): List<Pair<String, FloatArray>> {
        val text = InstrumentationRegistry.getInstrumentation().context.assets
            .open("ml/golden_text_vectors.tsv").use { it.readBytes().toString(Charsets.UTF_8) }
        return text.split('\n').filter { it.isNotEmpty() }.map { line ->
            val tab = line.indexOf('\t')
            line.substring(0, tab) to line.substring(tab + 1).split(' ').map { it.toFloat() }.toFloatArray()
        }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            na += a[i].toDouble() * a[i]
            nb += b[i].toDouble() * b[i]
        }
        return dot / (sqrt(na) * sqrt(nb))
    }

    @Test
    fun all60GoldenQueries_haveCosineAtLeastThreshold() = runBlocking {
        val golden = goldenVectors()
        assertEquals(60, golden.size)
        var worst = 1.0
        val failures = ArrayList<String>()
        for ((index, entry) in golden.withIndex()) {
            val (query, expected) = entry
            val got = try {
                encoder.encode(query)
            } catch (e: ModelException.Missing) {
                fail("Model dosyası APK'da yok: tools/fetch_models.sh çalıştırıp yeniden derleyin (${e.assetPath})")
                return@runBlocking
            }
            assertEquals(512, got.size)
            val c = cosine(got, expected)
            if (c < worst) worst = c
            if (c < MIN_COSINE) failures.add("#$index kosinüs=$c")
        }
        assertTrue("kosinüs < $MIN_COSINE (en kötü $worst): $failures", failures.isEmpty())
    }

    private companion object {
        const val MIN_COSINE = 0.9999
    }
}
