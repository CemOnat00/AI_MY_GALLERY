package com.ktu.aigaleri.ml

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ktu.aigaleri.data.AiGaleriDatabase
import com.ktu.aigaleri.data.Photo
import com.ktu.aigaleri.data.PhotoEmbedding
import com.ktu.aigaleri.data.RoomSearchRepository
import com.ktu.aigaleri.data.VectorCodec
import com.ktu.aigaleri.domain.InvalidQueryException
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Uçtan uca (GERÇEK modellerle, assets/models/, `tools/fetch_models.sh`): sentetik fotoğrafları görüntü kodlayıcıyla
 * kodla -> Room'a yaz -> Türkçe istemle [RoomSearchRepository] ile ara. Aynı ModelStore ve SingleSessionSlot paylaşılır
 * (arama metin, indeksleme görüntü oturumunu dönüşümlü açar). Piksel yükleyici sentetiktir (MediaStore gerekmez).
 *
 * DURUM: YAZILDI, DERLENİR; ÇALIŞTIRILMADI (cihaz/emülatör yok; model APK'da yoksa başarısız olur). Anlamsal kalite
 * (örn. "kırmızı" -> kırmızı fotoğraf) iddia EDİLMEZ; yalnızca boyut, normalizasyon, skor = nokta çarpımı ve sıralama.
 */
@RunWith(AndroidJUnit4::class)
class SearchEndToEndDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = ModelStore(context.filesDir, AssetManagerOpener(context.assets))
    private val slot = SingleSessionSlot()
    private lateinit var db: AiGaleriDatabase
    private lateinit var textEncoder: OnnxTextEncoder
    private lateinit var imageEncoder: OnnxImageEncoder

    private fun solid(rgb: Int) = PixelImage(IntArray(256 * 256) { rgb or (0xFF shl 24) }, 256, 256)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AiGaleriDatabase::class.java).build()
        textEncoder = OnnxTextEncoder(store, slot)
        imageEncoder = OnnxImageEncoder(store, { uri -> solid(if (uri.endsWith("/1")) 0xCC2020 else if (uri.endsWith("/2")) 0x2030CC else 0x20CC30) }, slot)
    }

    @After
    fun tearDown() {
        runBlocking { slot.closeAll() }
        db.close()
    }

    private fun norm(v: FloatArray) = sqrt(v.sumOf { it.toDouble() * it })

    private fun indexPhoto(id: Long): FloatArray = runBlocking {
        val uri = "content://media/external/images/media/$id"
        val v = try {
            imageEncoder.encode(uri)
        } catch (e: ModelException.Missing) {
            fail("Model dosyası APK'da yok: tools/fetch_models.sh çalıştırıp yeniden derleyin (${e.assetPath})")
            error("unreachable")
        }
        db.photoDao().upsert(Photo(id, uri, 1_000L + id, 1L, 1))
        db.photoEmbeddingDao().upsert(PhotoEmbedding(id, VectorCodec.encode(v), ModelManifest.MODEL_VERSION))
        v
    }

    @Test
    fun singlePhoto_turkishQuery_returnsIt_with512DimScoreEqualToDotProduct() = runBlocking {
        val image = indexPhoto(1)
        assertEquals(512, image.size)
        assertEquals(1.0, norm(image), 1e-3)

        val repo = RoomSearchRepository(textEncoder, db.photoEmbeddingDao())
        val results = repo.search("kırmızı bir fotoğraf")
        assertEquals(1, results.size)
        val q = textEncoder.encode("kırmızı bir fotoğraf")
        assertEquals(512, q.size)
        val expected = q.indices.sumOf { q[it].toDouble() * image[it] }
        assertEquals(expected, results[0].score.toDouble(), 1e-3)
        assertEquals(1L, results[0].photoId)
        assertEquals("content://media/external/images/media/1", results[0].uri)
        assertEquals(1_001L, results[0].dateTaken)
    }

    @Test
    fun threePhotos_sortedByDescendingScore_matchingManualDotProducts() = runBlocking {
        val vectors = (1L..3L).associateWith { indexPhoto(it) } // görüntü oturumu açık
        val repo = RoomSearchRepository(textEncoder, db.photoEmbeddingDao()) // arama metin oturumunu açar (eviction)
        val results = repo.search("mavi gökyüzü")
        assertEquals(3, results.size)
        assertTrue(results.zipWithNext().all { (a, b) -> a.score >= b.score })
        val q = textEncoder.encode("mavi gökyüzü")
        for (r in results) {
            val v = vectors.getValue(r.photoId)
            assertEquals(q.indices.sumOf { q[it].toDouble() * v[it] }, r.score.toDouble(), 1e-3)
        }
        assertEquals(2, repo.search("mavi gökyüzü", 2).size)
        // arama sonrası indeksleme yeniden görüntü oturumu açabilir
        assertEquals(512, indexPhoto(3).size)
    }

    @Test
    fun invalidQuery_isRejected_andOldModelVersionIsIgnored() = runBlocking {
        indexPhoto(1)
        db.photoDao().upsert(Photo(2, "content://media/external/images/media/2", null, 1L, 1))
        db.photoEmbeddingDao().upsert(PhotoEmbedding(2, VectorCodec.encode(FloatArray(512) { 1f / sqrt(512f) }), "old-model"))
        val repo = RoomSearchRepository(textEncoder, db.photoEmbeddingDao())
        assertEquals(listOf(1L), repo.search("kedi").map { it.photoId })
        try {
            repo.search("   ")
            fail()
        } catch (_: InvalidQueryException) {
        }
    }

    @Test
    fun warmUp_opensTextSession_thenSearchWorks() = runBlocking {
        indexPhoto(1)
        textEncoder.warmUp()
        val repo = RoomSearchRepository(textEncoder, db.photoEmbeddingDao())
        val t0 = System.nanoTime()
        val r = repo.search("deniz kenarı")
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(1, r.size)
        assertTrue("ısınmış arama çok yavaş: $ms ms", abs(ms) < 30_000) // yalnızca makullük; süre ölçümü değildir
    }
}
