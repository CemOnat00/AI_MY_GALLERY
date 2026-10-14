package com.ktu.aigaleri.ml

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ktu.aigaleri.ui.AppDependencies
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-033: GERÇEK [OnnxTextEncoder] + [PromptEnsembleTextEncoder] ile `tools/query_ensemble.py --golden`'in ürettiği
 * `expect` (ensemble) vektörlerine kosinüs >= [MIN_COSINE] (GoldenTextVectorsDeviceTest mantığı; Python referansı ORİJİNAL
 * model, masaüstü ORT 1.30.0). Altın dosya `src/test/resources/ml/query_ensemble_golden.tsv`, androidTest assets olarak
 * paketlenir (build.gradle.kts, `src/test/resources`). Ayrıca `AppDependencies.searchTextEncoder`'ın şablon topluluğu olduğunu
 * ve çıplak kodlayıcıdan farklı vektör ürettiğini (yani arama yolunun çıplak kodlayıcıyı kullanmadığını) sınar.
 *
 * EŞİK (T-035): [MIN_COSINE] = 0,995, "ÇEKİRDEK FARKI BANDI"dır (çıplak altın testiyle aynı eşik); regresyon kapısı DEĞİLDİR.
 * ARM cihaz (arm64 emülatör, ORT 1.30.0 AAR, KleidiAI kapalı, budanmış model) ölçümü: min 0,99881, ort 0,99951; eski 0,999
 * eşiği farkla kaçtı. Fark int8 dinamik nicemleme çekirdek farkıdır (~2e-3 gürültü; masaüstünde BASIC/kapalı graf
 * optimizasyonunda da 1-kosinüs ~2,2e-3), budamadan değil. Sıralama etkisi: resimli testte TR ensemble hit@5 1,00, MRR 1,00.
 * Masaüstü/JVM kapıları (`PromptEnsembleGoldenTest`, bileşen farkı < 2e-6) DEĞİŞMEDİ. Ayrıntı: docs/golden-text.md
 * "Cihaz ölçümü". Budama/.ort regresyonu için "orijinal modelle AYNI cihazda" karşılaştırma ayrıca önerilir (T-030 ön koşulu).
 *
 * DURUM: qa emülatörde çalıştırdı (KleidiAI kapalı oturum, bkz. [OrtEmulatorWorkaround]); fiziksel telefonda ölçülmedi.
 * Model dosyaları APK'da yoksa test başarısız olur.
 * Sorgu metni ve vektör log'a yazılmaz (yalnızca sıra no ve kosinüs).
 */
@RunWith(AndroidJUnit4::class)
class PromptEnsembleDeviceTest {
    private lateinit var bare: OnnxTextEncoder

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        bare = OnnxTextEncoder.create(context)
        // Emülatör SME/KleidiAI SIGILL geçici çözümü (gerçek telefonda etkisiz; bkz. OrtEmulatorWorkaround).
        OrtEmulatorWorkaround.seedText(ModelStore(context.filesDir, AssetManagerOpener(context.assets)))
    }

    @After
    fun tearDown() = runBlocking { bare.release() }

    private fun unescape(s: String): String =
        Regex("""\\u([0-9A-Fa-f]{4})""").replace(s) { it.groupValues[1].toInt(16).toChar().toString() }

    /** `expect` satırları: ham sorgu -> Python ensemble vektörü. */
    private fun expectRows(): List<Pair<String, FloatArray>> {
        val text = InstrumentationRegistry.getInstrumentation().context.assets
            .open("ml/query_ensemble_golden.tsv").use { it.readBytes().toString(Charsets.UTF_8) }
        return text.split('\n').filter { it.startsWith("expect\t") }.map { line ->
            val p = line.split('\t')
            unescape(p[1]) to p[3].split(' ').map { it.toFloat() }.toFloatArray()
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
    fun ensembleVectors_matchPythonReference() = runBlocking {
        val rows = expectRows()
        assertTrue("altın dosyada yeterli sorgu yok: ${rows.size}", rows.size >= 20)
        val ensemble = PromptEnsembleTextEncoder(bare)
        var worst = 1.0
        var sum = 0.0
        val failures = ArrayList<String>()
        for ((index, row) in rows.withIndex()) {
            val got = ensemble.encode(row.first)
            assertEquals(512, got.size)
            val c = cosine(got, row.second)
            if (c < worst) worst = c
            sum += c
            if (c < MIN_COSINE) failures.add("#$index kosinüs=$c")
        }
        android.util.Log.i("PromptEnsemble", "n=${rows.size} minCosine=$worst meanCosine=${sum / rows.size} kleidiaiDisabledWorkaround=${OrtEmulatorWorkaround.needed}")
        assertTrue("kosinüs < $MIN_COSINE (en kötü $worst): $failures", failures.isEmpty())
    }

    @Test
    fun appSearchEncoder_isEnsemble_andDiffersFromBareEncoder() = runBlocking {
        val deps = AppDependencies.get(ApplicationProvider.getApplicationContext())
        OrtEmulatorWorkaround.seedText(deps.modelStore, deps.sessionSlot)
        try {
            assertTrue("arama kodlayıcısı şablon topluluğu olmalı", deps.searchTextEncoder is PromptEnsembleTextEncoder)
            val searched = deps.searchTextEncoder.encode("bardak")
            val plain = deps.textEncoder.encode("bardak")
            val c = cosine(searched, plain)
            // Ölçüm (masaüstü): ensemble-çıplak kosinüs ~0,986; çıplakla özdeş (>= 0,99999) olması ensemble'ın devrede olmadığı anlamına gelir.
            assertTrue("ensemble çıplakla özdeş görünüyor (cos=$c)", c < 0.99999)
            assertTrue("ensemble çıplaktan çok uzak (cos=$c)", c > 0.9)
            val expected = expectRows().first { it.first == "bardak" }.second
            assertTrue("altınla uyumsuz", cosine(searched, expected) >= MIN_COSINE)
        } finally {
            deps.textEncoder.release()
        }
    }

    private companion object {
        /** Çekirdek farkı bandı (T-035); ölçülen min 0,99881. Gerekçe KDoc ve docs/golden-text.md. */
        const val MIN_COSINE = 0.995
    }
}
