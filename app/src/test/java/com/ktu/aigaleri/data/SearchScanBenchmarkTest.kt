package com.ktu.aigaleri.data

import com.ktu.aigaleri.domain.EmbeddingSpec
import java.util.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * T-025 JVM A/B mikro-benchmark: A = eski tarama (JOIN'li sayfa + uri her satırda + bayt bayt çözme), B = yeni tarama
 * (JOIN'siz sayfa + yalnızca adaylar için toplu uri + toplu çözme). Aynı sahte indeks, aynı sorgu, aynı limit (50).
 *
 * VARSAYILAN KOŞU (testDebugUnitTest): yalnızca küçük boyut (10 bin) ve yalnızca doğruluk (A == B); ağır boyutlar
 * (50K/100K, ~230 MB indeks, ~9 sn) yalnızca `RUN_SEARCH_BENCH=1` ortam değişkeniyle koşar:
 * `RUN_SEARCH_BENCH=1 ./gradlew testDebugUnitTest --tests '*SearchScanBenchmarkTest'` (sonuç stdout'ta,
 * app/build/test-results/testDebugUnitTest altındaki ...SearchScanBenchmarkTest.xml içinde).
 *
 * Yöntem: kayıt üretimi zamanlamadan ÇIKARILMIŞTIR (indeks önceden bellekte, boyut 512, tam 50K/100K kayıt);
 * [WARMUP] ısınma + [REPS] ölçülen tekrar, A ve B ardışık dönüşümlü (ısıl/GC kaymasını iki tarafa da dağıtır);
 * p50/p95 stdout'a yazılır. Mutlak süre ASSERT EDİLMEZ (CI kırılganlığı); yalnızca A ve B sonucunun aynı olduğu
 * doğrulanır. Log'a istem/yol yazılmaz (sentetik vektörler).
 *
 * UYARI: ölçüm makinesi TELEFON DEĞİLDİR; yalnızca A/B ORANI anlamlıdır. Sahte DAO satır başına `String`/`ByteArray`
 * kopyalamaz (Room/CursorWindow yapar), bu yüzden gerçek cihazda kazanç buradan farklı olur (cihaz ölçümü T-020).
 * Sonuç ve yorum: mobile/docs/search-scan-benchmark.md.
 */
class SearchScanBenchmarkTest {
    private val dim = 512
    private val limit = 50
    private val pageSize = RoomSearchRepository.DEFAULT_PAGE_SIZE

    private fun percentile(sorted: LongArray, p: Double): Long {
        val rank = Math.ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun bench(n: Int, warmup: Int = WARMUP, reps: Int = REPS) {
        val idx = SyntheticIndex.build(n = n, dim = dim, seed = 20261013L + n, maxGap = 3)
        val q = randomUnit(Random(99), dim)
        val src = idx.LegacySource()
        val dao = idx.NewDao()
        val repo = RoomSearchRepository(FixedTextEncoder(q, EmbeddingSpec(dim, TEST_MODEL)), dao, pageSize, null, Dispatchers.Unconfined)

        fun runA() = runBlocking { SyntheticIndex.legacySearch(src, TEST_MODEL, q, limit, pageSize, null) }
        fun runB() = runBlocking { repo.search("x", limit) }

        repeat(warmup) { runA(); runB() }
        val a = LongArray(reps)
        val b = LongArray(reps)
        var resultA = runA()
        var resultB = runB()
        for (i in 0 until reps) {
            var t = System.nanoTime(); resultA = runA(); a[i] = System.nanoTime() - t
            t = System.nanoTime(); resultB = runB(); b[i] = System.nanoTime() - t
        }
        assertEquals(resultA, resultB) // aynı sonuç (zamanlamadan bağımsız doğruluk)
        a.sort(); b.sort()
        val a50 = percentile(a, 0.5) / 1e6
        val a95 = percentile(a, 0.95) / 1e6
        val b50 = percentile(b, 0.5) / 1e6
        val b95 = percentile(b, 0.95) / 1e6
        println(
            "scan A/B n=$n dim=$dim limit=$limit page=$pageSize reps=$reps (JVM, telefon degil): " +
                "A(eski) p50=%.1f ms p95=%.1f ms | B(yeni) p50=%.1f ms p95=%.1f ms | B/A p50=%.2f p95=%.2f"
                    .format(java.util.Locale.ROOT, a50, a95, b50, b95, b50 / a50, b95 / a95),
        )
    }

    @Test fun tenThousand_resultsEqual_defaultRun() = bench(10_000, warmup = 1, reps = 2)

    @Test fun fiftyThousand_AvsB() {
        assumeTrue("RUN_SEARCH_BENCH=1 gerekir", System.getenv("RUN_SEARCH_BENCH") == "1")
        bench(50_000)
    }

    @Test fun hundredThousand_AvsB() {
        assumeTrue("RUN_SEARCH_BENCH=1 gerekir", System.getenv("RUN_SEARCH_BENCH") == "1")
        bench(100_000)
    }

    private companion object {
        const val WARMUP = 5
        const val REPS = 20
    }
}
