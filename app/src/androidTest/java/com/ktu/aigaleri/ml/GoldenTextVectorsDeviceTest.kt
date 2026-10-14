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
 * dosyalarıyla (masaüstü, Apple Silicon, ORT 1.30.0) ürettiği altın vektörlere kosinüs >= [MIN_COSINE] olmalı.
 * Altın dosya `src/test/resources/ml/golden_text_vectors.tsv`, androidTest assets olarak paketlenir (build.gradle.kts).
 * Sorgu anahtarlarındaki görünmez/ayrışık karakterler `\uXXXX` kaçışlıdır; [unescape] ile çözülür.
 *
 * EŞİK (T-035): [MIN_COSINE] = 0,995, bir "ÇEKİRDEK FARKI BANDI"dır; regresyon kapısı DEĞİLDİR. ARM cihaz (arm64 emülatör,
 * ORT 1.30.0 AAR, KleidiAI kapalı, BUDANMIŞ model) ölçümü: min 0,99775, ort 0,99922, 75/76 sorgu < 0,9999, 20 sorgu < 0,999,
 * 0,99 altında yok. Bu ~2e-3'lük fark budamadan değil int8 dinamik nicemleme çekirdek farkındandır: masaüstünde budanmış
 * model orijinalle bit özdeştir, masaüstünde graf optimizasyonu BASIC/kapalıyken de 1-kosinüs ~2,2e-3 çıkar. Bu yüzden eski
 * 0,9999 cihaz kapısı anlamsızdı. 0,9999 JVM/masaüstü kapılarında aynen geçerlidir (DEĞİŞMEDİ). Ölçümler, sıralama etkisi
 * (resimli testte hit@5/MRR bozulmadı) ve gerekçe: docs/golden-text.md "Cihaz ölçümü".
 * Bu test budama/.ort (T-027/T-030) regresyonunu TEK BAŞINA YAKALAMAZ: o kapı için aynı cihazda orijinal modelle
 * karşılaştırma önerilir (docs/golden-text.md; T-030 ön koşulu). Fiziksel telefonda (KleidiAI açık) ölçülmedi.
 *
 * DURUM: T-012'de Pixel_8 API 35 arm64 emülatöründe çalıştırıldı (emülatörde KleidiAI kapalı oturumla, bkz.
 * [OrtEmulatorWorkaround]). Model dosyaları APK'da yoksa test başarısız olur (sessiz atlama yok).
 * Sorgu metni ve vektör log'a yazılmaz (yalnızca sıra no ve kosinüs).
 */
@RunWith(AndroidJUnit4::class)
class GoldenTextVectorsDeviceTest {
    private lateinit var encoder: OnnxTextEncoder

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        encoder = OnnxTextEncoder.create(context)
        // Emülatör SME/KleidiAI SIGILL geçici çözümü (gerçek telefonda etkisiz; bkz. OrtEmulatorWorkaround).
        OrtEmulatorWorkaround.seedText(ModelStore(context.filesDir, AssetManagerOpener(context.assets)))
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
        var sum = 0.0
        val all = ArrayList<Double>()
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
            sum += c
            all.add(c)
            if (c < MIN_COSINE) failures.add("#$index kosinüs=$c")
        }
        // Ölçüm çıktısı (T-012; docs/golden-text.md 'Cihaz ölçümü'): yalnızca sayılar, sorgu metni yok.
        android.util.Log.i(
            "GoldenText",
            "n=${golden.size} minCosine=$worst meanCosine=${sum / golden.size} " +
                "below0.9999=${all.count { it < 0.9999 }} below0.999=${all.count { it < 0.999 }} below0.99=${all.count { it < 0.99 }} " +
                "kleidiaiDisabledWorkaround=${OrtEmulatorWorkaround.needed}",
        )
        assertTrue("kosinüs < $MIN_COSINE (en kötü $worst): $failures", failures.isEmpty())
    }

    private companion object {
        /** Çekirdek farkı bandı (T-035); ölçülen min 0,99775. Gerekçe KDoc ve docs/golden-text.md. */
        const val MIN_COSINE = 0.995
    }
}
