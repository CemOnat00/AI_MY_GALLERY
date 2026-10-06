package com.ktu.aigaleri.data

import com.ktu.aigaleri.domain.InvalidQueryException
import com.ktu.aigaleri.ml.QueryPreprocessor
import com.ktu.aigaleri.domain.EmbeddingSpec
import com.ktu.aigaleri.domain.SearchResult
import com.ktu.aigaleri.domain.TextEncoder
import java.util.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** T-008: sıralama, limit, model süzme, sayfalı tarama = kaba kuvvet, hata ve iptal davranışı. Model dosyası yok. */
class RoomSearchRepositoryTest {
    private fun repo(
        dao: PhotoEmbeddingDao,
        enc: TextEncoder = FixedTextEncoder(e0()),
        pageSize: Int = RoomSearchRepository.DEFAULT_PAGE_SIZE,
        minScore: Float? = null,
    ) = RoomSearchRepository(enc, dao, pageSize, minScore, Dispatchers.Unconfined)

    private fun ids(r: List<SearchResult>) = r.map { it.photoId }

    @Test
    fun ranking_isScoreDescending_thenPhotoIdAscending() = runBlocking {
        val dao = FakeEmbeddingDao(
            listOf(
                row(5, unitWithFirst(0.5f)),
                row(3, unitWithFirst(0.9f)),
                row(9, unitWithFirst(0.5f)),
                row(1, unitWithFirst(0.5f)),
                row(7, unitWithFirst(-0.2f)),
                row(2, unitWithFirst(0.9f)),
            ),
        )
        val result = repo(dao).search("kedi")
        assertEquals(listOf(2L, 3L, 1L, 5L, 9L, 7L), ids(result))
        assertEquals(listOf(0.9f, 0.9f, 0.5f, 0.5f, 0.5f, -0.2f), result.map { it.score })
    }

    @Test
    fun resultCarriesUriAndDateTakenFromPhoto() = runBlocking {
        val dao = FakeEmbeddingDao(
            listOf(
                row(1, e0(), uri = "content://media/external/images/media/1", date = 1234L),
                row(2, e0(), uri = "content://media/external/images/media/2", date = null),
            ),
        )
        val result = repo(dao).search("x")
        assertEquals(SearchResult(1, "content://media/external/images/media/1", 1f, 1234L), result[0])
        assertEquals(SearchResult(2, "content://media/external/images/media/2", 1f, null), result[1])
    }

    @Test
    fun limit_one_fifty_largerThanN() = runBlocking {
        val rows = (1L..120L).map { row(it, unitWithFirst(1f - it / 200f)) } // skor id arttıkça düşer
        val dao = FakeEmbeddingDao(rows)
        val r = repo(dao, pageSize = 25)
        assertEquals(listOf(1L), ids(r.search("x", 1)))
        assertEquals((1L..50L).toList(), ids(r.search("x", 50)))
        assertEquals(120, r.search("x", 1000).size)
        assertEquals(120, r.search("x", Int.MAX_VALUE).size)
        assertEquals((1L..50L).toList(), ids(r.search("x"))) // varsayılan limit 50
    }

    @Test
    fun nonPositiveLimit_throwsIllegalArgument_beforeEncoding() = runBlocking {
        val enc = FixedTextEncoder(e0())
        val r = repo(FakeEmbeddingDao(listOf(row(1, e0()))), enc)
        for (limit in listOf(0, -1, Int.MIN_VALUE)) {
            try {
                r.search("x", limit)
                fail("limit=$limit için IllegalArgumentException beklenir")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertEquals(0, enc.calls)
    }

    @Test
    fun oldModelVersionRecords_neverEnterSearch() = runBlocking {
        val dao = FakeEmbeddingDao(
            listOf(
                row(1, unitWithFirst(0.1f)),
                row(2, e0(), model = "old-model"), // en yüksek skor ama eski sürüm
                row(3, unitWithFirst(0.2f)),
                row(4, e0(), model = "$TEST_MODEL-x"),
            ),
        )
        val result = repo(dao).search("x")
        assertEquals(listOf(3L, 1L), ids(result))
        assertTrue(dao.pageCalls.all { it.first == TEST_MODEL })
    }

    @Test
    fun usesEncoderSpecModelVersion() = runBlocking {
        val spec = EmbeddingSpec(TEST_DIM, "other-model")
        val dao = FakeEmbeddingDao(listOf(row(1, e0(), model = "other-model"), row(2, e0())))
        val result = repo(dao, FixedTextEncoder(e0(), spec)).search("x")
        assertEquals(listOf(1L), ids(result))
    }

    @Test
    fun emptyIndex_returnsEmptyList_notError() = runBlocking {
        assertEquals(emptyList<SearchResult>(), repo(FakeEmbeddingDao()).search("x"))
        // yalnızca eski sürüm kayıtları da boş sonuç demektir
        assertEquals(emptyList<SearchResult>(), repo(FakeEmbeddingDao(listOf(row(1, e0(), model = "old")))).search("x"))
    }

    @Test
    fun pagedScan_equalsBruteForce_manyPages_randomSeededData() = runBlocking {
        val rnd = Random(20261006L)
        val rows = (1..2_003).map { i ->
            // kimlikler seyrek ve artmayan sırada üretilir; DAO sıralar
            val id = i * 7L + rnd.nextInt(5)
            row(id, randomUnit(rnd, TEST_DIM), model = if (i % 10 == 0) "old-model" else TEST_MODEL)
        }.shuffled(Random(1))
        val q = randomUnit(rnd, TEST_DIM)
        for (pageSize in listOf(1, 7, 64, 256, 499, 500)) {
            for (limit in listOf(1, 10, 50, 333, 5000)) {
                val dao = FakeEmbeddingDao(rows)
                val got = repo(dao, FixedTextEncoder(q), pageSize).search("x", limit)
                val want = bruteForce(rows, q, limit)
                assertEquals("pageSize=$pageSize limit=$limit", want.map { it.first }, ids(got))
                assertEquals(want.map { it.second }, got.map { it.score })
                assertTrue(dao.pageCalls.all { it.third == pageSize })
            }
        }
    }

    @Test
    fun pagedScan_pageCountAndKeysetProgress() = runBlocking {
        val rows = (1L..10L).map { row(it * 3, e0()) }
        val dao = FakeEmbeddingDao(rows)
        repo(dao, pageSize = 4).search("x")
        // 10 satır, sayfa 4: sayfalar 4+4+2; son sayfa kısa olduğu için ek boş sorgu yok
        assertEquals(listOf(Long.MIN_VALUE, 12L, 24L), dao.pageCalls.map { it.second })
        // tam bölünen durumda son boş sayfa ile biter
        val dao2 = FakeEmbeddingDao((1L..8L).map { row(it, e0()) })
        repo(dao2, pageSize = 4).search("x")
        assertEquals(listOf(Long.MIN_VALUE, 4L, 8L), dao2.pageCalls.map { it.second })
    }

    @Test
    fun equalScoresAcrossPageBoundaries_orderedByPhotoId() = runBlocking {
        // 40 satırın hepsi aynı skor; sayfa sınırları 5,10,... id'lerinde. Beklenen: en küçük id'ler.
        val rows = (1L..40L).map { row(it, unitWithFirst(0.5f)) }.shuffled(Random(3))
        for (pageSize in listOf(1, 3, 5, 10, 39, 40, 41)) {
            val got = repo(FakeEmbeddingDao(rows), pageSize = pageSize).search("x", 12)
            assertEquals("pageSize=$pageSize", (1L..12L).toList(), ids(got))
        }
        // sınırda eşit, öncesinde farklı skorlar: üst sıralar skor, sonra eşit grup id sırasıyla
        val mixed = (1L..30L).map { row(it, unitWithFirst(if (it % 5 == 0L) 0.8f else 0.4f)) }
        val got = repo(FakeEmbeddingDao(mixed), pageSize = 5).search("x", 9)
        assertEquals(listOf(5L, 10L, 15L, 20L, 25L, 30L, 1L, 2L, 3L), ids(got))
    }

    @Test
    fun invalidQueryException_passesThroughUnchanged_andNoIndexAccess() = runBlocking {
        val ex = InvalidQueryException(InvalidQueryException.Reason.EMPTY)
        val dao = FakeEmbeddingDao(listOf(row(1, e0())))
        try {
            repo(dao, FixedTextEncoder(e0(), failure = ex)).search("   ")
            fail()
        } catch (e: InvalidQueryException) {
            assertSame(ex, e)
        }
        assertTrue(dao.pageCalls.isEmpty())
    }

    @Test
    fun realQueryPreprocessing_rejectsBlankBeforeIndexAccess() = runBlocking {
        val enc = object : TextEncoder {
            override val embeddingSpec = TEST_SPEC
            override suspend fun encode(query: String): FloatArray {
                QueryPreprocessor.normalize(query)
                return e0()
            }
        }
        val dao = FakeEmbeddingDao(listOf(row(1, e0())))
        val r = repo(dao, enc)
        for ((q, reason) in listOf(
            "  \t\n" to InvalidQueryException.Reason.EMPTY,
            "a".repeat(QueryPreprocessor.MAX_QUERY_LENGTH + 1) to InvalidQueryException.Reason.TOO_LONG,
        )) {
            try {
                r.search(q)
                fail()
            } catch (e: InvalidQueryException) {
                assertEquals(reason, e.reason)
            }
        }
        assertTrue(dao.pageCalls.isEmpty())
        assertEquals(listOf(1L), ids(r.search("kedi")))
    }

    @Test
    fun queryVectorDimensionMismatch_isIllegalState_withoutQueryInMessage() = runBlocking {
        val dao = FakeEmbeddingDao(listOf(row(1, e0())))
        for (bad in listOf(FloatArray(TEST_DIM - 1), FloatArray(TEST_DIM + 1), FloatArray(0))) {
            try {
                repo(dao, FixedTextEncoder(bad)).search("gizli istem metni")
                fail()
            } catch (e: IllegalStateException) {
                assertTrue(!e.message!!.contains("gizli"))
            }
        }
        assertTrue(dao.pageCalls.isEmpty())
    }

    @Test
    fun nonFiniteQueryVector_isIllegalState() = runBlocking {
        val dao = FakeEmbeddingDao(listOf(row(1, e0())))
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY)) {
            val v = e0().also { it[3] = bad }
            try {
                repo(dao, FixedTextEncoder(v)).search("x")
                fail()
            } catch (_: IllegalStateException) {
            }
        }
    }

    @Test
    fun corruptIndexRecords_areSkipped_notFatal() = runBlocking {
        val good = row(2, unitWithFirst(0.3f))
        val wrongLength = Row(IndexedVector(1, "content://media/1", null, ByteArray(TEST_DIM * 4 - 4)), TEST_MODEL)
        val oddLength = Row(IndexedVector(3, "content://media/3", null, ByteArray(5)), TEST_MODEL)
        val nanVec = row(4, FloatArray(TEST_DIM) { Float.NaN })
        val blankUri = row(5, e0(), uri = "  ")
        val dao = FakeEmbeddingDao(listOf(wrongLength, good, oddLength, nanVec, blankUri))
        assertEquals(listOf(2L), ids(repo(dao).search("x")))
    }

    @Test
    fun minScore_whenSet_filtersBelowThreshold_defaultKeepsAll() = runBlocking {
        val rows = listOf(row(1, unitWithFirst(0.9f)), row(2, unitWithFirst(0.2f)), row(3, unitWithFirst(0.5f)))
        assertEquals(listOf(1L, 3L, 2L), ids(repo(FakeEmbeddingDao(rows)).search("x")))
        assertEquals(listOf(1L, 3L), ids(repo(FakeEmbeddingDao(rows), minScore = 0.5f).search("x"))) // eşik dahil
        assertEquals(emptyList<SearchResult>(), repo(FakeEmbeddingDao(rows), minScore = 0.95f).search("x"))
    }

    @Test
    fun constructorValidation() {
        val enc = FixedTextEncoder(e0())
        val dao = FakeEmbeddingDao()
        for (bad in listOf(0, -1, RoomSearchRepository.MAX_PAGE_SIZE + 1)) {
            try {
                RoomSearchRepository(enc, dao, pageSize = bad)
                fail()
            } catch (_: IllegalArgumentException) {
            }
        }
        try {
            RoomSearchRepository(enc, dao, minScore = Float.NaN)
            fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun nonAdvancingDao_failsInsteadOfLoopingForever() = runBlocking {
        val stuck = object : FakeEmbeddingDao() {
            override suspend fun getPageForModel(modelVersion: String, afterId: Long, limit: Int) =
                listOf(row(10, e0()).vec, row(11, e0()).vec) // afterId'yi yok sayar
        }
        try {
            repo(stuck, pageSize = 2).search("x")
            fail()
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun cancellationDuringEncode_propagatesCancellationException() = runBlocking {
        val enc = object : TextEncoder {
            override val embeddingSpec = TEST_SPEC
            override suspend fun encode(query: String): FloatArray = awaitCancellation()
        }
        val dao = FakeEmbeddingDao(listOf(row(1, e0())))
        val d = async(Dispatchers.Default) { repo(dao, enc).search("x") }
        kotlinx.coroutines.delay(50)
        d.cancel()
        try {
            d.await()
            fail()
        } catch (_: CancellationException) {
        }
        assertTrue(dao.pageCalls.isEmpty())
    }

    @Test
    fun cancellationBetweenPages_stopsScan() = runBlocking {
        val dao = FakeEmbeddingDao((1L..100L).map { row(it, e0()) })
        dao.onPage = { n -> if (n == 2) currentCoroutineContext().job.cancel() }
        val d = async(Dispatchers.Default) { repo(dao, pageSize = 10).search("x") }
        try {
            d.await()
            fail()
        } catch (_: CancellationException) {
        }
        assertEquals(2, dao.pageCalls.size) // 3. sayfa istenmedi
    }

    @Test
    fun alreadyCancelledCaller_throwsCancellation_beforeAnyPage() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val dao = FakeEmbeddingDao(listOf(row(1, e0())))
        val d = async(Dispatchers.Default, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            gate.await()
            repo(dao).search("x")
        }
        d.cancel()
        try {
            d.await()
            fail()
        } catch (_: CancellationException) {
        }
        assertTrue(dao.pageCalls.isEmpty())
    }

    @Test
    fun repositoryDoesNotMutateEncoderVector() = runBlocking {
        val q = unitWithFirst(0.6f)
        val copy = q.copyOf()
        repo(FakeEmbeddingDao(listOf(row(1, e0()))), FixedTextEncoder(q)).search("x")
        assertTrue(q.contentEquals(copy))
    }
}
