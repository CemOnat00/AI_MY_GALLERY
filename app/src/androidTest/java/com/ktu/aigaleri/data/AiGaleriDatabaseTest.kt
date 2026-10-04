package com.ktu.aigaleri.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room cihaz testi: ekle/oku, FK ile silme, parametreli sorgular, Flow yeniden emit.
 *
 * Not: emülatör/cihaz yok, çalıştırılmadı; `compileDebugAndroidTestKotlin` ile derlendiği doğrulandı.
 */
@RunWith(AndroidJUnit4::class)
class AiGaleriDatabaseTest {
    private lateinit var db: AiGaleriDatabase
    private val photos get() = db.photoDao()
    private val embeddings get() = db.photoEmbeddingDao()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AiGaleriDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private fun photo(id: Long, dateTaken: Long? = 1000L + id) =
        Photo(id, "content://media/external/images/media/$id", dateTaken, indexedAt = 5000L, indexVersion = 1)

    private fun embedding(id: Long, model: String = "m1") =
        PhotoEmbedding(id, VectorCodec.encode(floatArrayOf(id.toFloat(), 0.5f, -1f)), model)

    @Test
    fun insertAndRead_photoAndEmbedding() = runBlocking {
        photos.upsert(photo(1, dateTaken = null))
        embeddings.upsert(embedding(1))
        assertEquals(photo(1, dateTaken = null), photos.getById(1))
        assertEquals(embedding(1), embeddings.getByPhotoId(1))
        assertNull(photos.getById(99))
    }

    @Test
    fun deletingPhoto_cascadesToEmbedding() = runBlocking {
        photos.upsert(photo(1)); photos.upsert(photo(2))
        embeddings.upsert(embedding(1)); embeddings.upsert(embedding(2))
        photos.deleteById(1)
        assertNull(embeddings.getByPhotoId(1))
        assertEquals(embedding(2), embeddings.getByPhotoId(2))
        photos.deleteAll()
        assertEquals(0, embeddings.count())
    }

    @Test
    fun embeddingWithoutPhoto_violatesForeignKey() {
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { embeddings.upsert(embedding(42)) }
        }
    }

    @Test
    fun upsertingPhoto_keepsEmbedding() = runBlocking {
        photos.upsert(photo(1)); embeddings.upsert(embedding(1))
        photos.upsert(photo(1).copy(indexVersion = 2))
        assertEquals(2, photos.getById(1)!!.indexVersion)
        assertEquals(embedding(1), embeddings.getByPhotoId(1))
    }

    @Test
    fun idsWithoutEmbedding_andModelFilter() = runBlocking {
        for (id in 1L..3L) photos.upsert(photo(id))
        embeddings.upsert(embedding(1, "m1")); embeddings.upsert(embedding(2, "m2"))
        assertEquals(listOf(2L, 3L), photos.idsWithoutEmbedding("m1"))
        val rows = embeddings.getAllForModel("m1")
        assertEquals(listOf(1L), rows.map { it.photoId })
        assertEquals(photo(1).uri, rows.single().uri)
        embeddings.deleteNotMatching("m1")
        assertEquals(1, embeddings.count())
    }

    @Test
    fun queriesTreatInputAsData_notSql() = runBlocking {
        photos.upsert(photo(1)); embeddings.upsert(embedding(1))
        assertEquals(emptyList<IndexedVector>(), embeddings.getAllForModel("x' OR '1'='1"))
        assertEquals(1, photos.count())
    }

    @Test
    fun indexState_singletonUpsertAndObserve() = runBlocking {
        assertNull(db.indexStateDao().get())
        db.indexStateDao().upsert(IndexState(total = 10, processed = 3, lastRunAt = null))
        db.indexStateDao().upsert(IndexState(total = 10, processed = 10, lastRunAt = 7L))
        val state = db.indexStateDao().observe().first()
        assertEquals(IndexState(IndexState.SINGLETON_ID, 10, 10, 7L), state)
    }

    @Test
    fun indexStateObserve_reEmitsAfterUpdate() = runBlocking {
        val channel = db.indexStateDao().observe().produceIn(this)
        try {
            withTimeout(5_000) {
                assertNull(channel.receive())
                db.indexStateDao().upsert(IndexState(total = 5, processed = 1, lastRunAt = null))
                assertEquals(1, channel.receive()!!.processed)
                db.indexStateDao().upsert(IndexState(total = 5, processed = 5, lastRunAt = 9L))
                assertEquals(IndexState(IndexState.SINGLETON_ID, 5, 5, 9L), channel.receive())
            }
        } finally {
            channel.cancel()
        }
    }

    @Test
    fun observeCount_reEmitsOnInsertAndDelete() = runBlocking {
        val channel = photos.observeCount().produceIn(this)
        try {
            withTimeout(5_000) {
                assertEquals(0, channel.receive())
                photos.upsert(photo(1))
                assertEquals(1, channel.receive())
                photos.deleteAll()
                assertEquals(0, channel.receive())
            }
        } finally {
            channel.cancel()
        }
    }
}
