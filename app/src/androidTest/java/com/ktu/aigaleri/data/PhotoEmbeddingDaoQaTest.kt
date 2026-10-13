package com.ktu.aigaleri.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-025 QA: gerçek SQLite ile uç durumlar (kimlik uçları, negatif kimlik, Türkçe karakterli uri/modelVersion, model
 * sürümü büyük/küçük harf duyarlılığı, 500 kimlikli toplu sorgu, FK ile yetim embedding'in oluşamaması, boşluk-only uri'nin
 * olduğu gibi okunması, getAllForModel (JOIN'li eski sorgu) ile sayfa + toplu uri birleşiminin birebir aynılığı).
 *
 * DURUM: YAZILDI ve `compileDebugAndroidTestKotlin` ile DERLENİR; ÇALIŞTIRILMADI (cihaz/emülatör yok).
 */
@RunWith(AndroidJUnit4::class)
class PhotoEmbeddingDaoQaTest {
    private lateinit var db: AiGaleriDatabase
    private val photos get() = db.photoDao()
    private val embeddings get() = db.photoEmbeddingDao()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AiGaleriDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun put(id: Long, model: String, uri: String = "content://media/$id", date: Long? = null, vec: FloatArray = floatArrayOf(id.toFloat(), 1f)) {
        photos.upsert(Photo(id, uri, date, 1L, 1))
        embeddings.upsert(PhotoEmbedding(id, VectorCodec.encode(vec), model))
    }

    @Test
    fun extremeAndNegativeIds_pageInAscendingOrder_longMinIsExcludedByKeysetStart() = runBlocking {
        val ids = listOf(Long.MAX_VALUE, 0L, -1L, 1L, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1, -5L, Long.MIN_VALUE)
        ids.forEach { put(it, "m1") }
        val collected = mutableListOf<Long>()
        var after = Long.MIN_VALUE
        while (true) {
            val page = embeddings.getPageForModel("m1", after, 3)
            if (page.isEmpty()) break
            collected += page.map { it.photoId }
            after = page.last().photoId
        }
        // photoId > Long.MIN_VALUE: kimliği Long.MIN_VALUE olan kayıt taranmaz (eski JOIN'li sorguyla aynı; MediaStore kimlikleri pozitif)
        assertEquals(ids.filter { it != Long.MIN_VALUE }.sorted(), collected)
        assertEquals(emptyList<Long>(), embeddings.getPageForModel("m1", Long.MAX_VALUE, 10).map { it.photoId })
    }

    @Test
    fun modelVersion_isCaseAndTurkishCharacterSensitive_exactMatchOnly() = runBlocking {
        put(1, "kıyas-İI-ı-v2")
        put(2, "kıyas-iI-ı-v2")
        put(3, "KIYAS-Iİ-I-V2")
        put(4, "kıyas-İI-ı-v2 ")
        put(5, "kıyas-İI-ı-v2") // birleşik noktalı I (NFD), İ ile aynı DEĞİL
        assertEquals(listOf(1L), embeddings.getPageForModel("kıyas-İI-ı-v2", Long.MIN_VALUE, 10).map { it.photoId })
        assertEquals(listOf(2L), embeddings.getPageForModel("kıyas-iI-ı-v2", Long.MIN_VALUE, 10).map { it.photoId })
        assertEquals(emptyList<Long>(), embeddings.getPageForModel("", Long.MIN_VALUE, 10).map { it.photoId })
    }

    @Test
    fun photoRefs_turkishUri_blankUri_nullDate_roundTripVerbatim() = runBlocking {
        val uris = mapOf(
            1L to "content://media/external/images/media/çğıöşü-İ-1",
            2L to "   ",
            3L to "",
            4L to "file:///sdcard/Pictures/Şişli İstanbul 4.jpg",
        )
        uris.forEach { (id, u) -> put(id, "m1", uri = u, date = if (id == 4L) 123L else null) }
        val refs = embeddings.getPhotoRefsByIds(listOf(4L, 3L, 2L, 1L, 77L)).associateBy { it.mediaStoreId }
        assertEquals(uris.keys, refs.keys)
        uris.forEach { (id, u) -> assertEquals(u, refs.getValue(id).uri) }
        assertEquals(123L, refs.getValue(4).dateTaken)
        assertEquals(null, refs.getValue(1).dateTaken)
        // sayfa, boş/boşluk uri'li kaydı da döndürür (süzme aramada, üretimde atlanır)
        assertEquals(listOf(1L, 2L, 3L, 4L), embeddings.getPageForModel("m1", Long.MIN_VALUE, 10).map { it.photoId })
    }

    @Test
    fun orphanEmbedding_cannotExist_withForeignKeys_soJoinlessPageEqualsOldJoin() = runBlocking {
        try {
            embeddings.upsert(PhotoEmbedding(999, VectorCodec.encode(floatArrayOf(1f, 0f)), "m1"))
            fail("FK ihlali beklenirdi (yetim embedding)")
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
        }
        for (id in 1L..30L) put(id, if (id % 3 == 0L) "old" else "m1")
        val old = embeddings.getAllForModel("m1")
        val page = embeddings.getPageForModel("m1", Long.MIN_VALUE, 1000)
        assertEquals(old.map { it.photoId }, page.map { it.photoId })
        assertTrue(old.indices.all { old[it].vector.contentEquals(page[it].vector) })
    }

    @Test
    fun photoRefs_500Ids_withMissingOnes_returnsOnlyExisting() = runBlocking {
        for (id in 1L..300L) put(id, "m1")
        val ids = (1L..500L).toList() // 200 tanesi yok
        assertEquals(300, embeddings.getPhotoRefsByIds(ids).size)
        assertEquals(emptyList<PhotoRef>(), embeddings.getPhotoRefsByIds(listOf(Long.MIN_VALUE, Long.MAX_VALUE, -1L, 0L)))
    }

    @Test
    fun cascadeDelete_removesEmbedding_fromPage() = runBlocking {
        for (id in 1L..5L) put(id, "m1")
        photos.deleteById(3)
        assertEquals(listOf(1L, 2L, 4L, 5L), embeddings.getPageForModel("m1", Long.MIN_VALUE, 10).map { it.photoId })
        assertEquals(emptyList<PhotoRef>(), embeddings.getPhotoRefsByIds(listOf(3L)))
    }
}
