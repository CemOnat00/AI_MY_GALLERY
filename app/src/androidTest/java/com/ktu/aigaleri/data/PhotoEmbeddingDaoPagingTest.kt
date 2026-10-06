package com.ktu.aigaleri.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [PhotoEmbeddingDao.getPageForModel] (T-008) gerçek SQLite ile: keyset sayfalama, model süzme, sıra, LIMIT, parametre
 * güvenliği ve getAllForModel ile eşdeğerlik.
 *
 * DURUM: YAZILDI, `compileDebugAndroidTestKotlin` ile DERLENİR; ÇALIŞTIRILMADI (cihaz/emülatör yok).
 */
@RunWith(AndroidJUnit4::class)
class PhotoEmbeddingDaoPagingTest {
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

    private fun insert(id: Long, model: String) = runBlocking {
        photos.upsert(Photo(id, "content://media/external/images/media/$id", if (id % 2 == 0L) null else id * 10, 1L, 1))
        embeddings.upsert(PhotoEmbedding(id, VectorCodec.encode(floatArrayOf(id.toFloat(), 1f)), model))
    }

    @Test
    fun pages_coverAllRowsOfModelExactlyOnce_inPhotoIdOrder() = runBlocking {
        // eklenme sırası karışık, bir kısmı eski model
        listOf(9L, 2L, 7L, 4L, 1L, 8L, 3L, 6L, 5L, 10L).forEach { insert(it, if (it % 4 == 0L) "old" else "m1") }
        val expected = embeddings.getAllForModel("m1")
        val collected = mutableListOf<IndexedVector>()
        var after = Long.MIN_VALUE
        while (true) {
            val page = embeddings.getPageForModel("m1", after, 3)
            assertTrue(page.size <= 3)
            if (page.isEmpty()) break
            collected += page
            after = page.last().photoId
        }
        assertEquals(expected, collected)
        assertEquals(listOf(1L, 2L, 3L, 5L, 6L, 7L, 9L, 10L), collected.map { it.photoId })
    }

    @Test
    fun afterId_isExclusive_andLimitIsRespected() = runBlocking {
        for (id in 1L..5L) insert(id, "m1")
        assertEquals(listOf(3L, 4L), embeddings.getPageForModel("m1", 2L, 2).map { it.photoId })
        assertEquals(emptyList<Long>(), embeddings.getPageForModel("m1", 5L, 10).map { it.photoId })
        assertEquals(listOf(1L), embeddings.getPageForModel("m1", Long.MIN_VALUE, 1).map { it.photoId })
        assertEquals(5, embeddings.getPageForModel("m1", -1L, 1000).size)
    }

    @Test
    fun rowCarriesUriDateAndVector() = runBlocking {
        insert(1, "m1"); insert(2, "m1")
        val rows = embeddings.getPageForModel("m1", 0, 10)
        assertEquals("content://media/external/images/media/1", rows[0].uri)
        assertEquals(10L, rows[0].dateTaken)
        assertEquals(null, rows[1].dateTaken)
        assertTrue(floatArrayOf(2f, 1f).contentEquals(VectorCodec.decode(rows[1].vector)))
    }

    @Test
    fun modelFilter_treatsInputAsData_notSql() = runBlocking {
        insert(1, "m1")
        assertEquals(emptyList<IndexedVector>(), embeddings.getPageForModel("x' OR '1'='1", Long.MIN_VALUE, 10))
        assertEquals(emptyList<IndexedVector>(), embeddings.getPageForModel("m2", Long.MIN_VALUE, 10))
    }

    @Test
    fun photoDeletedBetweenPages_isSimplyAbsent() = runBlocking {
        for (id in 1L..6L) insert(id, "m1")
        val first = embeddings.getPageForModel("m1", Long.MIN_VALUE, 3)
        photos.deleteById(4) // CASCADE embedding'i de siler
        val second = embeddings.getPageForModel("m1", first.last().photoId, 3)
        assertEquals(listOf(5L, 6L), second.map { it.photoId })
    }
}
