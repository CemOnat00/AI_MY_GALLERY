package com.ktu.aigaleri.domain

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** T-005 QA: DomainContractTest'in kapsamadığı sınır durumları. */
class DomainContractEdgeCaseTest {
    private val spec = EmbeddingSpec(dimension = 4096, modelVersion = "fake-1")

    // --- IndexProgress değişmezleri ve fraction ---

    @Test
    fun indexProgress_rejectsNegativeValues() {
        assertThrows(IllegalArgumentException::class.java) { IndexProgress(IndexPhase.SCANNING, -1, 0) }
        assertThrows(IllegalArgumentException::class.java) { IndexProgress(IndexPhase.INDEXING, 3, -1) }
        assertThrows(IllegalArgumentException::class.java) { IndexProgress(IndexPhase.INDEXING, 3, 1, failed = -1) }
        assertThrows(IllegalArgumentException::class.java) { IndexProgress(IndexPhase.INDEXING, 0, 1) }
    }

    @Test
    fun indexProgress_boundaryValuesAreAccepted() {
        IndexProgress(IndexPhase.INDEXING, 3, 3, failed = 3) // hepsi başarısız olabilir
        IndexProgress(IndexPhase.COMPLETED, 0, 0)
        IndexProgress(IndexPhase.COMPLETED, 5, 5, failed = 2)
        IndexProgress(IndexPhase.INDEXING, Int.MAX_VALUE, Int.MAX_VALUE)
    }

    @Test
    fun indexProgress_fraction_zeroTotalDependsOnPhase() {
        assertEquals(0f, IndexProgress(IndexPhase.SCANNING, 0, 0).fraction, 0f)
        assertEquals(0f, IndexProgress(IndexPhase.INDEXING, 0, 0).fraction, 0f)
        assertEquals(1f, IndexProgress(IndexPhase.COMPLETED, 0, 0).fraction, 0f)
    }

    @Test
    fun indexProgress_fraction_staysWithinZeroAndOne_evenForHugeTotals() {
        assertEquals(0f, IndexProgress(IndexPhase.INDEXING, 10, 0).fraction, 0f)
        assertEquals(1f, IndexProgress(IndexPhase.INDEXING, 10, 10).fraction, 0f)
        val f = IndexProgress(IndexPhase.INDEXING, Int.MAX_VALUE, Int.MAX_VALUE - 1).fraction
        assertTrue("fraction=$f", f in 0f..1f)
        // failed ilerleme oranını etkilemez (başarısızlar da ele alınmış sayılır)
        assertEquals(1f, IndexProgress(IndexPhase.INDEXING, 4, 4, failed = 4).fraction, 0f)
    }

    @Test
    fun indexProgress_emittedSequenceIsMonotonicAndFractionNonDecreasing() = runTest {
        val index = FakeIndex()
        val photos = (1L..20L).map { FakePhoto(it, "etiket $it") }
        val events = FakePhotoIndexer(spec, photos, index, mutableSetOf(3L, 7L)).index().toList()
        assertEquals(1, events.count { it.phase == IndexPhase.SCANNING })
        assertEquals(1, events.count { it.phase == IndexPhase.COMPLETED })
        assertEquals(20, events.count { it.phase == IndexPhase.INDEXING })
        events.zipWithNext().forEach { (a, b) ->
            assertTrue(b.processed >= a.processed)
            assertTrue(b.failed >= a.failed)
            assertTrue(b.fraction >= a.fraction)
        }
        assertEquals(2, events.last().failed)
    }

    // --- SearchResult / EmbeddingSpec değişmezleri ---

    @Test
    fun searchResult_rejectsNaNAndBlankUri_acceptsNegativeAndNullDate() {
        assertThrows(IllegalArgumentException::class.java) { SearchResult(1, "content://x", Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { SearchResult(1, "   ", 0.1f) }
        assertEquals(-1f, SearchResult(1, "content://x", -1f).score, 0f)
        assertNull(SearchResult(1, "content://x", 0f).dateTaken)
        assertEquals(Long.MIN_VALUE, SearchResult(Long.MIN_VALUE, "content://x", 0f).photoId)
    }

    @Test
    fun embeddingSpec_rejectsNegativeDimensionAndEmptyVersion() {
        assertThrows(IllegalArgumentException::class.java) { EmbeddingSpec(-5, "v") }
        assertThrows(IllegalArgumentException::class.java) { EmbeddingSpec(8, "") }
        assertEquals(EmbeddingSpec(8, "v"), EmbeddingSpec(8, "v"))
    }

    // --- IndexException ---

    @Test
    fun indexException_messagesAreFixedAndCauseIsKept() {
        val cause = IllegalStateException("/storage/emulated/0/DCIM/gizli.jpg")
        val e = IndexException.Unexpected(cause)
        assertTrue(e.cause === cause)
        assertTrue(e.message!!.none { it == '/' }) // yol sızmaz
        assertNull(IndexException.PermissionMissing().cause)
        assertNotNull(IndexException.PermissionMissing().message)
    }

    // --- PhotoIndexer: soğuk akış, izin, boş/çok büyük galeri, bozuk fotoğraf, iptal ---

    @Test
    fun index_permissionMissing_emitsNothingAndWritesNothing_butOnlyWhenCollected() = runTest {
        val index = FakeIndex()
        val indexer = FakePhotoIndexer(spec, listOf(FakePhoto(1, "a")), index, permissionGranted = false)
        val flow = indexer.index() // soğuk: toplanmadan hata yok
        val seen = mutableListOf<IndexProgress>()
        val err = runCatching { flow.collect { seen.add(it) } }.exceptionOrNull()
        assertTrue(err is IndexException.PermissionMissing)
        assertTrue(seen.isEmpty())
        assertTrue(index.records.isEmpty())
        // aynı akış yeniden toplanınca aynı hata (izin hâlâ yok)
        assertTrue(runCatching { flow.toList() }.exceptionOrNull() is IndexException.PermissionMissing)
    }

    @Test
    fun index_coldFlowCanBeCollectedTwiceWithIdenticalEvents() = runTest {
        val indexer = FakePhotoIndexer(spec, listOf(FakePhoto(1, "a"), FakePhoto(2, "b")), FakeIndex())
        val flow = indexer.index(IndexMode.FULL)
        assertEquals(flow.toList(), flow.toList())
    }

    @Test
    fun index_allPhotosCorrupt_completesWithFailedEqualsTotal_andNothingIndexed() = runTest {
        val photos = (1L..5L).map { FakePhoto(it, "bozuk $it") }
        val index = FakeIndex()
        val indexer = FakePhotoIndexer(spec, photos, index, photos.map { it.id }.toMutableSet())
        val last = indexer.index().toList().last()
        assertEquals(IndexPhase.COMPLETED, last.phase)
        assertEquals(5, last.total)
        assertEquals(5, last.failed)
        assertEquals(1f, last.fraction, 0f)
        assertTrue(index.records.isEmpty())
        // indeks boş kaldığı için arama hata vermez, boş liste döner
        assertTrue(FakeSearchRepository(spec, index).search("bozuk").isEmpty())
    }

    @Test
    fun index_corruptPhotoDuringFull_keepsOldRecord_andIncrementalRetriesIt() = runTest {
        val photos = listOf(FakePhoto(1, "eski bir"), FakePhoto(2, "eski iki"))
        val index = FakeIndex()
        val indexer = FakePhotoIndexer(spec, photos, index)
        indexer.index().toList()
        indexer.failingIds.add(2L) // fotoğraf 2 FULL sırasında bozuldu/silindi
        val full = indexer.index(IndexMode.FULL).toList().last()
        assertEquals(1, full.failed)
        assertEquals(setOf(1L, 2L), index.records.keys) // eski kayıt silinmedi
        val retry = indexer.index(IndexMode.INCREMENTAL).toList().last()
        assertEquals(1, retry.total) // yalnızca başarısız olan yeniden denenir
        assertEquals(1, retry.failed)
        indexer.failingIds.clear()
        assertEquals(0, indexer.index(IndexMode.INCREMENTAL).toList().last().failed)
        assertEquals(0, indexer.index(IndexMode.INCREMENTAL).toList().last().total)
    }

    @Test
    fun index_largeGallery_progressIsExactAndAllRecordsWritten() = runTest {
        val n = 5_000
        val photos = (1L..n.toLong()).map { FakePhoto(it, "foto $it") }
        val index = FakeIndex()
        val events = FakePhotoIndexer(spec, photos, index).index().toList()
        assertEquals(n + 2, events.size)
        assertEquals(n, events.last().total)
        assertEquals(n, index.records.size)
        assertEquals(0, FakePhotoIndexer(spec, photos, index).index().toList().last().total)
    }

    @Test
    fun index_cancelAfterScanningOnly_writesNothing_andIncrementalDoesAll() = runTest {
        val photos = (1L..3L).map { FakePhoto(it, "x$it") }
        val index = FakeIndex()
        val indexer = FakePhotoIndexer(spec, photos, index)
        val seen = indexer.index().take(1).toList()
        assertEquals(IndexPhase.SCANNING, seen.single().phase)
        assertTrue(index.records.isEmpty())
        assertEquals(3, indexer.index(IndexMode.INCREMENTAL).toList().last().total)
        assertEquals(3, index.records.size)
    }

    @Test
    fun index_cancelAfterLastPhotoBeforeCompleted_incrementalHasNothingLeft() = runTest {
        val photos = (1L..3L).map { FakePhoto(it, "x$it") }
        val index = FakeIndex()
        val indexer = FakePhotoIndexer(spec, photos, index)
        indexer.index().take(4).toList() // SCANNING + 3 INDEXING, COMPLETED yok
        assertEquals(3, index.records.size)
        val rest = indexer.index(IndexMode.INCREMENTAL).toList()
        assertEquals(0, rest.last().total)
        assertEquals(listOf(IndexPhase.SCANNING, IndexPhase.COMPLETED), rest.map { it.phase })
    }

    @Test
    fun index_cancelledFullTwice_thenIncrementalCompletesRemainder() = runTest {
        val photos = (1L..4L).map { FakePhoto(it, "t$it") }
        val index = FakeIndex()
        val indexer = FakePhotoIndexer(spec, photos, index)
        indexer.index().toList()
        indexer.index(IndexMode.FULL).take(2).toList() // yalnızca foto 1 yeniden yazıldı
        indexer.index(IndexMode.FULL).take(3).toList() // ikinci FULL: foto 1 ve 2
        assertEquals(4, index.records.size)
        val rest = indexer.index(IndexMode.INCREMENTAL).toList().last()
        assertEquals(2, rest.total) // 3 ve 4
        assertEquals(0, indexer.index(IndexMode.INCREMENTAL).toList().last().total)
    }

    @Test
    fun index_fullOnEmptyGallery_andIncrementalOnEmptyGallery_complete() = runTest {
        val indexer = FakePhotoIndexer(spec, emptyList(), FakeIndex())
        IndexMode.values().forEach { mode ->
            val last = indexer.index(mode).toList().last()
            assertEquals(IndexPhase.COMPLETED, last.phase)
            assertEquals(0, last.total)
            assertEquals(1f, last.fraction, 0f)
        }
    }

    // --- SearchRepository: sıralama eşitliği, limit, boş/uzun/Türkçe sorgu ---

    @Test
    fun search_tieBreak_withMixedScoresAndNegativeAndLargeIds() = runTest {
        val photos = listOf(
            FakePhoto(Long.MAX_VALUE, "kedi"), FakePhoto(7, "kedi"), FakePhoto(2, "köpek"),
            FakePhoto(0, "kedi"), FakePhoto(-4, "kedi"), FakePhoto(1, "köpek"),
        )
        val index = FakeIndex()
        FakePhotoIndexer(spec, photos, index).index().toList()
        val ids = FakeSearchRepository(spec, index).search("kedi").map { it.photoId }
        // önce eşit skorlu "kedi" grubu artan id, sonra skoru 0 olan grup artan id
        assertEquals(listOf(-4L, 0L, 7L, Long.MAX_VALUE, 1L, 2L), ids)
    }

    @Test
    fun search_tieBreak_isIndependentOfInsertionOrder_andLimitCutsAfterSorting() = runTest {
        val ids = listOf(8L, 3L, 6L, 1L, 9L, 2L)
        val index = FakeIndex()
        FakePhotoIndexer(spec, ids.map { FakePhoto(it, "ayni") }, index).index().toList()
        val repo = FakeSearchRepository(spec, index)
        assertEquals(listOf(1L, 2L, 3L), repo.search("ayni", limit = 3).map { it.photoId })
    }

    @Test
    fun search_limitBoundaries_oneIntMaxAndAboveSize_andDefaultLimit() = runTest {
        val photos = (1L..120L).map { FakePhoto(it, "plaj") }
        val index = FakeIndex()
        FakePhotoIndexer(spec, photos, index).index().toList()
        val repo = FakeSearchRepository(spec, index)
        assertEquals(1, repo.search("plaj", 1).size)
        assertEquals(120, repo.search("plaj", Int.MAX_VALUE).size)
        assertEquals(120, repo.search("plaj", 121).size)
        assertEquals(DEFAULT_SEARCH_LIMIT, repo.search("plaj").size)
        assertEquals(50, DEFAULT_SEARCH_LIMIT)
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { repo.search("plaj", Int.MIN_VALUE) }
        }
    }

    @Test
    fun search_invalidLimitFailsEvenWhenIndexIsEmpty() = runTest {
        val repo = FakeSearchRepository(spec, FakeIndex())
        assertTrue(runCatching { repo.search("x", 0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(repo.search("x", 1).isEmpty())
    }

    @Test
    fun search_emptyAndBlankQuery_doNotCrash_andRespectContract() = runTest {
        val photos = listOf(FakePhoto(2, "plaj"), FakePhoto(1, "dağ"))
        val index = FakeIndex()
        FakePhotoIndexer(spec, photos, index).index().toList()
        val repo = FakeSearchRepository(spec, index)
        listOf("", "   ", "\n\t").forEach { q ->
            val r = repo.search(q)
            assertEquals(listOf(1L, 2L), r.map { it.photoId }) // hepsi 0 skor -> id artan
            assertTrue(r.all { it.score == 0f })
        }
        assertTrue(repo.search("").isNotEmpty() || index.records.isEmpty())
    }

    @Test
    fun search_veryLongQuery_returnsFiniteScoresInRange() = runTest {
        val photos = listOf(FakePhoto(1, "plaj"), FakePhoto(2, "dağ"))
        val index = FakeIndex()
        FakePhotoIndexer(spec, photos, index).index().toList()
        val q = "plaj ".repeat(200_000) + "ç".repeat(100_000)
        val r = FakeSearchRepository(spec, index).search(q)
        assertEquals(2, r.size)
        assertEquals(1L, r.first().photoId)
        assertTrue(r.all { it.score.isFinite() && it.score in -1.0001f..1.0001f })
    }

    @Test
    fun search_turkishCharacters_matchExactlyAndStayDeterministic() = runTest {
        val photos = listOf(
            FakePhoto(1, "çocuk ğ ı ö ş ü"),
            FakePhoto(2, "çocuk"),
            FakePhoto(3, "cocuk"), // ASCII karşılığı farklı kelime
            FakePhoto(4, "ışık"),
            FakePhoto(5, "ısık"),
        )
        val index = FakeIndex()
        FakePhotoIndexer(spec, photos, index).index().toList()
        val repo = FakeSearchRepository(spec, index)
        assertEquals(2L, repo.search("çocuk").first().photoId)
        assertEquals(4L, repo.search("ışık").first().photoId)
        assertEquals(1L, repo.search("ç ğ ı ö ş ü").first().photoId)
        assertEquals(repo.search("çocuk"), repo.search("çocuk")) // deterministik
    }

    @Test
    fun search_turkishCapitalIAndDottedI_doNotCrashAndKeepInvariants() = runTest {
        val photos = listOf(FakePhoto(1, "ışık"), FakePhoto(2, "istanbul"), FakePhoto(3, "IŞIK İSTANBUL"))
        val index = FakeIndex()
        FakePhotoIndexer(spec, photos, index).index().toList()
        val repo = FakeSearchRepository(spec, index)
        listOf("I", "İ", "ı", "i", "IŞIK", "İSTANBUL", "Işık İstanbul").forEach { q ->
            val r = repo.search(q)
            assertEquals(3, r.size)
            assertEquals(setOf(1L, 2L, 3L), r.map { it.photoId }.toSet())
            assertTrue(r.zipWithNext().all { (a, b) ->
                a.score > b.score || (a.score == b.score && a.photoId < b.photoId)
            })
        }
    }

    @Test
    fun search_staleVersionOnly_isEmptyNotError_andMixedVersionsOnlyReturnCurrent() = runTest {
        val index = FakeIndex()
        val photos = listOf(FakePhoto(1, "plaj"), FakePhoto(2, "plaj"))
        FakePhotoIndexer(spec, photos, index).index().toList() // fake-1
        val v2 = EmbeddingSpec(spec.dimension, "fake-2")
        val indexerV2 = FakePhotoIndexer(v2, listOf(FakePhoto(2, "plaj")), index)
        indexerV2.index().toList() // yalnızca foto 2 fake-2'ye yükseltilir
        val ids = FakeSearchRepository(v2, index).search("plaj").map { it.photoId }
        assertEquals(listOf(2L), ids)
        val idsV1 = FakeSearchRepository(spec, index).search("plaj").map { it.photoId }
        assertEquals(listOf(1L), idsV1)
    }
}
