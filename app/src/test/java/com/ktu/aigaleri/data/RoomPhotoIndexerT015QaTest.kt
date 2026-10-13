package com.ktu.aigaleri.data

import com.ktu.aigaleri.domain.EmbeddingSpec
import com.ktu.aigaleri.domain.IndexException
import com.ktu.aigaleri.domain.IndexMode
import com.ktu.aigaleri.domain.IndexPhase
import com.ktu.aigaleri.domain.IndexProgress
import com.ktu.aigaleri.ml.ImageDecodeException
import com.ktu.aigaleri.ml.ModelException
import com.ktu.aigaleri.work.IndexOutcome
import com.ktu.aigaleri.work.IndexWork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Ignore
import org.junit.Test

/**
 * T-015 (QA): FULL tur işareti (`IndexState.fullTargetVersion`) ve OutOfMemoryError eşlemesi için ek sınır durumları.
 * Mevcut testlerin (RoomPhotoIndexerEdgeCaseTest "T-015" bölümü, IndexWorkTest) kapsamadığı durumlar: ardışık FULL,
 * FULL iptal / INCREMENTAL / FULL dizileri, işaret=null iken boş MediaStore, boş galeri ve bayat işaret, OOM'nin her
 * `guarded` noktasında Unexpected olması, iptalin OOM tarafından yutulmaması, hata sonrası lastRunAt, worker
 * RETRY/FAILURE sınırı. Sahte kaynak/kodlayıcı/bellek içi DAO; cihaz ve model yok.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomPhotoIndexerT015QaTest {
    private val spec = EmbeddingSpec(8, "test-model-v1")
    private var now = 70_000L

    /** Hata enjeksiyonu: [site] adlı noktada OutOfMemoryError (ya da [custom]) fırlatılır. */
    private class Faults {
        var site: String? = null
        var custom: Throwable? = null
        fun hit(name: String) {
            if (site == name) throw (custom ?: OutOfMemoryError("test:$name"))
        }
        fun clear() { site = null; custom = null }
    }

    private class Env(
        val db: FakeIndexDb,
        val source: RecordingSource,
        val encoder: FakeImageEncoder,
        val faults: Faults,
        val indexer: RoomPhotoIndexer,
    )

    private fun TestScope.envOf(photos: List<MediaPhoto>): Env {
        val db = FakeIndexDb()
        val faults = Faults()
        val rec = RecordingSource(photos)
        val source = object : MediaPhotoSource by rec {
            override suspend fun loadIds(): LongArray { faults.hit("loadIds"); return rec.loadIds() }
            override suspend fun loadByIds(ids: List<Long>): List<MediaPhoto> { faults.hit("loadByIds"); return rec.loadByIds(ids) }
        }
        val photoDao = object : PhotoDao by db.photoDao {
            override suspend fun allIds(): List<Long> { faults.hit("allIds"); return db.photoDao.allIds() }
            override suspend fun deleteByIds(ids: List<Long>) { faults.hit("deleteByIds"); db.photoDao.deleteByIds(ids) }
            override suspend fun maxIndexVersion(): Int? { faults.hit("maxIndexVersion"); return db.photoDao.maxIndexVersion() }
            override suspend fun idsNeedingIndex(targetVersion: Int, modelVersion: String): List<Long> {
                faults.hit("idsNeedingIndex"); return db.photoDao.idsNeedingIndex(targetVersion, modelVersion)
            }
            override suspend fun upsert(photo: Photo) { faults.hit("photoUpsert"); db.photoDao.upsert(photo) }
            override suspend fun deleteById(mediaStoreId: Long) { faults.hit("deleteById"); db.photoDao.deleteById(mediaStoreId) }
        }
        val embeddingDao = object : PhotoEmbeddingDao by db.embeddingDao {
            override suspend fun upsert(embedding: PhotoEmbedding) { faults.hit("embeddingUpsert"); db.embeddingDao.upsert(embedding) }
        }
        val stateDao = object : IndexStateDao by db.stateDao {
            override suspend fun get(id: Int): IndexState? { faults.hit("stateGet"); return db.stateDao.get(id) }
            override suspend fun upsert(state: IndexState) { faults.hit("stateUpsert"); db.stateDao.upsert(state) }
        }
        val tx = object : TransactionRunner {
            override suspend fun <T> run(block: suspend () -> T): T { faults.hit("transaction"); return db.transactions.run(block) }
        }
        val encoder = FakeImageEncoder(spec)
        encoder.onEncode = { faults.hit("encoder") }
        val indexer = RoomPhotoIndexer(
            source, photoDao, embeddingDao, stateDao, tx, encoder,
            clock = { now++ },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        return Env(db, rec, encoder, faults, indexer)
    }

    private fun TestScope.env(ids: List<Long>) = envOf(ids.map { mediaPhoto(it) })

    private suspend fun Flow<IndexProgress>.run() = toList()

    private fun causes(t: Throwable) = generateSequence<Throwable>(t) { it.cause }.take(8).toList()

    /** n. kodlamadan sonra iptal: n. fotoğraf yazılır, n+1. başlamaz. Hata hook'unu korur. */
    private suspend fun TestScope.cancelAfterEncodes(e: Env, mode: IndexMode, n: Int) {
        var count = 0
        lateinit var job: Job
        val old = e.encoder.onEncode
        e.encoder.onEncode = { u -> old(u); if (++count == n) job.cancel() }
        job = launch { e.indexer.index(mode).collect { } }
        job.join()
        e.encoder.onEncode = old
    }

    private fun versionSet(e: Env) = e.db.photos.values.map { it.indexVersion }.toSet()

    private suspend fun expectUnexpectedOom(block: suspend () -> Unit): IndexException.Unexpected {
        try {
            block()
        } catch (x: IndexException.Unexpected) {
            return x
        }
        fail("IndexException.Unexpected beklenirdi")
        throw AssertionError()
    }

    // ================= kriter 2: ardışık / karışık FULL-INCREMENTAL dizileri =================

    @Test
    fun consecutiveCleanFulls_eachOpensNewRound_markerNullBetween_andEncodesWholeGalleryEachTime() = runTest {
        val e = env((1L..5L).toList())
        e.indexer.index().run()
        for (round in 2..4) {
            e.encoder.encoded.clear()
            val out = e.indexer.index(IndexMode.FULL).run().last()
            assertEquals(IndexProgress(IndexPhase.COMPLETED, 5, 5, 0), out)
            assertEquals(5, e.encoder.encoded.size)
            assertEquals(setOf(round), versionSet(e))
            assertNull(e.db.state!!.fullTargetVersion)
        }
    }

    @Test
    fun fullCancelled_incrementalClean_fullCancelled_fullResumesSecondRoundTarget_notThird() = runTest {
        val e = env((1L..6L).toList())
        e.indexer.index().run()
        cancelAfterEncodes(e, IndexMode.FULL, 2) // tur 1: hedef 2
        assertEquals(2, e.db.state!!.fullTargetVersion)
        e.indexer.index().run() // INCREMENTAL kesintisiz: tur 1 kapanır
        assertNull(e.db.state!!.fullTargetVersion)
        assertEquals(setOf(2), versionSet(e))

        cancelAfterEncodes(e, IndexMode.FULL, 1) // tur 2: hedef 3, bir fotoğraf yazıldı
        assertEquals(3, e.db.state!!.fullTargetVersion)
        assertEquals(1, e.db.photos.values.count { it.indexVersion == 3 })

        e.encoder.encoded.clear()
        assertEquals(5, e.indexer.index(IndexMode.FULL).run().last().total) // yalnızca kalan; yeni tur (4) açılmaz
        assertEquals(setOf(3), versionSet(e))
        assertNull(e.db.state!!.fullTargetVersion)

        e.encoder.encoded.clear()
        assertEquals(6, e.indexer.index(IndexMode.FULL).run().last().total) // şimdi gerçekten yeni tur
        assertEquals(setOf(4), versionSet(e))
    }

    @Test
    fun fullCancelled_incrementalWithFailure_keepsMarker_nextCleanIncrementalClosesIt_thenFullIsNewRound() = runTest {
        val e = env((1L..4L).toList())
        e.indexer.index().run()
        cancelAfterEncodes(e, IndexMode.FULL, 1) // hedef 2; #4 yazıldı
        // Model hatası (eşiğin altında) da başarısız fotoğraftır: işaret kalır.
        e.encoder.failures[mediaPhoto(1).uri] = ModelException.Inference("run", RuntimeException("ort"))
        val withFailure = e.indexer.index().run().last()
        assertEquals(1, withFailure.failed)
        assertEquals(2, e.db.state!!.fullTargetVersion)

        e.encoder.failures.clear()
        val clean = e.indexer.index().run().last()
        assertEquals(IndexProgress(IndexPhase.COMPLETED, 1, 1, 0), clean) // yalnızca #1 kaldı
        assertNull(e.db.state!!.fullTargetVersion)

        e.encoder.encoded.clear()
        assertEquals(4, e.indexer.index(IndexMode.FULL).run().last().total)
        assertEquals(setOf(3), versionSet(e))
    }

    @Test
    fun incrementalWithPhotoVanishedAfterScan_isNotFailure_soItClosesTheRound() = runTest {
        val e = env((1L..4L).toList())
        e.indexer.index().run()
        cancelAfterEncodes(e, IndexMode.FULL, 1)
        assertEquals(2, e.db.state!!.fullTargetVersion)
        e.source.vanishedAfterScan += 2L // tarama sonrası silindi: hata sayılmaz
        val out = e.indexer.index().run().last()
        assertEquals(0, out.failed)
        assertNull(e.db.state!!.fullTargetVersion)
        assertFalse(2L in e.db.photos)
    }

    @Test
    fun staleMarker_withNoRemainder_incrementalClosesIt_andFullOpensNewRound() = runTest {
        val e = env((1L..3L).toList())
        e.indexer.index().run()
        e.indexer.index(IndexMode.FULL).run() // tüm kayıtlar v2, işaret null
        // Kalıcı durum bozulması/yarış taklidi: işaret kalmış ama kalan yok.
        e.db.state = e.db.state!!.copy(fullTargetVersion = 2)
        e.encoder.encoded.clear()
        assertEquals(IndexProgress(IndexPhase.COMPLETED, 0, 0, 0), e.indexer.index().run().last())
        assertTrue(e.encoder.encoded.isEmpty())
        assertNull(e.db.state!!.fullTargetVersion)

        e.db.state = e.db.state!!.copy(fullTargetVersion = 2)
        assertEquals(3, e.indexer.index(IndexMode.FULL).run().last().total) // kalan yok -> yeni tur
        assertEquals(setOf(3), versionSet(e))
        assertNull(e.db.state!!.fullTargetVersion)
    }

    // ================= kriter 1: boş MediaStore =================

    @Test
    fun markerNull_mediaStoreTransientlyEmpty_staysNull_noDeletes_lastRunAtAdvances_forEveryMode() = runTest {
        val e = env((1L..4L).toList())
        e.indexer.index().run()
        e.source.gallery = emptyList()
        for (mode in IndexMode.entries) {
            val lastBefore = e.db.state!!.lastRunAt!!
            val writesBefore = e.db.stateWrites.size
            val out = e.indexer.index(mode).run()
            assertEquals("$mode", listOf(IndexPhase.SCANNING, IndexPhase.COMPLETED), out.map { it.phase })
            assertNull("$mode", e.db.state!!.fullTargetVersion)
            assertTrue("$mode", e.db.stateWrites.drop(writesBefore).all { it.fullTargetVersion == null })
            assertEquals("$mode", setOf(1L, 2L, 3L, 4L), e.db.photos.keys)
            assertTrue("$mode", e.db.state!!.lastRunAt!! > lastBefore) // atlama da tamamlanmış sayılır
            assertEquals(setOf(1), versionSet(e)) // FULL turu açılmadı
        }
        assertTrue(e.db.deleteByIdsCalls.isEmpty())
        assertTrue(e.encoder.encoded.size == 4) // yalnızca ilk indeksleme kodladı
    }

    @Test
    fun midFull_repeatedTransientEmpty_markerKeptEveryWrite_andLastRunAtNotErased() = runTest {
        val e = env((1L..4L).toList())
        e.indexer.index().run()
        val firstRunAt = e.db.state!!.lastRunAt
        cancelAfterEncodes(e, IndexMode.FULL, 1)
        val gallery = e.source.gallery
        e.source.gallery = emptyList()
        val writesBefore = e.db.stateWrites.size
        repeat(3) { e.indexer.index(IndexMode.FULL).run() }
        val writes = e.db.stateWrites.drop(writesBefore)
        assertTrue(writes.isNotEmpty())
        assertTrue(writes.all { it.fullTargetVersion == 2 }) // her yazımda işaret yerinde
        assertNotNull(e.db.state!!.lastRunAt)
        assertTrue(e.db.state!!.lastRunAt!! > firstRunAt!!)
        e.source.gallery = gallery
        assertEquals(3, e.indexer.index(IndexMode.FULL).run().last().total)
        assertEquals(setOf(2), versionSet(e))
    }

    @Test
    fun emptyGallery_full_withEmptyDb_clearsStaleMarker_andStaysIdempotent() = runTest {
        // Galeri ve Room boş (kullanıcı her şeyi sildi, kayıtlar zaten temizlendi) ama işaret kalmış: boş-MediaStore
        // güvenlik atlaması değil, normal boş çalıştırma; işaret kalkar.
        for (mode in IndexMode.entries) {
            val e = env(emptyList<Long>())
            e.db.state = IndexState(total = 0, processed = 0, lastRunAt = 5L, fullTargetVersion = 7)
            val out = e.indexer.index(mode).run()
            assertEquals("$mode", IndexProgress(IndexPhase.COMPLETED, 0, 0, 0), out.last())
            assertNull("$mode", e.db.state!!.fullTargetVersion)
            assertTrue("$mode", e.db.state!!.lastRunAt!! > 5L)
            assertNull("$mode", e.indexer.index(IndexMode.FULL).run().let { e.db.state!!.fullTargetVersion })
            assertTrue(e.encoder.encoded.isEmpty())
        }
    }

    @Test
    fun emptyGallery_full_neverOpensRoundThatStaysMarked() = runTest {
        val e = env(emptyList<Long>())
        repeat(3) { e.indexer.index(IndexMode.FULL).run() }
        assertTrue(e.db.stateWrites.all { it.total == 0 })
        assertNull(e.db.state!!.fullTargetVersion)
        // İlk foto geldiğinde FULL normal yeni tur açar.
        e.source.gallery = listOf(mediaPhoto(1))
        e.indexer.index(IndexMode.FULL).run()
        assertEquals(1, e.db.photos.size)
        assertNull(e.db.state!!.fullTargetVersion)
    }

    // ================= çok büyük galeri =================

    @Test
    fun largeGallery_fullCancelled_incrementalClean_closesRound_everyPhotoEncodedOncePerRound() = runTest {
        val n = 2_500
        val e = env((1L..n.toLong()).toList())
        e.indexer.index().run()
        cancelAfterEncodes(e, IndexMode.FULL, 123)
        assertEquals(2, e.db.state!!.fullTargetVersion)
        e.encoder.encoded.clear()
        val out = e.indexer.index().run().last()
        assertEquals(n - 123, out.total)
        assertEquals(0, out.failed)
        assertEquals(setOf(2), versionSet(e))
        assertEquals((n - 123), e.encoder.encoded.toSet().size) // yeniden yok
        assertNull(e.db.state!!.fullTargetVersion)
        assertEquals(n, e.indexer.index(IndexMode.FULL).run().last().total)
    }

    // ================= Türkçe karakterli URI'lerle işaret akışı =================

    @Test
    fun turkishUris_markerFlow_roundTripsAndResumes() = runTest {
        val names = listOf("çğıöşü", "İSTANBUL", "ıI", "ŞĞÜ-iİ", "Işık")
        val photos = names.mapIndexed { i, s ->
            MediaPhoto(i + 1L, "content://media/external/images/media/${i + 1}?ad=$s", if (i == 2) null else 9L)
        }
        val e = envOf(photos)
        e.indexer.index().run()
        cancelAfterEncodes(e, IndexMode.FULL, 2)
        assertEquals(2, e.db.state!!.fullTargetVersion)
        e.indexer.index().run()
        assertNull(e.db.state!!.fullTargetVersion)
        for (p in photos) assertEquals(p.uri, e.db.photos.getValue(p.id).uri)
        assertNull(e.db.photos.getValue(3).dateTaken)
        assertEquals(setOf(2), versionSet(e))
    }

    // ================= kriter 3: OOM her guarded noktasında Unexpected =================

    private val oomSites = listOf(
        "loadIds", "allIds", "stateGet", "deleteByIds", "maxIndexVersion", "idsNeedingIndex",
        "loadByIds", "encoder", "transaction", "photoUpsert", "embeddingUpsert", "deleteById",
    )

    /**
     * Hazırlık: 5 fotoğraf indekslendi (lastRunAt = L), FULL 1 fotoğrafla iptal edildi (işaret 2); galeri değişti
     * (1 silindi, 6 eklendi, 4 tarama sonrası kaybolur -> deleteById yolu). Döner: L.
     */
    private suspend fun TestScope.prepareMidFull(e: Env): Long {
        e.indexer.index().run()
        val lastRunAt = e.db.state!!.lastRunAt!!
        cancelAfterEncodes(e, IndexMode.FULL, 1)
        assertEquals(2, e.db.state!!.fullTargetVersion)
        e.source.gallery = listOf(2L, 3L, 4L, 5L, 6L).map { mediaPhoto(it) }
        e.source.vanishedAfterScan += 3L
        return lastRunAt
    }

    @Test
    fun oomAtEveryGuardedSite_becomesUnexpected_keepsMarkerAndLastRunAt_andRetryResumesSameTarget() = runTest {
        for (mode in IndexMode.entries) for (site in oomSites) {
            val tag = "$mode/$site"
            val e = env((1L..5L).toList())
            val lastRunAt = prepareMidFull(e)
            e.faults.site = site
            val ex = expectUnexpectedOom { e.indexer.index(mode).run() }
            assertTrue(tag, causes(ex).any { it is OutOfMemoryError })
            assertEquals(tag, 2, e.db.state!!.fullTargetVersion) // işaret kaybolmadı
            assertEquals(tag, lastRunAt, e.db.state!!.lastRunAt) // hata yolunda lastRunAt ilerlemez, silinmez
            assertEquals(tag, e.db.photos.keys, e.db.embeddings.keys) // yarım yazım yok (tek işlem)
            assertTrue(tag, e.db.embeddings.keys.containsAll(listOf(5L))) // önceki başarı korunur

            // Bellek rahatladı: yeniden deneme aynı hedefle sürer, yeni tur açmaz.
            e.faults.clear()
            val out = e.indexer.index(mode).run().last()
            assertEquals(tag, IndexPhase.COMPLETED, out.phase)
            assertEquals(tag, 0, out.failed)
            assertEquals(tag, setOf(2), versionSet(e))
            assertNull(tag, e.db.state!!.fullTargetVersion)
            assertTrue(tag, e.db.state!!.lastRunAt!! > lastRunAt)
        }
    }

    @Test
    fun oomInFirstEverRun_leavesLastRunAtNull_andReleasesSessionWhenPlanExists() = runTest {
        val e = env((1L..3L).toList())
        e.faults.site = "encoder"
        expectUnexpectedOom { e.indexer.index().run() }
        assertNull(e.db.state!!.lastRunAt)
        assertEquals(1, e.encoder.released)
        assertEquals(0, e.db.photos.size)
        // Tarama aşamasındaki OOM: oturum açılmadı, durum yazılmadı.
        val e2 = env((1L..3L).toList())
        e2.faults.site = "loadIds"
        expectUnexpectedOom { e2.indexer.index().run() }
        assertNull(e2.db.state)
        assertEquals(0, e2.encoder.encoded.size)
    }

    @Test
    fun oomInStateWrite_isUnexpected_notRawOutOfMemory() = runTest {
        // IndexState yazımı da DAO yazımıdır. Hem asıl yazım hem finally temizliği OOM verirse sızıntı olmamalı.
        for (mode in IndexMode.entries) {
            val e = env((1L..3L).toList())
            e.indexer.index().run()
            e.faults.site = "stateUpsert"
            val ex = try {
                e.indexer.index(mode).run()
                null
            } catch (x: IndexException.Unexpected) {
                x
            } catch (x: OutOfMemoryError) {
                fail("$mode: ham OutOfMemoryError sızdı (IndexException.Unexpected beklenirdi): ${x.message}")
                null
            }
            assertNotNull("$mode", ex)
            assertTrue("$mode", causes(ex!!).any { it is OutOfMemoryError })
        }
    }

    @Test
    fun oomInStateWrite_stillReleasesEncoderSession() = runTest {
        val e = env((1L..3L).toList())
        e.indexer.index().run()
        val releasedBefore = e.encoder.released
        e.faults.site = "stateUpsert"
        try {
            e.indexer.index(IndexMode.FULL).run()
        } catch (_: Throwable) {
        }
        assertEquals(releasedBefore + 1, e.encoder.released)
    }

    @Test
    fun oomInEncoder_isNotCountedAsFailedPhoto_andDoesNotContinueToNextPhotos() = runTest {
        val e = env((1L..6L).toList())
        e.faults.site = "encoder"
        expectUnexpectedOom { e.indexer.index().run() }
        assertEquals(1, e.encoder.encoded.size) // ilk OOM'de durdu, kalan fotoğraflar denenmedi
        assertTrue(e.db.photos.isEmpty())
    }

    // ================= OOM iptali yutmaz =================

    @Test
    fun cancellationAtEverySite_propagatesAsCancellation_evenWithOomCause_andNeverBecomesUnexpected() = runTest {
        for (site in oomSites) {
            val e = env((1L..5L).toList())
            val lastRunAt = prepareMidFull(e)
            e.faults.site = site
            e.faults.custom = CancellationException("iptal", OutOfMemoryError("neden"))
            try {
                e.indexer.index().run()
                fail("$site: CancellationException beklenirdi")
            } catch (x: IndexException) {
                fail("$site: iptal IndexException'a çevrildi: $x")
            } catch (_: CancellationException) {
            }
            assertEquals(site, 2, e.db.state!!.fullTargetVersion)
            assertEquals(site, lastRunAt, e.db.state!!.lastRunAt)
        }
    }

    @Test
    fun cancellationWhileOomIsPending_stateWriteFault_stillCancellation() = runTest {
        val e = env((1L..3L).toList())
        e.indexer.index().run()
        e.faults.site = "stateUpsert"
        e.faults.custom = CancellationException("iptal")
        try {
            e.indexer.index(IndexMode.FULL).run()
            fail()
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun otherErrors_areNotCaught_stackOverflowPropagatesRaw_butSessionIsReleased() = runTest {
        // Sözleşme: yalnızca OutOfMemoryError eşlenir; diğer Error'lar yayılır (belgelenmiş davranış).
        val e = env((1L..3L).toList())
        e.faults.site = "encoder"
        e.faults.custom = StackOverflowError("test")
        try {
            e.indexer.index().run()
            fail()
        } catch (_: StackOverflowError) {
        }
        assertEquals(1, e.encoder.released)
    }

    @Test
    fun decodeFailureStillPerPhoto_whileEncoderOomIsFatal_inSameGallery() = runTest {
        val e = env((1L..4L).toList())
        e.encoder.failures[mediaPhoto(4).uri] = ImageDecodeException("bozuk") // en yeni: önce işlenir
        e.encoder.failures[mediaPhoto(2).uri] = OutOfMemoryError("test")
        expectUnexpectedOom { e.indexer.index().run() }
        assertEquals(setOf(3L), e.db.photos.keys) // 4 atlandı (bozuk), 3 yazıldı, 2'de OOM ile durdu
        assertEquals(IndexState(total = 4, processed = 2, lastRunAt = null), e.db.state)
    }

    // ================= worker: RETRY (en çok 3 deneme) sonra FAILURE; gerçek indeksleyici ile =================

    @Test
    fun worker_oomFromRealIndexer_retriesTwice_thenFails_andFullKeepsSameTargetAcrossAttempts() = runTest {
        val e = env((1L..5L).toList())
        e.indexer.index().run()
        e.faults.site = "encoder"
        e.faults.custom = null
        val outcomes = (0 until IndexWork.MAX_ATTEMPTS + 1).map { IndexWork.execute(e.indexer, IndexMode.FULL, it) }
        assertEquals(
            listOf(IndexOutcome.RETRY, IndexOutcome.RETRY, IndexOutcome.FAILURE, IndexOutcome.FAILURE),
            outcomes,
        )
        // Her deneme hedef 2'de takıldı; işaret ve eski kayıtlar bozulmadan duruyor, arama için tam indeks yerinde.
        assertEquals(2, e.db.state!!.fullTargetVersion)
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L), e.db.embeddings.keys)
        assertEquals(setOf(1), versionSet(e))
    }

    @Test
    fun worker_oomOnFirstAttempt_thenSuccessOnRetry_resumesWithoutRedoingWrittenPhotos() = runTest {
        val e = env((1L..6L).toList())
        e.indexer.index().run()
        var count = 0
        e.encoder.onEncode = { if (++count == 3) throw OutOfMemoryError("test") } // 2 fotoğraf yazılır, 3.'de OOM
        e.encoder.encoded.clear()
        assertEquals(IndexOutcome.RETRY, IndexWork.execute(e.indexer, IndexMode.FULL, 0))
        assertEquals(2, e.db.state!!.fullTargetVersion)
        assertEquals(2, e.db.photos.values.count { it.indexVersion == 2 })
        e.encoder.onEncode = {}
        e.encoder.encoded.clear()
        assertEquals(IndexOutcome.SUCCESS, IndexWork.execute(e.indexer, IndexMode.FULL, 1))
        assertEquals(4, e.encoder.encoded.size) // yalnızca kalan 4
        assertEquals(setOf(2), versionSet(e))
        assertNull(e.db.state!!.fullTargetVersion)
    }

    @Test
    fun worker_oomInScan_isRetryNotCrash_forEachMode() = runTest {
        for (mode in IndexMode.entries) {
            val e = env((1L..3L).toList())
            e.faults.site = "loadIds"
            assertEquals("$mode", IndexOutcome.RETRY, IndexWork.execute(e.indexer, mode, 0))
            assertEquals("$mode", IndexOutcome.FAILURE, IndexWork.execute(e.indexer, mode, IndexWork.MAX_ATTEMPTS - 1))
        }
    }

    @Test
    fun worker_cancellationFromRealIndexer_isNotRetryOrFailure() = runTest {
        val e = env((1L..3L).toList())
        e.faults.site = "encoder"
        e.faults.custom = CancellationException("durduruldu")
        try {
            IndexWork.execute(e.indexer, IndexMode.INCREMENTAL, 0)
            fail("CancellationException beklenirdi")
        } catch (_: CancellationException) {
        }
    }
}
