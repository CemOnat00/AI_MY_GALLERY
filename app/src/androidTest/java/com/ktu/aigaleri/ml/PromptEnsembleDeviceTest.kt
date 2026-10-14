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
 * DURUM: YAZILDI ve derlendi; ÇALIŞTIRMAYI qa yapar (emülatör/cihaz). Model dosyaları APK'da yoksa test başarısız olur.
 * Eşik 0,999 (tek çıkarım altını için 0,9999'dan gevşek): varyantlar farklı uzunlukta olduğundan ve ortalama alındığından
 * cihaz-masaüstü çekirdek farkı biraz büyüyebilir; ilk cihaz ölçümünde en kötü kosinüs belgeye işlenir, eşik körlemesine gevşetilmez.
 * Sorgu metni ve vektör log'a yazılmaz (yalnızca sıra no ve kosinüs).
 */
@RunWith(AndroidJUnit4::class)
class PromptEnsembleDeviceTest {
    private lateinit var bare: OnnxTextEncoder

    @Before
    fun setUp() {
        bare = OnnxTextEncoder.create(ApplicationProvider.getApplicationContext())
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
        val failures = ArrayList<String>()
        for ((index, row) in rows.withIndex()) {
            val got = ensemble.encode(row.first)
            assertEquals(512, got.size)
            val c = cosine(got, row.second)
            if (c < worst) worst = c
            if (c < MIN_COSINE) failures.add("#$index kosinüs=$c")
        }
        assertTrue("kosinüs < $MIN_COSINE (en kötü $worst): $failures", failures.isEmpty())
    }

    @Test
    fun appSearchEncoder_isEnsemble_andDiffersFromBareEncoder() = runBlocking {
        val deps = AppDependencies.get(ApplicationProvider.getApplicationContext())
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
        const val MIN_COSINE = 0.999
    }
}
