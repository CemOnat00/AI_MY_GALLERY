package com.ktu.aigaleri.domain

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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

    private fun env(failing: Set<Long> = emptySet()): Triple<FakeIndex, PhotoIndexer, SearchRepository> {
        val index = FakeIndex()
        return Triple(
            index,
            FakePhotoIndexer(spec, gallery, index, failing),
            FakeSearchRepository(spec, index),
        )
    }

    @Test
    fun index_emitsScanningThenIndexingThenCompleted() = runTest {
        val (_, indexer, _) = env()
        val events = indexer.index().toList()
        assertEquals(IndexPhase.SCANNING, events.first().phase)
        assertEquals(IndexPhase.COMPLETED, events.last().phase)
        assertEquals(listOf(1, 2, 3, 4), events.filter { it.phase == IndexPhase.INDEXING }.map { it.processed })
        assertEquals(1f, events.last().fraction)
    }

    @Test
    fun index_skipsFailingPhotoAndContinues() = runTest {
        val (index, indexer, _) = env(failing = setOf(2L))
        val last = indexer.index().toList().last()
        assertEquals(4, last.processed)
        assertEquals(1, last.failed)
        assertEquals(setOf(1L, 3L, 4L), index.vectors.keys)
    }

    @Test
    fun index_incrementalSkipsAlreadyIndexed_fullReprocessesAll() = runTest {
        val (_, indexer, _) = env()
        indexer.index().toList()
        assertEquals(0, indexer.index(IndexMode.INCREMENTAL).toList().last().total)
        assertEquals(4, indexer.index(IndexMode.FULL).toList().last().total)
    }

    @Test
    fun index_emptyGallery_emitsOnlyScanningAndCompleted() = runTest {
        val index = FakeIndex()
        val events = FakePhotoIndexer(spec, emptyList(), index).index().toList()
        assertEquals(listOf(IndexPhase.SCANNING, IndexPhase.COMPLETED), events.map { it.phase })
    }

    @Test
    fun index_coldFlow_doesNothingUntilCollected() = runTest {
        val (index, indexer, _) = env()
        val flow = indexer.index()
        assertTrue(index.vectors.isEmpty())
        flow.first() // yalnızca SCANNING; iptal sonrası yazım olmaz
        assertTrue(index.vectors.isEmpty())
    }

    @Test
    fun search_ranksByScoreDescending_tieBrokenByPhotoId() = runTest {
        val (_, indexer, repo) = env()
        indexer.index().toList()
        val results = repo.search("plaj çocuk")
        // 4: tam eşleşme (1.0); 1: iki ortak kelime / 4 kelime; 2: tek ortak kelime / 3 kelime
        assertEquals(listOf(4L, 1L, 2L), results.map { it.photoId })
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
    fun search_respectsLimit_emptyIndexAndNoMatchGiveEmptyList() = runTest {
        val (_, indexer, repo) = env()
        assertTrue(repo.search("plaj").isEmpty())
        indexer.index().toList()
        assertEquals(2, repo.search("plaj", limit = 2).size)
        assertTrue(repo.search("uzay").isEmpty())
    }

    @Test
    fun search_rejectsNonPositiveLimit() = runTest {
        val (_, _, repo) = env()
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { repo.search("a", 0) } }
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
