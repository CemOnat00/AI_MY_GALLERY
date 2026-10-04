package com.ktu.aigaleri.domain

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainContractTest {
    // Çakışma olmaması için büyük boyut; boyut sabit değil, spec ile taşınır.
    private val spec = EmbeddingSpec(dimension = 4096, modelVersion = "fake-1")
    private val gallery = listOf(
        FakePhoto(1, "plaj çocuk gülmek deniz", dateTaken = 100),
        FakePhoto(2, "plaj gün batımı"),
        FakePhoto(3, "dağ kar kış"),
        FakePhoto(4, "plaj çocuk"),
    )

    private class Env(val index: FakeIndex, val indexer: FakePhotoIndexer, val repo: SearchRepository)

    private fun env(failing: Set<Long> = emptySet()): Env {
        val index = FakeIndex()
        return Env(
            index,
            FakePhotoIndexer(spec, gallery, index, failing.toMutableSet()),
            FakeSearchRepository(spec, index),
        )
    }

    @Test
    fun index_emitsScanningThenIndexingThenCompleted() = runTest {
        val events = env().indexer.index().toList()
        assertEquals(IndexPhase.SCANNING, events.first().phase)
        assertEquals(IndexPhase.COMPLETED, events.last().phase)
        assertEquals(listOf(1, 2, 3, 4), events.filter { it.phase == IndexPhase.INDEXING }.map { it.processed })
        assertEquals(1f, events.last().fraction)
    }

    @Test
    fun index_skipsFailingPhotoAndContinues() = runTest {
        val e = env(failing = setOf(2L))
        val last = e.indexer.index().toList().last()
        assertEquals(4, last.processed)
        assertEquals(1, last.failed)
        assertEquals(setOf(1L, 3L, 4L), e.index.records.keys)
    }

    @Test
    fun index_failedPhotosAreRetriedEveryIncrementalRun_failedCountsOnlyThatRun() = runTest {
        val e = env(failing = setOf(2L))
        e.indexer.index().toList()
        val retry = e.indexer.index(IndexMode.INCREMENTAL).toList().last()
        assertEquals(1, retry.total) // yalnızca başarısız olan yeniden denendi
        assertEquals(1, retry.failed)
        e.indexer.failingIds.clear()
        val fixed = e.indexer.index(IndexMode.INCREMENTAL).toList().last()
        assertEquals(1, fixed.total)
        assertEquals(0, fixed.failed)
        assertEquals(4, e.index.records.size)
    }

    @Test
    fun index_incrementalSkipsAlreadyIndexed_fullReprocessesAll() = runTest {
        val e = env()
        e.indexer.index().toList()
        assertEquals(0, e.indexer.index(IndexMode.INCREMENTAL).toList().last().total)
        assertEquals(4, e.indexer.index(IndexMode.FULL).toList().last().total)
    }

    @Test
    fun index_emptyGallery_emitsOnlyScanningAndCompleted() = runTest {
        val events = FakePhotoIndexer(spec, emptyList(), FakeIndex()).index().toList()
        assertEquals(listOf(IndexPhase.SCANNING, IndexPhase.COMPLETED), events.map { it.phase })
    }

    @Test
    fun index_isColdAndCancellationDuringIndexingKeepsWrittenRecords() = runTest {
        val e = env()
        val flow = e.indexer.index()
        assertTrue(e.index.records.isEmpty()) // toplanmadan iş başlamaz

        // SCANNING + 2 INDEXING sonrası iptal: fotoğraf 1 ve 2 yazılmış, 3 ve 4 yazılmamış olmalı.
        val seen = flow.take(3).toList()
        assertEquals(IndexPhase.INDEXING, seen.last().phase)
        assertEquals(setOf(1L, 2L), e.index.records.keys)

        // INCREMENTAL kalan 2 fotoğrafı işler.
        val rest = e.indexer.index(IndexMode.INCREMENTAL).toList().last()
        assertEquals(2, rest.total)
        assertEquals(setOf(1L, 2L, 3L, 4L), e.index.records.keys)
    }

    @Test
    fun index_cancelledFullKeepsOldRecordsSearchable_thenIncrementalCompletesRest() = runTest {
        val e = env()
        e.indexer.index().toList()
        // Fotoğraf 1 ve 2'nin etiketi değişti; FULL bunları yeniden yazacak, 3 ve 4'e gelmeden iptal.
        val changed = listOf(FakePhoto(1, "kedi", 100), FakePhoto(2, "kedi"), gallery[2], gallery[3])
        val indexer2 = FakePhotoIndexer(spec, changed, e.index)
        indexer2.index(IndexMode.FULL).take(3).toList()

        assertEquals(4, e.index.records.size) // önce silmedi
        // 1 ve 2 yeni içerikle, 3 ve 4 eski kayıtla aranabilir.
        assertEquals(listOf(1L, 2L), e.repo.search("kedi", limit = 2).map { it.photoId })
        assertEquals(4L, e.repo.search("plaj çocuk").first().photoId)

        // FULL tamamlanmadığı için INCREMENTAL yalnızca kalan 3 ve 4'ü yeniler.
        val rest = indexer2.index(IndexMode.INCREMENTAL).toList().last()
        assertEquals(2, rest.total)
        assertEquals(0, indexer2.index(IndexMode.INCREMENTAL).toList().last().total)
    }

    @Test
    fun index_staleModelVersionRecordsAreExcludedFromSearch_andReindexedIncrementally() = runTest {
        val e = env()
        e.indexer.index().toList()
        val v2 = EmbeddingSpec(spec.dimension, "fake-2")
        val repoV2 = FakeSearchRepository(v2, e.index)
        assertTrue(repoV2.search("plaj").isEmpty()) // eski sürüm kayıtları aramaya girmez

        val indexerV2 = FakePhotoIndexer(v2, gallery, e.index)
        assertEquals(4, indexerV2.index(IndexMode.INCREMENTAL).toList().last().total)
        assertEquals(3, repoV2.search("plaj").count { it.score > 0f })
    }

    @Test
    fun index_withoutPermission_failsWithPermissionMissing() = runTest {
        val indexer = FakePhotoIndexer(spec, gallery, FakeIndex(), permissionGranted = false)
        val result = runCatching { indexer.index().toList() }
        assertTrue(result.exceptionOrNull() is IndexException.PermissionMissing)
    }

    @Test
    fun search_ranksByScoreDescending() = runTest {
        val e = env()
        e.indexer.index().toList()
        val results = e.repo.search("plaj çocuk")
        // 4: tam eşleşme (1.0); 1: iki ortak kelime / 4 kelime; 2: tek ortak / 3 kelime; 3: eşleşme yok (0)
        assertEquals(listOf(4L, 1L, 2L, 3L), results.map { it.photoId })
        assertEquals(1.0f, results[0].score, 1e-5f)
        assertTrue(results.zipWithNext().all { (a, b) -> a.score >= b.score })
        assertEquals(100L, results[1].dateTaken)
        assertEquals("content://fake/media/4", results[0].uri)
    }

    @Test
    fun search_tieBreak_usesPhotoIdAscending() = runTest {
        val index = FakeIndex()
        val photos = listOf(FakePhoto(9, "kedi"), FakePhoto(3, "kedi"), FakePhoto(5, "kedi"))
        FakePhotoIndexer(spec, photos, index).index().toList()
        val ids = FakeSearchRepository(spec, index).search("kedi").map { it.photoId }
        assertEquals(listOf(3L, 5L, 9L), ids)
    }

    @Test
    fun search_emptyIndexGivesEmptyList_limitIsRespected() = runTest {
        val e = env()
        assertTrue(e.repo.search("plaj").isEmpty())
        e.indexer.index().toList()
        assertEquals(2, e.repo.search("plaj", limit = 2).size)
        assertEquals(1, e.repo.search("plaj", limit = 1).size)
    }

    @Test
    fun search_rejectsNonPositiveLimit() = runTest {
        val repo = env().repo
        listOf(0, -1).forEach { limit ->
            val result = runCatching { repo.search("a", limit) }
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun search_cancellationDeliversNoResult() = runTest {
        val e = env()
        e.indexer.index().toList()
        var results: List<SearchResult>? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) { results = e.repo.search("plaj") }
        job.cancelAndJoin() // arama iptal noktasında (yield) askıda
        assertTrue(job.isCancelled)
        assertNull(results)
    }

    @Test
    fun dataClasses_validateInvariants() {
        assertThrows(IllegalArgumentException::class.java) { EmbeddingSpec(0, "v") }
        assertThrows(IllegalArgumentException::class.java) { EmbeddingSpec(8, " ") }
        assertThrows(IllegalArgumentException::class.java) { IndexProgress(IndexPhase.INDEXING, 2, 3) }
        assertThrows(IllegalArgumentException::class.java) { IndexProgress(IndexPhase.INDEXING, 2, 1, failed = 2) }
        assertThrows(IllegalArgumentException::class.java) { IndexProgress(IndexPhase.COMPLETED, 2, 1) }
        assertThrows(IllegalArgumentException::class.java) { SearchResult(1, "", 0.5f) }
        assertEquals(0.5f, IndexProgress(IndexPhase.INDEXING, 4, 2).fraction)
    }
}
