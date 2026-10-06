package com.ktu.aigaleri.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ktu.aigaleri.domain.EmbeddingSpec
import com.ktu.aigaleri.domain.ImageEncoder
import com.ktu.aigaleri.domain.IndexMode
import com.ktu.aigaleri.domain.IndexPhase
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room tabanlı indeksleme testi (gerçek SQLite, gerçek DAO sorguları, FK/CASCADE ve @Transaction), sahte kaynak ve
 * sahte kodlayıcıyla: JVM testlerinde bellek içi sahte DAO'nun SQL taklidini gerçek sorgularla doğrular
 * (`idsNeedingIndex`, `maxIndexVersion`, `deleteByIds`, `allIds`).
 *
 * DURUM: YAZILDI, DERLENDİ, ÇALIŞTIRILMADI (cihaz/emülatör yok).
 */
@RunWith(AndroidJUnit4::class)
class RoomPhotoIndexerDeviceTest {
    private val spec = EmbeddingSpec(4, "device-test-v1")
    private lateinit var db: AiGaleriDatabase
    private val encoded = mutableListOf<String>()

    private val encoder = object : ImageEncoder {
        override val embeddingSpec = spec
        override suspend fun encode(uri: String): FloatArray {
            encoded += uri
            return floatArrayOf(1f, 0f, 0f, 0f)
        }
        override suspend fun release() = Unit
    }

    private class Source(var ids: List<Long>) : MediaPhotoSource {
        private fun photo(id: Long) = MediaPhoto(id, "content://media/external/images/media/$id", 1_000L + id)
        override suspend fun count() = ids.size
        override suspend fun loadPhotos() = ids.map(::photo)
        override suspend fun loadIds() = ids.toLongArray()
        override suspend fun loadByIds(ids: List<Long>) = ids.filter { it in this.ids }.map(::photo)
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AiGaleriDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private fun indexer(source: Source) = RoomPhotoIndexer(
        source, db.photoDao(), db.photoEmbeddingDao(), db.indexStateDao(), RoomTransactionRunner(db), encoder,
    )

    @Test
    fun incrementalThenFullThenCleanup_onRealSqlite() = runBlocking {
        val source = Source(listOf(1, 2, 3, 4))
        val idx = indexer(source)

        val first = idx.index(IndexMode.INCREMENTAL).toList()
        assertEquals(IndexPhase.COMPLETED, first.last().phase)
        assertEquals(4, db.photoDao().count())
        assertEquals(4, db.photoEmbeddingDao().count())
        assertEquals(RoomPhotoIndexer.PIPELINE_VERSION, db.photoDao().maxIndexVersion())

        // Aynı model ve sürümde yeniden işlenmez.
        encoded.clear()
        idx.index(IndexMode.INCREMENTAL).toList()
        assertTrue(encoded.isEmpty())
        assertTrue(db.photoDao().idsNeedingIndex(RoomPhotoIndexer.PIPELINE_VERSION, spec.modelVersion).isEmpty())

        // FULL (indeks güncel -> yeni tur): hedef sürüm artar, hepsi yerine yazılır, embedding hiç eksilmez.
        idx.index(IndexMode.FULL).toList()
        assertEquals(RoomPhotoIndexer.PIPELINE_VERSION + 1, db.photoDao().maxIndexVersion())
        assertEquals(4, db.photoEmbeddingDao().count())
        assertEquals(8, encoded.size)

        // Galeriden silinen: kayıt ve (CASCADE) embedding temizlenir; yeni eklenen indekslenir.
        source.ids = listOf(2, 3, 4, 7)
        encoded.clear()
        idx.index(IndexMode.INCREMENTAL).toList()
        assertEquals(listOf(2L, 3L, 4L, 7L), db.photoDao().allIds())
        assertNull(db.photoEmbeddingDao().getByPhotoId(1))
        assertEquals(1, encoded.size)
        assertEquals(RoomPhotoIndexer.PIPELINE_VERSION + 1, db.photoDao().getById(7)!!.indexVersion)

        val state = db.indexStateDao().get()!!
        assertEquals(1, state.total)
        assertEquals(1, state.processed)
        assertTrue(state.lastRunAt != null)
    }

    @Test
    fun oldModelVersion_isReprocessed_andReplacedInPlace() = runBlocking {
        val source = Source(listOf(1, 2))
        indexer(source).index().toList()
        val newer = RoomPhotoIndexer(
            source, db.photoDao(), db.photoEmbeddingDao(), db.indexStateDao(), RoomTransactionRunner(db),
            object : ImageEncoder by encoder {
                override val embeddingSpec = EmbeddingSpec(4, "device-test-v2")
            },
        )
        encoded.clear()
        newer.index().toList()
        assertEquals(2, encoded.size)
        assertEquals(2, db.photoEmbeddingDao().getAllForModel("device-test-v2").size)
        assertEquals(0, db.photoEmbeddingDao().getAllForModel("device-test-v1").size)
    }

    @Test
    fun bulkDelete_ofManyIds_works() = runBlocking {
        val ids = (1L..1_200L).toList()
        for (id in ids) db.photoDao().upsert(Photo(id, "content://x/$id", null, 1L, 1))
        // Galeri boş OLMAMALI: boş MediaStore + dolu Room'da temizleme bilinçli olarak atlanır.
        val idx = indexer(Source(listOf(5_000L)))
        idx.index().toList()
        assertEquals(listOf(5_000L), db.photoDao().allIds())
    }
}
