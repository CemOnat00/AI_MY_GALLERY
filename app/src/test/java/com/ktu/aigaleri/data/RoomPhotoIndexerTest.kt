package com.ktu.aigaleri.data

import com.ktu.aigaleri.domain.EmbeddingSpec
import com.ktu.aigaleri.domain.IndexException
import com.ktu.aigaleri.domain.IndexMode
import com.ktu.aigaleri.domain.IndexPhase
import com.ktu.aigaleri.domain.IndexProgress
import com.ktu.aigaleri.ml.ImageDecodeException
import com.ktu.aigaleri.ml.ModelException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** T-006: PhotoIndexer davranışı; sahte kodlayıcı, sahte MediaStore ve bellek içi DAO'larla (model/cihaz yok). */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomPhotoIndexerTest {
    private val spec = EmbeddingSpec(8, "test-model-v1")
    private var now = 10_000L

    private class Env(
        val db: FakeIndexDb,
        val source: RecordingSource,
        val encoder: FakeImageEncoder,
        val indexer: RoomPhotoIndexer,
    )

    private fun TestScope.env(
        ids: List<Long>,
        spec: EmbeddingSpec = this@RoomPhotoIndexerTest.spec,
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

    /** coroutine yığın izi kurtarma (testlerde açık) istisnayı sarabilir; neden zincirinin tamamına bakılır. */
    private fun causes(t: Throwable) = generateSequence<Throwable>(t) { it.cause }.take(8).toList()

    private fun uri(id: Long) = mediaPhoto(id).uri

    private suspend fun Flow<IndexProgress>.run() = toList()

    /**
     * Toplama, [n]. fotoğraf kodlanınca iptal edilir (iş parçacığı sırasından bağımsız, deterministik): n. fotoğraf
     * tamamen yazılır, n+1. başlamadan iş durur.
     */
    private suspend fun TestScope.collectCancelledAfterEncodes(e: Env, mode: IndexMode, n: Int) {
        var count = 0
        lateinit var job: kotlinx.coroutines.Job
        e.encoder.onEncode = { if (++count == n) job.cancel() }
        job = launch { e.indexer.index(mode).collect { } }
        job.join()
        e.encoder.onEncode = {}
    }

    // ---- akış sırası ve temel INCREMENTAL ----

    @Test
    fun incremental_onEmptyIndex_indexesEverything_inContractOrder() = runTest {
        val e = env(listOf(1, 2, 3))
        val out = e.indexer.index(IndexMode.INCREMENTAL).run()
        assertEquals(
            listOf(
                IndexProgress(IndexPhase.SCANNING, 0, 0),
                IndexProgress(IndexPhase.INDEXING, 3, 1),
                IndexProgress(IndexPhase.INDEXING, 3, 2),
                IndexProgress(IndexPhase.INDEXING, 3, 3),
                IndexProgress(IndexPhase.COMPLETED, 3, 3),
            ),
            out,
        )
        // En yeni (büyük) kimlik önce.
        assertEquals(listOf(uri(3), uri(2), uri(1)), e.encoder.encoded)
        for (id in 1L..3L) {
            val p = e.db.photos.getValue(id)
            assertEquals(uri(id), p.uri)
            assertEquals(1_000L + id, p.dateTaken)
            assertEquals(RoomPhotoIndexer.PIPELINE_VERSION, p.indexVersion)
            val emb = e.db.embeddings.getValue(id)
            assertEquals(spec.modelVersion, emb.modelVersion)
            assertEquals(spec.dimension, VectorCodec.decode(emb.vector).size)
        }
        assertEquals(3, e.db.state!!.total)
        assertEquals(3, e.db.state!!.processed)
        assertTrue(e.db.state!!.lastRunAt != null)
        assertEquals(1, e.encoder.released)
    }

    @Test
    fun emptyGallery_emitsScanningAndCompleted_only() = runTest {
        val e = env(emptyList())
        val out = e.indexer.index().run()
        assertEquals(listOf(IndexProgress(IndexPhase.SCANNING, 0, 0), IndexProgress(IndexPhase.COMPLETED, 0, 0)), out)
        assertEquals(IndexState(total = 0, processed = 0, lastRunAt = e.db.state!!.lastRunAt), e.db.state)
        assertTrue(e.db.state!!.lastRunAt != null)
    }

    @Test
    fun flowIsCold_nothingHappensUntilCollected_andSpecIsEncoders() = runTest {
        val e = env(listOf(1))
        val flow = e.indexer.index()
        assertSame(spec, e.indexer.embeddingSpec)
        assertTrue(e.encoder.encoded.isEmpty())
        assertTrue(e.db.photos.isEmpty())
        flow.run()
        flow.run() // yeniden toplanabilir (soğuk)
        assertEquals(1, e.encoder.encoded.size) // ikincisinde zaten güncel
    }

    @Test
    fun incremental_sameModelAndVersion_doesNotReprocess() = runTest {
        val e = env(listOf(1, 2, 3))
        e.indexer.index().run()
        val before = e.db.embeddings.toMap()
        e.encoder.encoded.clear()
        val out = e.indexer.index().run()
        assertEquals(listOf(IndexPhase.SCANNING, IndexPhase.COMPLETED), out.map { it.phase })
        assertTrue(e.encoder.encoded.isEmpty())
        assertEquals(before, e.db.embeddings)
    }

    @Test
    fun incremental_processesOnlyNewPhotos_withSameIndexVersion() = runTest {
        val e = env(listOf(1, 2))
        e.indexer.index().run()
        e.source.gallery = listOf(1L, 2L, 5L, 4L).map { mediaPhoto(it) }
        e.encoder.encoded.clear()
        val out = e.indexer.index().run()
        assertEquals(listOf(uri(5), uri(4)), e.encoder.encoded)
        assertEquals(2, out.last().total)
        assertEquals(1, e.db.photos.getValue(4).indexVersion)
        assertEquals(4, e.db.photos.size)
    }

    @Test
    fun incremental_reprocessesOldModelVersion_replacingVectorInPlace() = runTest {
        val db = FakeIndexDb()
        val old = env(listOf(1, 2), spec = EmbeddingSpec(8, "old-model"), db = db)
        old.indexer.index().run()
        val e = env(listOf(1, 2), db = db, source = old.source)
        val out = e.indexer.index().run()
        assertEquals(2, out.last().total)
        assertEquals(2, e.encoder.encoded.size)
        assertTrue(db.embeddings.values.all { it.modelVersion == spec.modelVersion })
        assertEquals(2, db.embeddings.size)
    }

    @Test
    fun incremental_embeddingMissingForExistingPhoto_isFilledIn() = runTest {
        val e = env(listOf(1, 2))
        e.indexer.index().run()
        e.db.embeddings.remove(2L)
        e.encoder.encoded.clear()
        e.indexer.index().run()
        assertEquals(listOf(uri(2)), e.encoder.encoded)
        assertTrue(2L in e.db.embeddings)
    }

    @Test
    fun incremental_pipelineVersionBump_isReflectedInRecords() = runTest {
        val e = env(listOf(1))
        e.db.photos[1] = mediaPhoto(1).toPhoto(1L, indexVersion = 0) // eski hat sürümü
        e.db.embeddings[1] = PhotoEmbedding(1, VectorCodec.encode(FloatArray(8) { 1f }), spec.modelVersion)
        e.indexer.index().run()
        assertEquals(listOf(uri(1)), e.encoder.encoded) // indexVersion 0 < hedef 1
        assertEquals(RoomPhotoIndexer.PIPELINE_VERSION, e.db.photos.getValue(1).indexVersion)
    }

    // ---- FULL ----

    @Test
    fun full_reprocessesEverything_andRaisesIndexVersion() = runTest {
        val e = env(listOf(1, 2, 3))
        e.indexer.index().run()
        e.encoder.encoded.clear()
        val out = e.indexer.index(IndexMode.FULL).run()
        assertEquals(3, out.last().total)
        assertEquals(3, e.encoder.encoded.size)
        assertTrue(e.db.photos.values.all { it.indexVersion == 2 })
        // Hedefe ulaşılmış: ardından INCREMENTAL işlenecek bir şey bulmaz.
        e.encoder.encoded.clear()
        e.indexer.index().run()
        assertTrue(e.encoder.encoded.isEmpty())
    }

    @Test
    fun full_doesNotDeleteFirst_oldRecordsStayUntilReplaced() = runTest {
        val e = env(listOf(1, 2, 3))
        e.indexer.index().run()
        val oldBlob = e.db.embeddings.getValue(1).vector.copyOf()
        val seenDuringEncode = mutableListOf<Int>()
        e.encoder.onEncode = { seenDuringEncode += e.db.embeddings.size }
        e.indexer.index(IndexMode.FULL).run()
        assertEquals(listOf(3, 3, 3), seenDuringEncode) // hiçbir anda silinmedi
        assertEquals(3, e.db.embeddings.size)
        assertEquals(2, e.db.photos.getValue(1).indexVersion)
        assertEquals(oldBlob.size, e.db.embeddings.getValue(1).vector.size)
    }

    @Test
    fun full_cancelledMidway_keepsOldRecordsSearchable_andIncrementalFinishesRemainder() = runTest {
        val e = env(listOf(1, 2, 3, 4, 5))
        e.indexer.index().run()
        e.encoder.encoded.clear()
        // FULL: 2 fotoğraf işlendikten sonra iptal (take) -> kalan 3 eski kayıtta.
        collectCancelledAfterEncodes(e, IndexMode.FULL, 2)
        assertEquals(5, e.db.embeddings.size)
        assertEquals(5, e.db.photos.size)
        assertEquals(setOf(5L, 4L), e.db.photos.values.filter { it.indexVersion == 2 }.map { it.mediaStoreId }.toSet())
        assertEquals(setOf(1L, 2L, 3L), e.db.photos.values.filter { it.indexVersion == 1 }.map { it.mediaStoreId }.toSet())
        assertTrue(e.db.embeddings.values.all { it.modelVersion == spec.modelVersion }) // aranabilir kalır
        assertEquals(2, e.encoder.released) // ilk tam çalıştırma + iptal edilen FULL: iptalde de oturum bırakıldı
        // İlerleme: iptalde de yazıldı.
        assertEquals(
            IndexState(total = 5, processed = 2, lastRunAt = e.db.state!!.lastRunAt, fullTargetVersion = 2),
            e.db.state,
        )

        // INCREMENTAL yalnızca kalan 3'ü işler ve hedefe (2) yükseltir.
        e.encoder.encoded.clear()
        val out = e.indexer.index().run()
        assertEquals(3, out.last().total)
        assertEquals(listOf(uri(3), uri(2), uri(1)), e.encoder.encoded)
        assertTrue(e.db.photos.values.all { it.indexVersion == 2 })
    }

    // ---- iptal ve devam (INCREMENTAL) ----

    @Test
    fun incremental_cancelledMidway_resumesWithoutRedoingFinishedPhotos() = runTest {
        val e = env(listOf(1, 2, 3, 4, 5, 6))
        collectCancelledAfterEncodes(e, IndexMode.INCREMENTAL, 3)
        assertEquals(setOf(6L, 5L, 4L), e.db.photos.keys)
        e.encoder.encoded.clear()
        val out = e.indexer.index().run()
        assertEquals(3, out.last().total)
        assertEquals(listOf(uri(3), uri(2), uri(1)), e.encoder.encoded)
        assertEquals(6, e.db.photos.size)
        assertEquals(6, e.db.embeddings.size)
    }

    @Test
    fun encoderCancellation_propagates_andIsNotCountedAsFailure() = runTest {
        val e = env(listOf(1, 2))
        e.encoder.failures[uri(2)] = CancellationException("iptal")
        try {
            e.indexer.index().run()
            fail()
        } catch (_: CancellationException) {
        }
        assertEquals(1, e.encoder.released)
        assertFalse(2L in e.db.photos)
    }

    // ---- silinmiş fotoğraf temizliği ----

    @Test
    fun scan_removesRecordsOfPhotosNoLongerInMediaStore_andTheirEmbeddings() = runTest {
        val e = env(listOf(1, 2, 3))
        e.indexer.index().run()
        e.source.gallery = listOf(mediaPhoto(2))
        val out = e.indexer.index().run()
        assertEquals(setOf(2L), e.db.photos.keys)
        assertEquals(setOf(2L), e.db.embeddings.keys)
        assertEquals(listOf(IndexPhase.SCANNING, IndexPhase.COMPLETED), out.map { it.phase })
    }

    @Test
    fun scan_bulkDelete_isChunked_andCleansAll() = runTest {
        val db = FakeIndexDb()
        val n = 1_234L
        for (id in 1..n) db.photos[id] = mediaPhoto(id).toPhoto(1, 1)
        val e = env(listOf(5_000L), db = db) // galeri boş OLMAMALI (boş galeride silme yapılmaz)
        e.indexer.index().run()
        assertEquals(setOf(5_000L), db.photos.keys)
        assertEquals(3, db.deleteByIdsCalls.size)
        assertTrue(db.deleteByIdsCalls.all { it.size <= RoomPhotoIndexer.DELETE_CHUNK })
        assertEquals(n, db.deleteByIdsCalls.sumOf { it.size }.toLong())
    }

    @Test
    fun staleDeletion_doesNotRaiseTargetVersion() = runTest {
        // En yüksek sürümlü kayıt galeriden silindiyse hedef onunla yükselmez (gereksiz yeniden işleme yok).
        val e = env(listOf(1, 2))
        e.indexer.index().run()
        e.db.photos[9] = mediaPhoto(9).toPhoto(1, indexVersion = 7)
        e.encoder.encoded.clear()
        e.indexer.index().run()
        assertTrue(e.encoder.encoded.isEmpty())
        assertFalse(9L in e.db.photos)
    }

    @Test
    fun photoVanishingAfterScan_isCleanedUp_countedProcessed_notFailed() = runTest {
        val e = env(listOf(1, 2, 3))
        e.source.vanishedAfterScan += 2L
        val out = e.indexer.index().run()
        assertEquals(IndexProgress(IndexPhase.COMPLETED, 3, 3, failed = 0), out.last())
        assertEquals(setOf(1L, 3L), e.db.photos.keys)
    }

    // ---- tek fotoğraf hatası ----

    @Test
    fun singlePhotoFailure_isSkipped_countsFailed_andContinues() = runTest {
        val e = env(listOf(1, 2, 3, 4))
        e.encoder.failures[uri(3)] = ImageDecodeException("bozuk")
        e.encoder.failures[uri(1)] = ModelException.Inference("run", RuntimeException("ort"))
        e.encoder.failures[uri(4)] = java.io.FileNotFoundException("dosya")
        val out = e.indexer.index().run()
        assertEquals(IndexProgress(IndexPhase.COMPLETED, 4, 4, failed = 3), out.last())
        // INDEXING sırası 4,3,2,1: hata, hata, başarı, hata -> failed 1,2,2,3
        assertEquals(listOf(1, 2, 2, 3), out.filter { it.phase == IndexPhase.INDEXING }.map { it.failed })
        assertEquals(setOf(2L), e.db.photos.keys) // başarısızlar kalıcı yazılmadı
    }

    @Test
    fun failedPhotos_areRetriedOnNextIncremental_andFailedCountIsPerRun() = runTest {
        val e = env(listOf(1, 2))
        e.encoder.failures[uri(2)] = ImageDecodeException("bozuk")
        assertEquals(1, e.indexer.index().run().last().failed)
        e.encoder.encoded.clear()
        val again = e.indexer.index().run().last() // hâlâ bozuk: yeniden denenir
        assertEquals(listOf(uri(2)), e.encoder.encoded)
        assertEquals(IndexProgress(IndexPhase.COMPLETED, 1, 1, failed = 1), again)
        e.encoder.failures.clear()
        e.encoder.encoded.clear()
        val fixed = e.indexer.index().run().last()
        assertEquals(IndexProgress(IndexPhase.COMPLETED, 1, 1, failed = 0), fixed)
        assertEquals(setOf(1L, 2L), e.db.photos.keys)
    }

    @Test
    fun full_failedPhoto_keepsOldRecord_andIncrementalRetriesIt() = runTest {
        val e = env(listOf(1, 2, 3))
        e.indexer.index().run()
        val oldBlob = e.db.embeddings.getValue(2).vector.copyOf()
        e.encoder.failures[uri(2)] = ImageDecodeException("bozuk")
        e.encoder.encoded.clear()
        val out = e.indexer.index(IndexMode.FULL).run()
        assertEquals(1, out.last().failed)
        assertEquals(1, e.db.photos.getValue(2).indexVersion) // eski sürüm
        assertArrayEquals(oldBlob, e.db.embeddings.getValue(2).vector) // eski vektör aranabilir
        assertEquals(2, e.db.photos.getValue(1).indexVersion)
        e.encoder.failures.clear()
        e.encoder.encoded.clear()
        e.indexer.index().run()
        assertEquals(listOf(uri(2)), e.encoder.encoded)
        assertEquals(2, e.db.photos.getValue(2).indexVersion)
    }

    // ---- fotoğraftan bağımsız hatalar ----

    @Test
    fun modelLevelFailure_endsFlowWithUnexpected_andReleasesAndPersistsProgress() = runTest {
        val e = env(listOf(1, 2, 3))
        e.encoder.failures[uri(2)] = ModelException.Missing("models/vision_model_quantized.onnx")
        try {
            e.indexer.index().run()
            fail()
        } catch (ex: IndexException.Unexpected) {
            assertTrue(causes(ex).any { it is ModelException.Missing })
        }
        assertEquals(setOf(3L), e.db.photos.keys)
        assertEquals(1, e.encoder.released)
        assertEquals(IndexState(total = 3, processed = 1, lastRunAt = null), e.db.state)
    }

    @Test
    fun modelOpenFailure_isFatal_butRunFailureIsPerPhoto() = runTest {
        val a = env(listOf(1, 2))
        a.encoder.failures[uri(2)] = ModelException.Inference("open", RuntimeException())
        try {
            a.indexer.index().run()
            fail()
        } catch (_: IndexException.Unexpected) {
        }
    }

    @Test
    fun permissionMissing_whenScanThrowsSecurityException() = runTest {
        val e = env(listOf(1))
        e.source.idsFailure = SecurityException("izin")
        try {
            e.indexer.index().run()
            fail()
        } catch (_: IndexException.PermissionMissing) {
        }
        assertTrue(e.db.photos.isEmpty())
    }

    @Test
    fun scanOrDatabaseFailure_becomesUnexpected_withCause() = runTest {
        val a = env(listOf(1))
        a.source.idsFailure = IllegalStateException("null cursor")
        try {
            a.indexer.index().run()
            fail()
        } catch (ex: IndexException.Unexpected) {
            assertTrue(causes(ex).any { it is IllegalStateException && it.message == "null cursor" })
        }
        val b = env(listOf(1))
        b.db.failAllIdsWith = RuntimeException("disk")
        try {
            b.indexer.index().run()
            fail()
        } catch (_: IndexException.Unexpected) {
        }
    }

    @Test
    fun photoAndEmbeddingWrite_isAtomic() = runTest {
        val e = env(listOf(1, 2))
        e.db.failEmbeddingUpsertWith = IllegalStateException("disk dolu")
        try {
            e.indexer.index().run()
            fail()
        } catch (_: IndexException.Unexpected) {
        }
        // Photo yazıldı ama embedding yazılamadı: işlem geri alındı, yetim Photo yok.
        assertTrue(e.db.photos.isEmpty())
        assertTrue(e.db.embeddings.isEmpty())
    }

    @Test
    fun wrongVectorSize_isUnexpected_notSilentlyWritten() = runTest {
        val e = env(listOf(1))
        e.encoder.vectorSize = 4
        try {
            e.indexer.index().run()
            fail()
        } catch (_: IndexException.Unexpected) {
        }
        assertTrue(e.db.photos.isEmpty())
    }

    // ---- bellek/parça kullanımı ve durum yazımı ----

    @Test
    fun usesIdsAndSmallBatches_neverLoadsWholeList() = runTest {
        val ids = (1L..120L).toList()
        val e = env(ids)
        e.indexer.index().run()
        assertEquals(0, e.source.loadPhotosCalls)
        assertEquals(listOf(50, 50, 20), e.source.loadByIdsSizes)
        assertEquals(120, e.db.photos.size)
    }

    @Test
    fun indexState_isWrittenInIntervals_notPerPhoto() = runTest {
        val e = env((1L..25L).toList())
        e.indexer.index().run()
        // başlangıç (0), 10, 20, bitiş (25)
        assertEquals(listOf(0, 10, 20, 25), e.db.stateWrites.map { it.processed })
        assertTrue(e.db.stateWrites.all { it.total == 25 })
        assertNull(e.db.stateWrites.first().lastRunAt)
        assertTrue(e.db.stateWrites.last().lastRunAt != null)
    }

    @Test
    fun interruptedRun_doesNotUpdateLastRunAt() = runTest {
        val e = env(listOf(1, 2, 3))
        e.indexer.index().run()
        val firstRun = e.db.state!!.lastRunAt
        e.source.gallery = (1L..6L).map { mediaPhoto(it) }
        collectCancelledAfterEncodes(e, IndexMode.INCREMENTAL, 1)
        assertEquals(firstRun, e.db.state!!.lastRunAt)
        assertEquals(3, e.db.state!!.total)
        assertEquals(1, e.db.state!!.processed)
    }

    @Test
    fun firstElementIsAlwaysScanning_beforeAnyWork() = runTest {
        val e = env(listOf(1, 2))
        val first = e.indexer.index().first()
        assertEquals(IndexPhase.SCANNING, first.phase)
        assertEquals(0, first.total)
    }

    // ---- FULL kendi başına yeniden başlatılabilir ----

    @Test
    fun full_cancelledDuringScanning_writesNothing_andRetryRunsAsRealFull() = runTest {
        val e = env(listOf(1, 2, 3))
        e.indexer.index().run() // hepsi indexVersion 1
        e.encoder.encoded.clear()
        lateinit var job: kotlinx.coroutines.Job
        e.source.afterLoadIds = { job.cancel() }
        job = launch { e.indexer.index(IndexMode.FULL).collect { } }
        job.join()
        e.source.afterLoadIds = {}
        assertTrue(e.encoder.encoded.isEmpty()) // sıfır yazım
        assertTrue(e.db.photos.values.all { it.indexVersion == 1 })

        val out = e.indexer.index(IndexMode.FULL).run() // gerçekten FULL çalışır
        assertEquals(3, out.last().total)
        assertEquals(3, e.encoder.encoded.size)
        assertTrue(e.db.photos.values.all { it.indexVersion == 2 })
    }

    @Test
    fun full_afterPartialFull_resumesWithSameTarget_thenNextFullStartsNewRound() = runTest {
        val e = env(listOf(1, 2, 3, 4, 5))
        e.indexer.index().run()
        collectCancelledAfterEncodes(e, IndexMode.FULL, 2) // 5 ve 4 -> sürüm 2
        e.encoder.encoded.clear()

        val resumed = e.indexer.index(IndexMode.FULL).run()
        assertEquals(3, resumed.last().total) // yalnızca kalan: baştan başlamaz
        assertEquals(listOf(uri(3), uri(2), uri(1)), e.encoder.encoded)
        assertTrue(e.db.photos.values.all { it.indexVersion == 2 }) // aynı hedef: max+1 değil

        e.encoder.encoded.clear()
        val next = e.indexer.index(IndexMode.FULL).run() // tamamlanmış: yeni tur
        assertEquals(5, next.last().total)
        assertEquals(5, e.encoder.encoded.size)
        assertTrue(e.db.photos.values.all { it.indexVersion == 3 })
    }

    @Test
    fun full_onEmptyIndex_startsNewRound_andNewPhotosAfterCompletedFullGetNewRoundToo() = runTest {
        val e = env(listOf(1, 2))
        e.indexer.index(IndexMode.FULL).run()
        assertTrue(e.db.photos.values.all { it.indexVersion == 2 }) // boş indekste max=1 -> yeni tur 2
        e.source.gallery = listOf(1L, 2L, 3L).map { mediaPhoto(it) }
        e.encoder.encoded.clear()
        // Room'da kalan yok (yeni fotoğraf tek başına kalan sayılmaz): FULL yeni tur, hedef 3, hepsi işlenir.
        val out = e.indexer.index(IndexMode.FULL).run()
        assertEquals(3, out.last().total)
        assertTrue(e.db.photos.values.all { it.indexVersion == 3 })
    }

    @Test
    fun full_resumingPartialFull_alsoIndexesNewlyAddedPhotos() = runTest {
        val e = env(listOf(1, 2, 3))
        e.indexer.index().run()
        collectCancelledAfterEncodes(e, IndexMode.FULL, 1) // 3 -> sürüm 2; 2,1 kalan
        e.source.gallery = (1L..4L).map { mediaPhoto(it) }
        e.encoder.encoded.clear()
        val out = e.indexer.index(IndexMode.FULL).run()
        assertEquals(3, out.last().total) // kalan 2,1 + yeni 4
        assertEquals(setOf(uri(4), uri(2), uri(1)), e.encoder.encoded.toSet())
        assertTrue(e.db.photos.values.all { it.indexVersion == 2 })
    }

    // ---- izin ve ardışık hata ----

    @Test
    fun securityExceptionWhileReadingPhoto_isPermissionMissing_notPerPhotoFailure() = runTest {
        val e = env(listOf(1, 2, 3))
        e.encoder.failures[uri(2)] = SecurityException("izin geri alındı")
        try {
            e.indexer.index().run()
            fail()
        } catch (_: IndexException.PermissionMissing) {
        }
        assertEquals(setOf(3L), e.db.photos.keys) // kesilmeden önceki yazım korunur
        assertEquals(1, e.encoder.released)
    }

    @Test
    fun consecutiveFailuresWithoutAnySuccess_endFlowWithUnexpected() = runTest {
        val n = RoomPhotoIndexer.MAX_MODEL_FAILURES_WITHOUT_SUCCESS
        val e = env((1L..(n + 5L)).toList())
        for (id in 1L..(n + 5L)) e.encoder.failures[uri(id)] = ModelException.Inference("run", RuntimeException("ort"))
        try {
            e.indexer.index().run()
            fail("Unexpected beklenir")
        } catch (ex: IndexException.Unexpected) {
            assertTrue(causes(ex).any { it is IllegalStateException })
        }
        assertEquals(n, e.encoder.encoded.size) // sınırda kesildi, galeri sessizce bitirilmedi
        assertTrue(e.db.photos.isEmpty())
        assertEquals(1, e.encoder.released)
    }

    @Test
    fun failuresBelowLimit_orAfterFirstSuccess_doNotEndFlow() = runTest {
        val n = RoomPhotoIndexer.MAX_MODEL_FAILURES_WITHOUT_SUCCESS
        // n-1 hata: sınırın altında tamamlanır.
        val a = env((1L..(n - 1L)).toList())
        for (id in 1L until n) a.encoder.failures[uri(id)] = ModelException.Inference("run", RuntimeException())
        assertEquals(n - 1, a.indexer.index().run().last().failed)
        // İlk işlenen (en büyük kimlik) başarılı, sonrasında 2n hata: ilk başarıdan sonra sınır uygulanmaz.
        val total = 2L * n + 1
        val b = env((1L..total).toList())
        for (id in 1L until total) b.encoder.failures[uri(id)] = ModelException.Inference("run", RuntimeException())
        val last = b.indexer.index().run().last()
        assertEquals(IndexPhase.COMPLETED, last.phase)
        assertEquals(2 * n, last.failed)
    }

    // ---- boş MediaStore güvenliği ----

    @Test
    fun emptyMediaStore_withPopulatedDb_deletesAndProcessesNothing() = runTest {
        val e = env(listOf(1, 2, 3))
        e.indexer.index().run()
        e.source.gallery = emptyList() // SecurityException atılmadan boş döndü (ör. kısmi erişim)
        e.encoder.encoded.clear()
        for (mode in IndexMode.entries) {
            val out = e.indexer.index(mode).run()
            assertEquals(listOf(IndexPhase.SCANNING, IndexPhase.COMPLETED), out.map { it.phase })
            assertEquals(setOf(1L, 2L, 3L), e.db.photos.keys)
            assertEquals(setOf(1L, 2L, 3L), e.db.embeddings.keys)
        }
        assertTrue(e.encoder.encoded.isEmpty())
        assertTrue(e.db.deleteByIdsCalls.isEmpty())
    }

    @Test
    fun emptyMediaStore_withEmptyDb_isPlainEmptyRun() = runTest {
        val e = env(emptyList())
        assertEquals(IndexProgress(IndexPhase.COMPLETED, 0, 0), e.indexer.index(IndexMode.FULL).run().last())
    }
}
