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
 * T-026: GERÇEK [OnnxTextEncoder] ile 76 altın sorgunun vektörleri, `tools/golden_text.py`'nin ORİJİNAL model
 * dosyalarıyla ürettiği altın vektörlere kosinüs >= [MIN_COSINE] olmalı (gerekçe: docs/golden-text.md).
 * T-027: aynı test BUDANMIŞ metin modeli/sözlüğü (Katman A) için cihaz kapısıdır; masaüstünde budanmış model altınla bit özdeş
 * (kosinüs 1,0), cihazda da >= [MIN_COSINE] beklenir (T-012 sonrası ölçülüp docs/golden-text.md'ye yazılır).
 * Altın dosya `src/test/resources/ml/golden_text_vectors.tsv`, androidTest assets olarak paketlenir (build.gradle.kts).
 * Sorgu anahtarlarındaki görünmez/ayrışık karakterler `\uXXXX` kaçışlıdır; [unescape] ile çözülür.
 *
 * DURUM: YAZILDI ve `compileDebugAndroidTestKotlin` ile DERLENDİ; ÇALIŞTIRILMADI (cihaz/emülatör yok). Model
 * dosyaları APK'da yoksa test başarısız olur (sessiz atlama yok).
 *
 * PLATFORM UYARISI: altın, Apple Silicon masaüstünde pip `onnxruntime` 1.30.0 ile üretildi; bu test Android AAR
 * ORT'siyle (farklı derleme ve ARM çekirdekleri) çalışır. x86_64 emülatör, arm64 için nicemlenmiş modeli farklı bir
 * çekirdek yoluyla çalıştırabilir. Masaüstünde graf optimizasyonu BASIC/kapalıyken 1-kosinüs ~2,2e-3 ölçüldü; cihaz
 * tabanı ÖLÇÜLMEDİ (ilk cihaz ölçümü T-012'ye bağlı; sonuç docs/golden-text.md "Cihaz ölçümü" bölümüne yazılır).
 * Test ilk kez cihazda düşerse eşiği körlemesine gevşetmeyin: en kötü kosinüs ve dağılım belgeye işlenip karar verilir.
 * Sorgu metni ve vektör log'a yazılmaz (yalnızca sıra no ve kosinüs).
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
            unescape(line.substring(0, tab)) to line.substring(tab + 1).split(' ').map { it.toFloat() }.toFloatArray()
        }
    }

    private fun unescape(s: String): String =
        Regex("""\\u([0-9A-Fa-f]{4})""").replace(s) { it.groupValues[1].toInt(16).toChar().toString() }

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
        assertEquals(76, golden.size)
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
