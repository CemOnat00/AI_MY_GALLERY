package com.ktu.aigaleri.data

import com.ktu.aigaleri.domain.EmbeddingSpec
import com.ktu.aigaleri.domain.SearchResult
import java.util.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-025 eşdeğerlik: eski (JOIN'li sayfa + bayt bayt çözme + tek aşamalı tarama) davranışın test içindeki birebir
 * kopyası ([SyntheticIndex.legacySearch]) ile yeni uygulama ([RoomSearchRepository], JOIN'siz sayfa + toplu uri +
 * toplu çözme) AYNI sentetik veride aynı kimlik/skor/uri/dateTaken/sıra/sayıyı (ve aynı hata türünü) vermelidir.
 * Tüm rastgelelik sabit tohumludur. Ölçek: 100 bin kayıt (vektör boyutu 32: eşdeğerlik boyuttan bağımsızdır, bellek ve
 * süre için; boyut 512 kıyası [SearchScanBenchmarkTest]'te).
 */
class SearchScanEquivalenceTest {
    private val dim = 32

    private fun query(seed: Long) = randomUnit(Random(seed), dim)

    private sealed interface Outcome {
        data class Ok(val results: List<SearchResult>) : Outcome
        data class Failed(val type: String) : Outcome
    }

    private fun legacy(idx: SyntheticIndex, q: FloatArray, limit: Int, pageSize: Int, minScore: Float?): Outcome = runBlocking {
        try {
            Outcome.Ok(SyntheticIndex.legacySearch(idx.LegacySource(), TEST_MODEL, q, limit, pageSize, minScore))
        } catch (e: IllegalStateException) {
            Outcome.Failed("IllegalStateException")
        }
    }

    private fun current(idx: SyntheticIndex, q: FloatArray, limit: Int, pageSize: Int, minScore: Float?, dao: PhotoEmbeddingDao = idx.NewDao()): Outcome = runBlocking {
        val repo = RoomSearchRepository(FixedTextEncoder(q, EmbeddingSpec(dim, TEST_MODEL)), dao, pageSize, minScore, Dispatchers.Unconfined)
        try {
            Outcome.Ok(repo.search("x", limit))
        } catch (e: IllegalStateException) {
            Outcome.Failed("IllegalStateException")
        }
    }

    private fun assertEquivalent(idx: SyntheticIndex, q: FloatArray, limit: Int, pageSize: Int, minScore: Float? = null, label: String = ""): Outcome {
        val a = legacy(idx, q, limit, pageSize, minScore)
        val b = current(idx, q, limit, pageSize, minScore)
        assertEquals("$label limit=$limit pageSize=$pageSize minScore=$minScore", a, b)
        return b
    }

    @Test
    fun hundredThousandMixedRecords_sameIdsScoresOrderAndCount() {
        val idx = SyntheticIndex.build(
            n = 100_000, dim = dim, seed = 20261013L,
            otherModelFraction = 0.10, corruptFraction = 0.01, blankUriFraction = 0.005,
            orphanFraction = 0.005, tieFraction = 0.30, maxGap = 6,
        )
        val q = query(7)
        val validCount = (0 until idx.size).count { idx.models[it] == TEST_MODEL }
        assertTrue(validCount in 80_000..95_000)
        for (limit in listOf(1, 2, 50, 51, 1000, 5000)) {
            for (pageSize in listOf(7, 256, 500)) {
                val out = assertEquivalent(idx, q, limit, pageSize, label = "mixed") as Outcome.Ok
                assertEquals(limit, out.results.size) // fazladan aday: limit'ten az kalmaz
                assertTrue(out.results.all { it.uri.isNotBlank() })
            }
        }
        // minScore ile (eşik altı kayıtlar sonuca girmez, aynı sayıda)
        for (min in listOf(0.0f, 0.3f)) assertEquivalent(idx, q, 50, 256, min, "minScore")
    }

    @Test
    fun limitBoundaries_atAndBeyondRecordCount() {
        val idx = SyntheticIndex.build(
            n = 3000, dim = dim, seed = 11L, otherModelFraction = 0.2, corruptFraction = 0.02,
            blankUriFraction = 0.02, orphanFraction = 0.02, tieFraction = 0.4, maxGap = 3,
        )
        val q = query(8)
        // legacy'nin gördüğü iyi kayıt sayısı = tam sonuç uzunluğu
        val total = (assertEquivalent(idx, q, Int.MAX_VALUE, 256, label = "all") as Outcome.Ok).results.size
        assertTrue(total in 1 until 3000)
        for (limit in listOf(total - 1, total, total + 1, total + 31, total + 32, total + 33, total + 600, 100_000, Int.MAX_VALUE - 31, Int.MAX_VALUE - 1, Int.MAX_VALUE)) {
            val out = assertEquivalent(idx, q, limit, 64, label = "boundary") as Outcome.Ok
            assertEquals(minOf(limit.toLong(), total.toLong()).toInt(), out.results.size)
        }
    }

    @Test
    fun equalScores_orderedByPhotoIdAscending_likeLegacy() {
        // Tüm kayıtlar aynı vektör: skorların hepsi eşit; sıra yalnızca photoId artan.
        val idx = SyntheticIndex.build(n = 2000, dim = dim, seed = 5L, tieFraction = 1.0, poolSize = 1, maxGap = 4, orphanFraction = 0.05)
        val q = query(9)
        val out = assertEquivalent(idx, q, 100, 13, label = "ties") as Outcome.Ok
        assertEquals(out.results.map { it.photoId }.sorted(), out.results.map { it.photoId })
        assertEquals(1, out.results.map { it.score.toRawBits() }.toSet().size)
    }

    @Test
    fun topCandidatesWithBlankUriOrNoPhoto_triggerExtraCandidates_andStillFillLimit() {
        val idx = SyntheticIndex.build(n = 20_000, dim = dim, seed = 3L, maxGap = 3)
        val q = query(10)
        val qBlob = VectorCodec.encode(q)
        // En yüksek skorlu 300 kayıt: 150 boş uri + 150 yetim (aday payından CANDIDATE_SLACK=32'den çok fazla).
        val top = (0 until idx.size step 60).take(300)
        top.forEachIndexed { k, i ->
            idx.vectors[i] = qBlob
            if (k % 2 == 0) idx.uris[i] = if (k % 4 == 0) "" else " " else idx.hasPhoto[i] = false
        }
        val dao = idx.NewDao()
        val limit = 50
        val a = legacy(idx, q, limit, 256, null)
        val b = current(idx, q, limit, 256, null, dao)
        assertEquals(a, b)
        val res = (b as Outcome.Ok).results
        assertEquals(limit, res.size)
        assertTrue(res.none { r -> r.photoId in top.map { idx.ids[it] }.toSet() }) // üstteki 300 kayıt yok, ama sayı tam
        assertTrue("ikinci geçiş beklenirdi", dao.pageCalls.count { it == Long.MIN_VALUE } >= 2)
        assertTrue(dao.refCallSizes.all { it <= RoomSearchRepository.REF_CHUNK })
    }

    @Test
    fun commonCase_singleScan_andUriFetchedOnlyForCandidates_inChunksOfAtMost500() {
        val idx = SyntheticIndex.build(n = 30_000, dim = dim, seed = 4L, maxGap = 2)
        val q = query(11)
        val dao = idx.NewDao()
        val out = current(idx, q, 50, 256, null, dao) as Outcome.Ok
        assertEquals(legacy(idx, q, 50, 256, null), Outcome.Ok(out.results))
        assertEquals(1, dao.pageCalls.count { it == Long.MIN_VALUE }) // tek tarama
        // uri yalnızca adaylar için: tek toplu sorgu, tam limit + slack kimlik (100 bin kayıtta bile 82)
        assertEquals(listOf(50 + RoomSearchRepository.CANDIDATE_SLACK), dao.refCallSizes)
        // büyük limit: parçalama <= 500
        val big = idx.NewDao()
        val bigOut = current(idx, q, 1200, 256, null, big) as Outcome.Ok
        assertEquals(1200, bigOut.results.size)
        assertTrue(big.refCallSizes.size >= 3 && big.refCallSizes.all { it <= 500 })
        assertEquals(legacy(idx, q, 1200, 256, null), Outcome.Ok(bigOut.results))
    }

    @Test
    fun orphanEmbedding_isInvisible_likeInnerJoin_andDoesNotShrinkResult() {
        // Yetim embedding vektörü sorguyla tam örtüşür (en iyi skor); photo satırı yok -> sonuçta görünmemeli.
        val rows = listOf(
            row(1, unitWithFirst(0.2f)), row(2, e0()), row(3, unitWithFirst(0.6f)), row(4, unitWithFirst(0.4f)),
        )
        val dao = FakeEmbeddingDao(rows, orphans = setOf(2L))
        val repo = RoomSearchRepository(FixedTextEncoder(e0()), dao, dispatcher = Dispatchers.Unconfined)
        assertEquals(listOf(3L, 4L, 1L), runBlocking { repo.search("x", 10) }.map { it.photoId })
        assertEquals(listOf(3L, 4L), runBlocking { repo.search("x", 2) }.map { it.photoId }) // limit dolu
        // Yalnızca yetimler: eski INNER JOIN'de taranan 0 -> boş liste (hata DEĞİL).
        val onlyOrphans = FakeEmbeddingDao(listOf(row(1, e0()), row(2, e0())), orphans = setOf(1L, 2L))
        assertEquals(emptyList<SearchResult>(), runBlocking { RoomSearchRepository(FixedTextEncoder(e0()), onlyOrphans, dispatcher = Dispatchers.Unconfined).search("x") })
        // Yetim + bozuk (photo satırı olan) kayıt: tek görünür kayıt bozuk -> hata (eski davranış).
        val corruptVisible = Row(IndexedVector(5, "content://media/5", null, ByteArray(3)), TEST_MODEL)
        val mixed = FakeEmbeddingDao(listOf(row(1, e0()), corruptVisible), orphans = setOf(1L))
        try {
            runBlocking { RoomSearchRepository(FixedTextEncoder(e0()), mixed, dispatcher = Dispatchers.Unconfined).search("x") }
            throw AssertionError("IllegalStateException beklenirdi")
        } catch (_: IllegalStateException) {
        }
        // Yetim + bozuk (ikisi de photo'suz): görünür kayıt yok -> boş liste.
        val allInvisible = FakeEmbeddingDao(listOf(row(1, e0()), corruptVisible), orphans = setOf(1L, 5L))
        assertEquals(emptyList<SearchResult>(), runBlocking { RoomSearchRepository(FixedTextEncoder(e0()), allInvisible, dispatcher = Dispatchers.Unconfined).search("x") })
    }

    @Test
    fun failureParity_withLegacy_forAllSkippedScenarios() {
        val q = query(12)
        fun build(corrupt: Double = 0.0, blank: Double = 0.0, orphan: Double = 0.0, other: Double = 0.0, n: Int = 400) =
            SyntheticIndex.build(n = n, dim = dim, seed = 77L, corruptFraction = corrupt, blankUriFraction = blank, orphanFraction = orphan, otherModelFraction = other)
        val scenarios = mapOf(
            "hepsi bozuk" to build(corrupt = 1.0),
            "hepsi boş uri" to build(blank = 1.0),
            "hepsi yetim" to build(orphan = 1.0),
            "bozuk + yetim" to build(corrupt = 1.0, orphan = 0.5),
            "boş uri + yetim" to build(blank = 1.0, orphan = 0.7),
            "bozuk + boş uri" to build(corrupt = 0.5, blank = 1.0),
            "başka model" to build(other = 1.0),
            "boş indeks" to build(n = 0),
            "tek iyi kayıt" to build(corrupt = 0.0, n = 1),
        )
        val seen = mutableSetOf<String>()
        for ((name, idx) in scenarios) {
            for (min in listOf<Float?>(null, 0.9f, 2f)) {
                for (limit in listOf(1, 50, 1000)) {
                    val o = assertEquivalent(idx, q, limit, 64, min, name)
                    seen += name + ":" + (if (o is Outcome.Failed) "F" else "OK")
                }
            }
        }
        assertTrue("hepsi bozuk -> hata", "hepsi bozuk:F" in seen)
        assertTrue("hepsi boş uri -> hata", "hepsi boş uri:F" in seen)
        assertTrue("hepsi yetim -> boş", "hepsi yetim:OK" in seen && "hepsi yetim:F" !in seen)
        assertTrue("başka model -> boş", "başka model:OK" in seen && "başka model:F" !in seen)
    }

    @Test
    fun minScoreAboveEverything_isEmpty_notFailure_likeLegacy() {
        val idx = SyntheticIndex.build(n = 500, dim = dim, seed = 21L)
        val q = query(13)
        val o = assertEquivalent(idx, q, 50, 64, minScore = 2f, label = "minScore üstü")
        assertEquals(Outcome.Ok(emptyList<SearchResult>()), o)
    }

    // --- VectorCodec.decodeInto: bit-bit aynı ---

    @Test
    fun decodeInto_isBitIdentical_toByteByByteLegacy_includingNaNPayloads() {
        val rnd = Random(424242L)
        val special = intArrayOf(
            0x7FC00000, 0x7F800001, 0x7FA00000, 0x7FFFFFFF, 0xFFC00000.toInt(), 0xFF800001.toInt(), 0xFFFFFFFF.toInt(),
            0x7F800000, 0xFF800000.toInt(), 0x00000000, 0x80000000.toInt(), 0x00000001, 0x007FFFFF, 0x80000001.toInt(), 0x7F7FFFFF,
        )
        val dimsToTry = listOf(1, 2, 3, 7, 32, 512)
        for (d in dimsToTry) {
            repeat(300) { t ->
                val blob = ByteArray(d * 4)
                rnd.nextBytes(blob)
                if (t % 3 == 0) { // rastgele konumlara özel bit desenleri
                    for (k in 0 until d) if (rnd.nextInt(3) == 0) {
                        val bits = special[rnd.nextInt(special.size)]
                        for (b in 0 until 4) blob[k * 4 + b] = (bits ushr (8 * b)).toByte()
                    }
                }
                val a = FloatArray(d)
                val b = FloatArray(d)
                SyntheticIndex.legacyDecodeInto(blob, a)
                VectorCodec.decodeInto(blob, b)
                for (k in 0 until d) assertEquals("d=$d k=$k", a[k].toRawBits(), b[k].toRawBits())
            }
        }
        // tüm özel desenler tek tek
        for (bits in special) {
            val blob = ByteArray(4) { (bits ushr (8 * it)).toByte() }
            val a = FloatArray(1); val b = FloatArray(1)
            SyntheticIndex.legacyDecodeInto(blob, a); VectorCodec.decodeInto(blob, b)
            assertEquals(Integer.toHexString(bits), a[0].toRawBits(), b[0].toRawBits())
        }
    }

    @Test
    fun decodeInto_reusedBuffer_overwritesAllElements_andRejectsWrongLength() {
        val out = FloatArray(4) { 99f }
        VectorCodec.decodeInto(VectorCodec.encode(floatArrayOf(1f, -2f, 3.5f, 0f)), out)
        assertTrue(floatArrayOf(1f, -2f, 3.5f, 0f).contentEquals(out))
        for (n in listOf(0, 15, 17, 20)) {
            try {
                VectorCodec.decodeInto(ByteArray(n), out)
                throw AssertionError("n=$n")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertFalse(out.any { it == 99f })
    }
}
