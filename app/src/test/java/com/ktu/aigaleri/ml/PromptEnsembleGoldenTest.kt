package com.ktu.aigaleri.ml

import com.ktu.aigaleri.domain.EmbeddingSpec
import com.ktu.aigaleri.domain.TextEncoder
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-033: Kotlin ensemble matematiği, Python referansıyla (`tools/query_ensemble.py --golden`, ORİJİNAL model, ORT 1.30.0)
 * uyumlu mu. JVM ONNX çalıştıramaz: altın dosyadaki ÇIPLAK varyant vektörleri sahte kodlayıcı olarak verilir; Kotlin'in
 * ürettiği varyant METİNLERİ dosyadakilerle birebir aynı olmalı (bilinmeyen metin başarısızlıktır) ve ensemble vektörü
 * dosyadaki `expect` satırıyla eşleşmelidir. Gerçek model üstünde vektör doğruluğu cihaz testinde ayrıca doğrulanmaz
 * (çıplak kodlayıcı altınları GoldenTextTest/GoldenTextVectorsDeviceTest).
 */
class PromptEnsembleGoldenTest {
    private class Entry(val query: String, val variants: LinkedHashMap<String, FloatArray>, var expect: FloatArray?)

    private val entries: List<Entry> by lazy {
        val raw = PromptEnsembleGoldenTest::class.java.getResourceAsStream("/ml/query_ensemble_golden.tsv")!!
            .use { it.readBytes().toString(Charsets.UTF_8) }
        val out = ArrayList<Entry>()
        for (line in raw.split('\n').filter { it.isNotEmpty() }) {
            val p = line.split('\t')
            require(p.size == 4) { "query_ensemble_golden.tsv: 4 sütun bekleniyor" }
            val q = GoldenText.unescape(p[1])
            val vec = p[3].split(' ').map { it.toFloat() }.toFloatArray()
            require(vec.size == 512)
            when (p[0]) {
                "variant" -> {
                    val e = out.lastOrNull()?.takeIf { it.query == q && it.expect == null } ?: Entry(q, LinkedHashMap(), null).also { out.add(it) }
                    e.variants[GoldenText.unescape(p[2])] = vec
                }
                "expect" -> out.last().also { require(it.query == q && it.expect == null) }.expect = vec
                else -> error("bilinmeyen tür ${p[0]}")
            }
        }
        out
    }

    private open class LookupBase(val table: Map<String, FloatArray>) : TextEncoder {
        override val embeddingSpec = EmbeddingSpec(512, "golden")
        val calls = mutableListOf<String>()
        override suspend fun encode(query: String): FloatArray {
            val n = QueryPreprocessor.normalize(query)
            calls.add(n)
            return table[n] ?: throw AssertionError("altın dosyada olmayan varyant metni istendi: '$n'")
        }
    }

    /** Üretimdeki [OnnxTextEncoder] gibi toplu yolu da sunan kodlayıcı: varyantlar TEK encodeAll çağrısında gelir. */
    private class BatchLookupBase(table: Map<String, FloatArray>) : LookupBase(table), BatchTextEncoder {
        var batchCalls = 0
        override suspend fun encodeAll(queries: List<String>): List<FloatArray> {
            batchCalls++
            return queries.map { encode(it) }
        }
    }

    private fun Entry.isBare() = variants.size == 1 && variants.keys.single() == QueryPreprocessor.normalize(query)

    @Test
    fun goldenFile_hasExpectedShape() {
        assertTrue("en az 15 sorgu", entries.size >= 15)
        assertTrue("iki şablonlu sorgular", entries.count { it.variants.size == 2 } >= 12)
        assertTrue("tek varyantlı (ön/son ek kuralı) sorgular", entries.count { it.variants.size == 1 && !it.isBare() } >= 5)
        assertTrue("çıplak (şablonsuz) sorgular", entries.count { it.isBare() } >= 3)
        for (e in entries) {
            val x = e.expect!!
            assertEquals(1.0, sqrt(x.sumOf { it.toDouble() * it }), 1e-6)
        }
    }

    @Test
    fun kotlinEnsembleMatchesPythonReference_andRequestsExactlyTheReferenceVariants() = runTest {
        for (e in entries) {
            val base = LookupBase(e.variants)
            val got = PromptEnsembleTextEncoder(base).encode(e.query)
            assertEquals("varyant metinleri/sırası: '${e.query}'", e.variants.keys.toList(), base.calls)
            // Toplu yol (üretimdeki OnnxTextEncoder gibi): aynı metinler, tek encodeAll, aynı sonuç.
            val batch = BatchLookupBase(e.variants)
            val gotBatch = PromptEnsembleTextEncoder(batch).encode(e.query)
            assertEquals(e.variants.keys.toList(), batch.calls)
            assertEquals(if (e.isBare()) 0 else 1, batch.batchCalls)
            for (i in got.indices) assertEquals(got[i], gotBatch[i], 0f)
            val expect = e.expect!!
            var maxDiff = 0.0
            var dot = 0.0
            for (i in got.indices) {
                maxDiff = maxOf(maxDiff, abs((got[i] - expect[i]).toDouble()))
                dot += got[i].toDouble() * expect[i]
            }
            assertTrue("en büyük bileşen farkı $maxDiff: '${e.query}'", maxDiff < 2e-6)
            assertTrue("kosinüs $dot: '${e.query}'", dot > 1 - 1e-6)
        }
    }

    @Test
    fun bareQueries_returnExactlyTheBareVector() = runTest {
        for (e in entries.filter { it.isBare() }) {
            val got = PromptEnsembleTextEncoder(LookupBase(e.variants)).encode(e.query)
            val bare = e.variants.values.single()
            for (i in got.indices) assertEquals(bare[i], got[i], 0f)
        }
    }
}
