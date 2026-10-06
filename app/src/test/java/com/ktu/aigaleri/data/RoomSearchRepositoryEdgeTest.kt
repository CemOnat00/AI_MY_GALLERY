package com.ktu.aigaleri.data

import com.ktu.aigaleri.domain.SearchResult
import com.ktu.aigaleri.domain.TextEncoder
import java.util.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** T-008 QA: RoomSearchRepository sınır durumları (JVM, sahte DAO ve kodlayıcı). */
class RoomSearchRepositoryEdgeTest {
    private fun repo(
        dao: PhotoEmbeddingDao,
        enc: TextEncoder = FixedTextEncoder(e0()),
        pageSize: Int = RoomSearchRepository.DEFAULT_PAGE_SIZE,
    ) = RoomSearchRepository(enc, dao, pageSize, null, Dispatchers.Unconfined)

    private fun ids(r: List<SearchResult>) = r.map { it.photoId }

    private class RecordingEncoder(val vector: FloatArray) : TextEncoder {
        val seen = mutableListOf<String>()
        override val embeddingSpec = TEST_SPEC
        override suspend fun encode(query: String): FloatArray {
            seen += query
            return vector
        }
    }

    @Test
    fun turkishAndOddQueries_reachEncoderVerbatim() = runBlocking {
        val enc = RecordingEncoder(e0())
        val r = repo(FakeEmbeddingDao(listOf(row(1, e0()))), enc)
        val queries = listOf(
            "İstanbul", "ığdır", "I ı İ i", "ÇĞİÖŞÜ çğıöşü", "  boşluklu\tsorgu\n", "x".repeat(10_000),
            "İ", "a\u0000b", "😀",
        )
        for (q in queries) r.search(q)
        assertEquals(queries, enc.seen)
    }

    @Test
    fun emptyAndHugeQueryStrings_areNotJudgedByRepository() = runBlocking {
        // Doğrulama kodlayıcının işidir (InvalidQueryException); depo metni değiştirmez/kesmez.
        val enc = RecordingEncoder(e0())
        val r = repo(FakeEmbeddingDao(listOf(row(1, e0()))), enc)
        assertEquals(listOf(1L), ids(r.search("")))
        assertEquals(listOf(1L), ids(r.search("ş".repeat(100_000))))
        assertEquals(2, enc.seen.size)
        assertEquals(0, enc.seen[0].length)
    }

    @Test
    fun limitOne_andIntMax_onEmptyAndNonEmptyIndex() = runBlocking {
        assertEquals(emptyList<SearchResult>(), repo(FakeEmbeddingDao()).search("x", 1))
        assertEquals(emptyList<SearchResult>(), repo(FakeEmbeddingDao()).search("x", Int.MAX_VALUE))
        val dao = FakeEmbeddingDao((1L..9L).map { row(it, unitWithFirst(it / 10f)) })
        assertEquals(listOf(9L), ids(repo(dao, pageSize = 2).search("x", 1)))
        assertEquals((1L..9L).toList().reversed(), ids(repo(dao, pageSize = 2).search("x", Int.MAX_VALUE)))
    }

    @Test
    fun pageSizeOne_scansEveryRow_withOneTrailingEmptyPage() = runBlocking {
        val dao = FakeEmbeddingDao((1L..5L).map { row(it, unitWithFirst(0.1f * it)) })
        val got = repo(dao, pageSize = 1).search("x", 3)
        assertEquals(listOf(5L, 4L, 3L), ids(got))
        assertEquals(6, dao.pageCalls.size) // 5 dolu sayfa + 1 boş
        assertEquals(listOf(Long.MIN_VALUE, 1L, 2L, 3L, 4L, 5L), dao.pageCalls.map { it.second })
    }

    @Test
    fun exactFullPageBoundary_endsWithEmptyPage_noDuplicates() = runBlocking {
        for (n in listOf(4, 8, 12)) {
            val dao = FakeEmbeddingDao((1L..n.toLong()).map { row(it, e0()) })
            val got = repo(dao, pageSize = 4).search("x", Int.MAX_VALUE)
            assertEquals((1L..n.toLong()).toList(), ids(got))
            assertEquals(n / 4 + 1, dao.pageCalls.size)
        }
    }

    @Test
    fun allRowsOldModelVersion_isEmptyList_notError_andScansOnlyCurrentModel() = runBlocking {
        val dao = FakeEmbeddingDao((1L..50L).map { row(it, e0(), model = "old-$it") })
        assertEquals(emptyList<SearchResult>(), repo(dao, pageSize = 7).search("x"))
        assertTrue(dao.pageCalls.all { it.first == TEST_MODEL })
    }

    @Test
    fun mixedModelVersions_interleaved_onlyCurrentRanked() = runBlocking {
        val rows = (1L..60L).map { row(it, unitWithFirst(0.5f), model = if (it % 3 == 0L) TEST_MODEL else "old") }
        val got = repo(FakeEmbeddingDao(rows), pageSize = 4).search("x", 100)
        assertEquals((1L..60L).filter { it % 3 == 0L }, ids(got))
    }

    @Test
    fun emptyUri_exactlyEmptyString_isSkipped() = runBlocking {
        val rows = listOf(row(1, e0(), uri = ""), row(2, unitWithFirst(0.3f)))
        assertEquals(listOf(2L), ids(repo(FakeEmbeddingDao(rows)).search("x")))
        try {
            repo(FakeEmbeddingDao(listOf(row(1, e0(), uri = "")))).search("x")
            fail()
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun vectorLengthMismatch_partial_wholePage_andLongerThanExpected() = runBlocking {
        fun bad(id: Long, bytes: Int) = Row(IndexedVector(id, "content://media/$id", null, ByteArray(bytes)), TEST_MODEL)
        // ilk sayfanın tamamı bozuk, sonraki sayfada sağlam kayıtlar var: atlanır, hata yok
        val rows = (1L..4L).map { bad(it, 3) } + (5L..8L).map { row(it, unitWithFirst(0.1f * (it - 4))) }
        assertEquals(listOf(8L, 7L, 6L, 5L), ids(repo(FakeEmbeddingDao(rows), pageSize = 4).search("x")))
        // beklenenden UZUN BLOB (boyut büyütüldü) de atlanır; tamamı ise ISE
        val longer = (1L..5L).map { bad(it, TEST_DIM * 4 + 4) }
        try {
            repo(FakeEmbeddingDao(longer), pageSize = 2).search("x")
            fail()
        } catch (_: IllegalStateException) {
        }
        // boş BLOB
        try {
            repo(FakeEmbeddingDao(listOf(bad(1, 0)))).search("x")
            fail()
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun infiniteVectorEntries_areSkipped() = runBlocking {
        val inf = row(1, FloatArray(TEST_DIM) { Float.POSITIVE_INFINITY })
        val negInf = row(2, FloatArray(TEST_DIM) { Float.NEGATIVE_INFINITY })
        val ok = row(3, unitWithFirst(0.2f))
        assertEquals(listOf(3L), ids(repo(FakeEmbeddingDao(listOf(inf, negInf, ok))).search("x")))
    }

    @Test
    fun cancellationInsideFirstPage_throwsCancellation_andStopsScan() = runBlocking {
        val dao = FakeEmbeddingDao((1L..40L).map { row(it, e0()) })
        dao.onPage = { n -> if (n == 1) currentCoroutineContext().job.cancel() }
        val d = async(Dispatchers.Default) { repo(dao, pageSize = 5).search("x") }
        try {
            d.await()
            fail()
        } catch (_: CancellationException) {
        }
        assertEquals(1, dao.pageCalls.size)
    }

    @Test
    fun largeGallery_limit1_matchesBruteForce() = runBlocking {
        val rnd = Random(77)
        val rows = (1L..3_000L).map { row(it, randomUnit(rnd, TEST_DIM)) }
        val q = randomUnit(rnd, TEST_DIM)
        val got = repo(FakeEmbeddingDao(rows), FixedTextEncoder(q), pageSize = 500).search("x", 1)
        assertEquals(bruteForce(rows, q, 1).map { it.first }, ids(got))
    }

    @Test
    fun scoresStayWithinCosineRange_forUnitVectors() = runBlocking {
        val rnd = Random(5)
        val rows = (1L..500L).map { row(it, randomUnit(rnd, TEST_DIM)) }
        val got = repo(FakeEmbeddingDao(rows), FixedTextEncoder(randomUnit(rnd, TEST_DIM))).search("x", 500)
        assertTrue(got.all { it.score in -1.001f..1.001f })
    }

    @Test
    fun queryNormFarFromOne_isRejected_beforeIndexAccess() = runBlocking {
        val dao = FakeEmbeddingDao(listOf(row(1, e0())))
        for (scale in listOf(1e-6f, 0.25f, 3f, 1e6f)) {
            try {
                repo(dao, FixedTextEncoder(e0().also { it[0] = scale })).search("gizli")
                fail("scale=$scale")
            } catch (e: IllegalStateException) {
                assertTrue(!(e.message ?: "").contains("gizli"))
            }
        }
        assertTrue(dao.pageCalls.isEmpty())
    }

    @Test
    fun errorMessages_neverContainQueryText() = runBlocking {
        val secret = "çok-gizli-İstem-ığ"
        val badUnit = repo(FakeEmbeddingDao(listOf(row(1, e0()))), FixedTextEncoder(FloatArray(TEST_DIM)))
        val allCorrupt = repo(FakeEmbeddingDao(listOf(row(1, e0(), uri = " "))))
        for (r in listOf(badUnit, allCorrupt)) {
            try {
                r.search(secret)
                fail()
            } catch (e: IllegalStateException) {
                assertTrue(!(e.message ?: "").contains(secret))
                assertTrue(!(e.stackTraceToString()).contains("gizli"))
            }
        }
    }
}
