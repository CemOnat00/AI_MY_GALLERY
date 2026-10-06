package com.ktu.aigaleri.data

import com.ktu.aigaleri.domain.EmbeddingSpec
import com.ktu.aigaleri.domain.ImageEncoder
import com.ktu.aigaleri.domain.IndexException
import com.ktu.aigaleri.domain.IndexMode
import com.ktu.aigaleri.domain.IndexPhase
import com.ktu.aigaleri.domain.IndexProgress
import com.ktu.aigaleri.ml.ImageDecodeException
import com.ktu.aigaleri.ml.ModelException
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * T-006 (QA): RoomPhotoIndexer sınır durumları; sahte kaynak/kodlayıcı/bellek içi DAO ile (model ve cihaz yok).
 * (Eski KNOWN_ISSUE testleri T-006 ek turunda düzeltilen davranışa göre güncellendi: FULL kalıcı tur durumu ve
 * yalnızca model hatalarını sayan eşik.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomPhotoIndexerEdgeCaseTest {
    private val spec = EmbeddingSpec(8, "test-model-v1")
    private var now = 50_000L

    private class Env(
        val db: FakeIndexDb,
        val source: RecordingSource,
        val encoder: FakeImageEncoder,
        val indexer: RoomPhotoIndexer,
    )

    private fun TestScope.env(
        ids: List<Long>,
        spec: EmbeddingSpec = this@RoomPhotoIndexerEdgeCaseTest.spec,
        db: FakeIndexDb = FakeIndexDb(),
        source: RecordingSource = RecordingSource(ids.map { mediaPhoto(it) }),
    ): Env {
        val encoder = FakeImageEncoder(spec)
        val indexer = RoomPhotoIndexer(
            source, db.photoDao, db.embeddingDao, db.stateDao, db.transactions, encoder,
            clock = { now++ },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        return Env(db, source, encoder, indexer)
    }

    private fun causes(t: Throwable) = generateSequence<Throwable>(t) { it.cause }.take(8).toList()

    private fun uri(id: Long) = mediaPhoto(id).uri

    private suspend fun Flow<IndexProgress>.run() = toList()

    private suspend fun TestScope.cancelAfterEncodes(e: Env, mode: IndexMode, n: Int) {
        var count = 0
        lateinit var job: Job
        e.encoder.onEncode = { if (++count == n) job.cancel() }
        job = launch { e.indexer.index(mode).collect { } }
        job.join()
        e.encoder.onEncode = {}
    }

    private fun versions(db: FakeIndexDb) = db.photos.values.groupBy({ it.indexVersion }, { it.mediaStoreId })

    // ================= boş galeri =================

    @Test
    fun emptyGallery_everyMode_isIdempotent_andNeverTouchesMediaStoreByIds() = runTest {
        val e = env(emptyList())
        for (mode in IndexMode.entries + IndexMode.entries) {
            val out = e.indexer.index(mode).run()
            assertEquals(
                listOf(IndexProgress(IndexPhase.SCANNING, 0, 0), IndexProgress(IndexPhase.COMPLETED, 0, 0)),
                out,
            )
        }
        assertTrue(e.source.loadByIdsSizes.isEmpty())
        assertTrue(e.encoder.encoded.isEmpty())
        assertTrue(e.db.photos.isEmpty() && e.db.embeddings.isEmpty())
        assertEquals(IndexState(total = 0, processed = 0, lastRunAt = e.db.state!!.lastRunAt), e.db.state)
        assertEquals(4, e.encoder.released) // her çalıştırmada oturum bırakıldı
    }

    @Test
    fun emptyGallery_thenPhotosAppear_nextRunIndexesThem() = runTest {
        val e = env(emptyList())
        e.indexer.index().run()
        e.source.gallery = listOf(mediaPhoto(1), mediaPhoto(2))
        val out = e.indexer.index().run()
        assertEquals(IndexProgress(IndexPhase.COMPLETED, 2, 2), out.last())
        assertEquals(setOf(1L, 2L), e.db.photos.keys)
    }

    // ================= çok büyük galeri (bellek / sıra) =================

    /** loadPhotos YASAK; yalnızca kimlik ve <= 50'lik parçalar. 100 bin fotoğraf için ucuz sahte. */
    private class BigSource(val ids: LongArray) : MediaPhotoSource {
        var maxBatch = 0
        var batches = 0
        override suspend fun count() = ids.size
        override suspend fun loadPhotos(): List<MediaPhoto> = throw AssertionError("loadPhotos çağrılmamalı")
        override suspend fun loadIds(): LongArray = ids.copyOf().also { it.reverse() } // sırasız
        override suspend fun loadByIds(ids: List<Long>): List<MediaPhoto> {
            batches++
            maxBatch = maxOf(maxBatch, ids.size)
            return ids.map { mediaPhoto(it) }
        }
    }

    /** Hafif kodlayıcı: uri kaydetmez, yalnızca sıra ve sayı tutar. */
    private class CountingEncoder(override val embeddingSpec: EmbeddingSpec) : ImageEncoder {
        var count = 0
        var last = Long.MAX_VALUE
        var descending = true
        var released = 0
        private val vec = FloatArray(embeddingSpec.dimension).also { it[0] = 1f }
        override suspend fun encode(uri: String): FloatArray {
            val id = uri.substringAfterLast('/').toLong()
            if (id >= last) descending = false
            last = id
            count++
            return vec
        }
        override suspend fun release() { released++ }
    }

    /** Işlem kopyası (O(n) her yazımda) yerine geçişli çalıştırıcı: büyük galeri testi O(n^2) olmasın. */
    private val passThrough = object : TransactionRunner {
        override suspend fun <T> run(block: suspend () -> T): T = block()
    }

    private fun TestScope.bigIndexer(db: FakeIndexDb, source: MediaPhotoSource, enc: ImageEncoder) =
        RoomPhotoIndexer(
            source, db.photoDao, db.embeddingDao, db.stateDao, passThrough, enc,
            clock = { now++ }, dispatcher = StandardTestDispatcher(testScheduler),
        )

    @Test
    fun hugeGallery_100k_freshIndex_newestFirst_smallBatches_boundedStateWrites() = runTest(timeout = 5.minutes) {
        val n = 100_000
        val src = BigSource(LongArray(n) { (it + 1).toLong() })
        val enc = CountingEncoder(spec)
        val db = FakeIndexDb()
        var emissions = 0
        var prev = 0
        var monotone = true
        var failedEver = 0
        var last: IndexProgress? = null
        bigIndexer(db, src, enc).index(IndexMode.INCREMENTAL).collect {
            last = it
            if (it.phase == IndexPhase.INDEXING) {
                emissions++
                if (it.processed != prev + 1) monotone = false
                prev = it.processed
                failedEver += it.failed
            }
        }
        assertEquals(IndexProgress(IndexPhase.COMPLETED, n, n), last)
        assertEquals(n, emissions)
        assertTrue("processed her adımda +1", monotone)
        assertEquals(0, failedEver)
        assertTrue("en yeni kimlik önce", enc.descending)
        assertEquals(n, enc.count)
        assertEquals(n, db.photos.size)
        assertEquals(n, db.embeddings.size)
        assertTrue(src.maxBatch <= RoomPhotoIndexer.BATCH_SIZE)
        assertEquals(n / RoomPhotoIndexer.BATCH_SIZE, src.batches)
        // IndexState: başlangıç + her 10'da bir (son hariç) + bitiş.
        assertEquals(n / RoomPhotoIndexer.STATE_WRITE_EVERY + 1, db.stateWrites.size)
        assertEquals(1, enc.released)
    }

    @Test
    fun hugeGallery_100k_upToDate_rerunProcessesNothing_andShrinkingGalleryDeletesInChunks() = runTest(timeout = 5.minutes) {
        val n = 100_000
        val db = FakeIndexDb()
        val vec = VectorCodec.encode(FloatArray(8).also { it[0] = 1f })
        for (id in 1L..n) {
            db.photos[id] = mediaPhoto(id).toPhoto(1L, 1)
            db.embeddings[id] = PhotoEmbedding(id, vec, spec.modelVersion)
        }
        val src = BigSource(LongArray(n) { (it + 1).toLong() })
        val enc = CountingEncoder(spec)
        val out = bigIndexer(db, src, enc).index(IndexMode.INCREMENTAL).toList()
        assertEquals(listOf(IndexPhase.SCANNING, IndexPhase.COMPLETED), out.map { it.phase })
        assertEquals(0, enc.count)
        assertEquals(0, src.batches)
        assertEquals(n, db.photos.size)

        // Galeri 100k -> 40k: 60k kayıt <= 500'lük parçalarla silinir; vektörler CASCADE ile gider.
        val small = BigSource(LongArray(40_000) { (it + 1).toLong() })
        bigIndexer(db, small, enc).index(IndexMode.INCREMENTAL).toList()
        assertEquals(40_000, db.photos.size)
        assertEquals(40_000, db.embeddings.size)
        assertEquals(120, db.deleteByIdsCalls.size)
        assertTrue(db.deleteByIdsCalls.all { it.size <= RoomPhotoIndexer.DELETE_CHUNK })
        assertEquals(0, enc.count)
    }

    @Test
    fun hugeGallery_50k_full_replacesInPlace_everyRecordGetsNewVersion() = runTest(timeout = 5.minutes) {
        val n = 50_000
        val db = FakeIndexDb()
        val src = BigSource(LongArray(n) { (it + 1).toLong() })
        val enc = CountingEncoder(spec)
        val ix = bigIndexer(db, src, enc)
        ix.index().toList()
        ix.index(IndexMode.FULL).toList()
        assertEquals(2 * n, enc.count)
        assertEquals(setOf(2), db.photos.values.map { it.indexVersion }.toSet())
        assertEquals(n, db.embeddings.size)
    }

    // ================= iptal sonrası yeniden başlama =================

    @Test
    fun incremental_repeatedCancellations_eventuallyCompletes_eachPhotoEncodedOnce() = runTest {
        val e = env((1L..7L).toList())
        val seen = mutableListOf<String>()
        var rounds = 0
        while (e.db.photos.size < 7 && rounds < 10) {
            e.encoder.encoded.clear()
            cancelAfterEncodes(e, IndexMode.INCREMENTAL, 2)
            seen += e.encoder.encoded
            rounds++
        }
        assertEquals(4, rounds) // 2+2+2+1
        assertEquals(7, seen.size)
        assertEquals(7, seen.toSet().size) // hiçbir fotoğraf iki kez işlenmedi
        assertEquals(7, e.db.embeddings.size)
    }

    @Test
    fun full_repeatedCancellations_resumeSameTarget_noRedo_thenFinishes() = runTest {
        val e = env((1L..5L).toList())
        e.indexer.index().run()
        e.encoder.encoded.clear()
        val seen = mutableListOf<String>()
        cancelAfterEncodes(e, IndexMode.FULL, 2); seen += e.encoder.encoded; e.encoder.encoded.clear()
        cancelAfterEncodes(e, IndexMode.FULL, 2); seen += e.encoder.encoded; e.encoder.encoded.clear()
        e.indexer.index(IndexMode.FULL).run(); seen += e.encoder.encoded
        assertEquals(listOf(uri(5), uri(4), uri(3), uri(2), uri(1)), seen)
        assertEquals(setOf(2), e.db.photos.values.map { it.indexVersion }.toSet()) // tek tur: sürüm 2
        assertEquals(5, e.db.embeddings.size)
    }

    @Test
    fun cancelledAfterFirstPhoto_keepsIt_andCancelledDuringScan_writesNothing() = runTest {
        val e = env(listOf(1, 2, 3))
        cancelAfterEncodes(e, IndexMode.INCREMENTAL, 1) // ilk fotoğraf yazıldıktan sonra
        assertEquals(1, e.db.photos.size)
        val e2 = env(listOf(10, 11), db = FakeIndexDb())
        lateinit var job: Job
        e2.source.afterLoadIds = { job.cancel() }
        job = launch { e2.indexer.index().collect { } }
        job.join()
        assertTrue(e2.db.photos.isEmpty())
        assertTrue(e2.encoder.encoded.isEmpty())
    }

    // ================= tüm fotoğraflar başarısız =================

    @Test
    fun allPhotosFail_belowLimit_completesWithFailedCount_noRecords_stateComplete() = runTest {
        val n = RoomPhotoIndexer.MAX_MODEL_FAILURES_WITHOUT_SUCCESS - 1
        val e = env((1L..n.toLong()).toList())
        for (id in 1L..n) e.encoder.failures[uri(id)] = ImageDecodeException("bozuk")
        val last = e.indexer.index().run().last()
        assertEquals(IndexProgress(IndexPhase.COMPLETED, n, n, failed = n), last)
        assertTrue(e.db.photos.isEmpty())
        assertEquals(n, e.db.state!!.processed)
        assertTrue(e.db.state!!.lastRunAt != null)
    }

    @Test
    fun allPhotosFail_inFull_atLimit_keepsOldRecordsAndOldVectors_andDoesNotAdvanceLastRunAt() = runTest {
        val n = RoomPhotoIndexer.MAX_MODEL_FAILURES_WITHOUT_SUCCESS + 3
        val e = env((1L..n.toLong()).toList())
        e.indexer.index().run()
        val firstRun = e.db.state!!.lastRunAt
        val blobs = e.db.embeddings.mapValues { it.value.vector.copyOf() }
        for (id in 1L..n) e.encoder.failures[uri(id)] = ModelException.Inference("run", RuntimeException("ort"))
        try {
            e.indexer.index(IndexMode.FULL).run()
            fail("Unexpected beklenir")
        } catch (_: IndexException.Unexpected) {
        }
        // Kesilen FULL turu kalıcıdır: yeniden istenince aynı hedefle (2) devam eder.
        assertEquals(2, e.db.state!!.fullTargetVersion)
        assertEquals(n, e.db.photos.size)
        for ((id, b) in blobs) assertArrayEquals(b, e.db.embeddings.getValue(id).vector)
        assertTrue(e.db.photos.values.all { it.indexVersion == 1 })
        assertEquals(firstRun, e.db.state!!.lastRunAt)
        // Sınırı aşan 20. başarısız fotoğraf processed'e sayılmadan akış kesilir.
        assertEquals(RoomPhotoIndexer.MAX_MODEL_FAILURES_WITHOUT_SUCCESS - 1, e.db.state!!.processed)
    }

    // ================= gerçek hayat: ilk 20 çözme hatası =================

    @Test
    fun first19DecodeFailures_thenSuccess_completes_andLaterFailuresAreNotLimited() = runTest {
        val limit = RoomPhotoIndexer.MAX_MODEL_FAILURES_WITHOUT_SUCCESS
        // En yeni (büyük kimlik) 19 bozuk, sonra sağlam, sonra yine 30 bozuk.
        val total = 19 + 1 + 30
        val e = env((1L..total.toLong()).toList())
        for (id in (total - 18)..total) e.encoder.failures[uri(id.toLong())] = ImageDecodeException("bozuk")
        for (id in 1L..30L) e.encoder.failures[uri(id)] = ImageDecodeException("bozuk")
        val last = e.indexer.index().run().last()
        assertEquals(IndexPhase.COMPLETED, last.phase)
        assertEquals(19 + 30, last.failed)
        assertEquals(setOf(31L), e.db.photos.keys)
        assertTrue(19 < limit)
    }

    /**
     * En yeni 20 fotoğraf ImageDecodeException verse bile (fotoğrafa özgü hata) eşik tetiklenmez: akış COMPLETED olur,
     * eski sağlam fotoğraflar indekslenir. Eşik yalnızca model kaynaklı hataları sayar.
     */
    @Test
    fun newest20DecodeFailures_doNotStopFlow_olderHealthyPhotosAreIndexed() = runTest {
        val limit = RoomPhotoIndexer.MAX_MODEL_FAILURES_WITHOUT_SUCCESS
        val total = limit + 5
        val e = env((1L..total.toLong()).toList())
        for (id in (total - limit + 1)..total) e.encoder.failures[uri(id.toLong())] = ImageDecodeException("bozuk")
        val last = e.indexer.index().run().last()
        assertEquals(IndexProgress(IndexPhase.COMPLETED, total, total, failed = limit), last)
        assertEquals((1L..5L).toSet(), e.db.photos.keys)
    }

    @Test
    fun decodeFailuresDoNotCount_evenMixedWithFewerModelFailures() = runTest {
        val limit = RoomPhotoIndexer.MAX_MODEL_FAILURES_WITHOUT_SUCCESS
        // 100 çözme hatası + (limit-1) model hatası: hiçbiri tek başına/toplam eşiği aşmaz, sağlamlar indekslenir.
        val total = 100 + (limit - 1) + 3
        val e = env((1L..total.toLong()).toList())
        for (id in 1L..(100L + limit - 1)) {
            e.encoder.failures[uri(total - id + 1)] =
                if (id % 2 == 0L && id <= 2L * (limit - 1)) ModelException.Inference("run", RuntimeException()) else ImageDecodeException("b")
        }
        val last = e.indexer.index().run().last()
        assertEquals(IndexPhase.COMPLETED, last.phase)
        assertEquals(3, e.db.photos.size)
    }

    @Test
    fun modelFailuresAtLimit_withoutSuccess_stillEndFlowWithUnexpected_evenWithDecodeFailuresInterleaved() = runTest {
        val limit = RoomPhotoIndexer.MAX_MODEL_FAILURES_WITHOUT_SUCCESS
        val total = 3 * limit
        val e = env((1L..total.toLong()).toList())
        for (id in 1L..total) {
            e.encoder.failures[uri(id)] =
                if (id % 2 == 0L) ModelException.Inference("run", RuntimeException()) else ImageDecodeException("b")
        }
        try {
            e.indexer.index().run()
            fail("Unexpected beklenir")
        } catch (_: IndexException.Unexpected) {
        }
        assertEquals(2 * limit - 1, e.encoder.encoded.size) // kimlik azalan: çift=model; 20. model hatasında (39. fotoğraf) kesilir
        assertTrue(e.db.photos.isEmpty())
    }

    // ================= kalıcı başarısız fotoğraf + FULL: her tamamlanmış FULL yeni tur açar =================

    /**
     * FULL, başarısız fotoğraflarla bitse de tur kapanır (IndexState.fullTargetVersion = null); kalıcı bozuk tek fotoğraf
     * sonraki FULL'u kilitlemez: her istenen FULL tüm galeriyi yeni hedefle işler.
     */
    @Test
    fun full_withPersistentFailedPhoto_eachCompletedFullOpensNewRound() = runTest {
        val e = env((1L..5L).toList())
        e.indexer.index().run() // hepsi sürüm 1
        e.encoder.failures[uri(3)] = ImageDecodeException("kalıcı bozuk")
        val first = e.indexer.index(IndexMode.FULL).run().last() // yeni tur: hedef 2, #3 başarısız
        assertEquals(IndexProgress(IndexPhase.COMPLETED, 5, 5, failed = 1), first)
        assertEquals(mapOf(2 to listOf(1L, 2L, 4L, 5L), 1 to listOf(3L)), versions(e.db).mapValues { it.value.sorted() })
        assertNull(e.db.state!!.fullTargetVersion) // tur kapandı

        e.encoder.encoded.clear()
        val second = e.indexer.index(IndexMode.FULL).run().last() // kullanıcı tekrar "yeniden indeksle" der
        assertEquals(IndexProgress(IndexPhase.COMPLETED, 5, 5, failed = 1), second) // tüm galeri, yeni tur
        assertEquals(5, e.encoder.encoded.size)
        assertEquals(mapOf(3 to listOf(1L, 2L, 4L, 5L), 1 to listOf(3L)), versions(e.db).mapValues { it.value.sorted() })
        assertNull(e.db.state!!.fullTargetVersion)

        // Fotoğraf düzelince INCREMENTAL onu 3'e yükseltir; sonraki FULL yine yeni tur (4).
        e.encoder.failures.clear()
        e.indexer.index().run()
        assertEquals(setOf(3), e.db.photos.values.map { it.indexVersion }.toSet())
        val third = e.indexer.index(IndexMode.FULL).run().last()
        assertEquals(5, third.total)
        assertEquals(setOf(4), e.db.photos.values.map { it.indexVersion }.toSet())
    }

    // ================= FULL kalıcı tur durumu (IndexState.fullTargetVersion) =================

    @Test
    fun fullTargetVersion_setWhenFullStarts_clearedOnlyWhenFullCompletes_incrementalLeavesItAlone() = runTest {
        val e = env((1L..4L).toList())
        e.indexer.index().run()
        assertNull(e.db.state!!.fullTargetVersion)
        cancelAfterEncodes(e, IndexMode.FULL, 1)
        assertEquals(2, e.db.state!!.fullTargetVersion) // yarım FULL
        // INCREMENTAL kalanı tamamlar ama işareti silmez.
        e.indexer.index().run()
        assertEquals(setOf(2), e.db.photos.values.map { it.indexVersion }.toSet())
        assertEquals(2, e.db.state!!.fullTargetVersion)
        assertTrue(e.db.stateWrites.drop(e.db.stateWrites.size - 3).all { it.fullTargetVersion == 2 })
        // Tur INCREMENTAL ile tamamlanmıştı: sonraki FULL kalan görmez ve yeni tur (3) açar, bitince işaret kalkar.
        e.encoder.encoded.clear()
        assertEquals(4, e.indexer.index(IndexMode.FULL).run().last().total)
        assertEquals(setOf(3), e.db.photos.values.map { it.indexVersion }.toSet())
        assertNull(e.db.state!!.fullTargetVersion)
    }

    @Test
    fun fullCancelledWithZeroPhotoWrites_afterPlanPersisted_resumesSameTarget_andIncrementalAlsoCompletesIt() = runTest {
        for (resumeWith in listOf(IndexMode.FULL, IndexMode.INCREMENTAL)) {
            val e = env((1L..3L).toList())
            e.indexer.index().run()
            e.encoder.failures[uri(3)] = CancellationException("iptal") // ilk kodlamada iptal: hiç fotoğraf yazılmaz
            try {
                e.indexer.index(IndexMode.FULL).run()
            } catch (_: CancellationException) {
            }
            assertTrue(e.db.photos.values.all { it.indexVersion == 1 })
            assertEquals(2, e.db.state!!.fullTargetVersion) // tur kalıcı (sıfır yazımla bile)
            e.encoder.failures.clear()
            e.encoder.encoded.clear()
            val out = e.indexer.index(resumeWith).run().last()
            assertEquals("$resumeWith", 3, out.total) // hedefin altındaki tüm kayıtlar
            assertTrue(e.db.photos.values.all { it.indexVersion == 2 }) // max+1 değil: aynı hedef
            assertEquals(if (resumeWith == IndexMode.FULL) null else 2, e.db.state!!.fullTargetVersion)
        }
    }

    @Test
    fun full_failedPhotoDeletedFromGallery_unblocksNextFullNewRound() = runTest {
        val e = env((1L..4L).toList())
        e.indexer.index().run()
        e.encoder.failures[uri(2)] = ImageDecodeException("bozuk")
        e.indexer.index(IndexMode.FULL).run()
        e.source.gallery = e.source.gallery.filter { it.id != 2L } // kullanıcı bozuk fotoğrafı sildi
        e.encoder.encoded.clear()
        val out = e.indexer.index(IndexMode.FULL).run().last()
        // Bozuk fotoğrafın kaydı silinince Room'da kalan yok: FULL yeni tur (hedef 3) olarak tüm galeriyi işler.
        assertFalse(2L in e.db.photos)
        assertEquals(3, out.total)
        assertEquals(setOf(3), e.db.photos.values.map { it.indexVersion }.toSet())
    }

    // ================= aynı modelVersion'da yeniden işlememe =================

    @Test
    fun sameModelVersion_neverReprocessed_acrossRuns_andAcrossNewIndexerInstances() = runTest {
        val db = FakeIndexDb()
        val e = env(listOf(1, 2, 3), db = db)
        e.indexer.index().run()
        val blobs = db.embeddings.mapValues { it.value.vector.copyOf() }
        repeat(3) { e.indexer.index().run() }
        val restarted = env(emptyList(), db = db, source = e.source) // süreç yeniden başladı: yeni örnek
        restarted.indexer.index().run()
        restarted.indexer.index(IndexMode.INCREMENTAL).run()
        assertEquals(3, e.encoder.encoded.size)
        assertTrue(restarted.encoder.encoded.isEmpty())
        for ((id, b) in blobs) assertArrayEquals(b, db.embeddings.getValue(id).vector)
    }

    @Test
    fun modelVersionChange_reprocessesAll_thenStableAgain() = runTest {
        val db = FakeIndexDb()
        val a = env(listOf(1, 2), spec = EmbeddingSpec(8, "A"), db = db)
        a.indexer.index().run()
        val b = env(listOf(1, 2), spec = EmbeddingSpec(8, "B"), db = db, source = a.source)
        b.indexer.index().run()
        assertEquals(2, b.encoder.encoded.size)
        b.encoder.encoded.clear()
        b.indexer.index().run()
        assertTrue(b.encoder.encoded.isEmpty())
        // Eski modele dönmek de yeniden işler (vektörler artık B'ye ait).
        val back = env(listOf(1, 2), spec = EmbeddingSpec(8, "A"), db = db, source = a.source)
        back.indexer.index().run()
        assertEquals(2, back.encoder.encoded.size)
    }

    // ================= bozuk / silinmiş fotoğraf, kaynak hataları =================

    @Test
    fun deletedPhotoAfterScan_inFull_removesExistingRecord_andCountsProcessed() = runTest {
        val e = env((1L..4L).toList())
        e.indexer.index().run()
        e.source.vanishedAfterScan += setOf(2L, 3L)
        val out = e.indexer.index(IndexMode.FULL).run().last()
        assertEquals(IndexProgress(IndexPhase.COMPLETED, 4, 4, failed = 0), out)
        assertEquals(setOf(1L, 4L), e.db.photos.keys)
        assertEquals(setOf(1L, 4L), e.db.embeddings.keys)
    }

    @Test
    fun everyPhotoVanishesAfterScan_runCompletes_andRoomIsCleaned() = runTest {
        val e = env((1L..3L).toList())
        e.indexer.index().run()
        e.source.vanishedAfterScan += setOf(1L, 2L, 3L)
        val out = e.indexer.index(IndexMode.FULL).run().last()
        assertEquals(IndexProgress(IndexPhase.COMPLETED, 3, 3), out)
        assertTrue(e.db.photos.isEmpty())
    }

    private class FailingBatchSource(photos: List<MediaPhoto>, val failure: Throwable) : MediaPhotoSource {
        val inner = RecordingSource(photos)
        override suspend fun count() = inner.count()
        override suspend fun loadPhotos() = inner.loadPhotos()
        override suspend fun loadIds() = inner.loadIds()
        override suspend fun loadByIds(ids: List<Long>): List<MediaPhoto> = throw failure
    }

    @Test
    fun loadByIdsSecurityException_isPermissionMissing_otherErrorsUnexpected_andStatePersisted() = runTest {
        val db = FakeIndexDb()
        val photos = (1L..3L).map { mediaPhoto(it) }
        val enc = FakeImageEncoder(spec)
        fun indexer(f: Throwable) = RoomPhotoIndexer(
            FailingBatchSource(photos, f), db.photoDao, db.embeddingDao, db.stateDao, db.transactions, enc,
            clock = { now++ }, dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            indexer(SecurityException("izin")).index().run(); fail()
        } catch (_: IndexException.PermissionMissing) {
        }
        try {
            indexer(IllegalStateException("null cursor")).index().run(); fail()
        } catch (_: IndexException.Unexpected) {
        }
        assertEquals(IndexState(total = 3, processed = 0, lastRunAt = null), db.state)
        assertTrue(enc.encoded.isEmpty())
        assertEquals(2, enc.released)
    }

    @Test
    fun permissionRevokedMidRun_keepsAlreadyWrittenRecords_andNextRunResumes() = runTest {
        val e = env((1L..6L).toList())
        e.encoder.failures[uri(3)] = SecurityException("izin geri alındı")
        try {
            e.indexer.index().run(); fail()
        } catch (_: IndexException.PermissionMissing) {
        }
        assertEquals(setOf(6L, 5L, 4L), e.db.photos.keys)
        assertEquals(IndexState(total = 6, processed = 3, lastRunAt = null), e.db.state)
        e.encoder.failures.clear()
        e.encoder.encoded.clear()
        val out = e.indexer.index().run().last()
        assertEquals(3, out.total)
        assertEquals(6, e.db.photos.size)
    }

    @Test
    fun sourceReturnsPartialBatch_missingPhotosAreCleaned_notFailed() = runTest {
        // loadByIds bazı kimlikleri döndürmez (silinmiş): parça başına yalnızca eksikler temizlenir.
        val e = env((1L..120L).toList())
        e.source.vanishedAfterScan += (1L..120L).filter { it % 3 == 0L }.toSet()
        val out = e.indexer.index().run().last()
        assertEquals(IndexProgress(IndexPhase.COMPLETED, 120, 120, failed = 0), out)
        assertEquals(80, e.db.photos.size)
    }

    // ================= kimlik / URI uç değerleri (Türkçe karakter, null tarih) =================

    @Test
    fun turkishCharactersAndNullDate_inUri_roundTripUnchanged_toEncoderAndRoom() = runTest {
        val uris = listOf(
            "content://media/external/images/media/1?ad=çğıöşü",
            "content://media/external/images/media/2?ad=İSTANBUL%20ıı",
            "content://media/external/images/media/3?ad=I%C4%B1%20ŞĞÜ",
        )
        val photos = uris.mapIndexed { i, u -> MediaPhoto((i + 1).toLong(), u, if (i == 1) null else 1_000L) }
        val e = env(emptyList(), source = RecordingSource(photos))
        e.indexer.index().run()
        assertEquals(uris.reversed(), e.encoder.encoded) // aynen, en yeni kimlik önce
        for (p in photos) assertEquals(p.uri, e.db.photos.getValue(p.id).uri)
        assertNull(e.db.photos.getValue(2).dateTaken)
        assertEquals(1_000L, e.db.photos.getValue(1).dateTaken)
    }

    @Test
    fun extremeIds_zeroAndLongMax_areOrderedAndIndexed() = runTest {
        val ids = listOf(0L, 1L, Long.MAX_VALUE - 1, Long.MAX_VALUE)
        val e = env(ids)
        e.indexer.index().run()
        assertEquals(ids.sortedDescending().map { uri(it) }, e.encoder.encoded)
        assertEquals(ids.toSet(), e.db.photos.keys)
    }

    // ================= hata türleri =================

    @Test
    fun modelOpenFailure_isUnexpected_withCause_andDoesNotCountAsPerPhotoFailure() = runTest {
        val e = env((1L..5L).toList())
        e.encoder.failures[uri(5)] = ModelException.Inference("native-load", UnsatisfiedLinkError("x"))
        try {
            e.indexer.index().run(); fail()
        } catch (ex: IndexException.Unexpected) {
            assertTrue(causes(ex).any { it is ModelException.Inference })
        }
        assertEquals(1, e.encoder.encoded.size) // ilk fotoğrafta durdu, 20 hatayı beklemedi
        assertTrue(e.db.photos.isEmpty())
    }

    @Test
    fun outOfMemoryInEncoder_isNotSwallowed_butSessionIsReleasedAndProgressSaved() = runTest {
        // Davranış belgeleme: OOM (Error) tek fotoğraf hatası sayılmaz, olduğu gibi yayılır; temizlik yine çalışır.
        val e = env((1L..3L).toList())
        e.encoder.failures[uri(2)] = OutOfMemoryError("test")
        try {
            e.indexer.index().run(); fail()
        } catch (_: OutOfMemoryError) {
        }
        assertEquals(setOf(3L), e.db.photos.keys)
        assertEquals(1, e.encoder.released)
        assertEquals(IndexState(total = 3, processed = 1, lastRunAt = null), e.db.state)
    }

    @Test
    fun releaseFailure_doesNotMaskSuccessOrOriginalError() = runTest {
        val db = FakeIndexDb()
        val flaky = object : ImageEncoder {
            override val embeddingSpec = spec
            var fail = false
            override suspend fun encode(uri: String): FloatArray {
                if (fail) throw ModelException.Missing("models/x")
                return FloatArray(8).also { it[0] = 1f }
            }
            override suspend fun release() { throw IllegalStateException("kapatılamadı") }
        }
        val photos = (1L..2L).map { mediaPhoto(it) }
        fun ix() = RoomPhotoIndexer(
            RecordingSource(photos), db.photoDao, db.embeddingDao, db.stateDao, db.transactions, flaky,
            clock = { now++ }, dispatcher = StandardTestDispatcher(testScheduler),
        )
        assertEquals(IndexPhase.COMPLETED, ix().index().run().last().phase)
        flaky.fail = true
        db.photos.clear(); db.embeddings.clear()
        try {
            ix().index().run(); fail()
        } catch (ex: IndexException.Unexpected) {
            assertTrue(causes(ex).any { it is ModelException.Missing }) // asıl hata release hatasıyla örtülmedi
        }
    }
}
