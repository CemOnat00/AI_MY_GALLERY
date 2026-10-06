package com.ktu.aigaleri.data

import java.util.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 50-100 bin sentetik kayıtla bellek/süre makullüğü (JVM, sahte DAO; telefon ölçümü DEĞİLDİR). Kayıtlar sayfa istendikçe
 * üretilir, bütün indeks hiç bellekte tutulmaz; böylece "tarama indeksten bağımsız bellek kullanır" iddiası sınanır.
 */
class SearchScaleTest {
    private class GeneratingDao(private val total: Int, private val dim: Int) : PhotoEmbeddingDao {
        var pages = 0
        var maxRowsPerPage = 0
        override suspend fun getPageForModel(modelVersion: String, afterId: Long, limit: Int): List<IndexedVector> {
            pages++
            val start = if (afterId == Long.MIN_VALUE) 1L else afterId + 1
            val end = minOf(total.toLong(), start + limit - 1)
            if (start > end) return emptyList()
            val out = ArrayList<IndexedVector>((end - start + 1).toInt())
            for (id in start..end) {
                val rnd = Random(id)
                val v = randomUnit(rnd, dim)
                out += IndexedVector(id, "content://media/external/images/media/$id", id, VectorCodec.encode(v))
            }
            maxRowsPerPage = maxOf(maxRowsPerPage, out.size)
            return out
        }
        override suspend fun getAllForModel(modelVersion: String): List<IndexedVector> = throw AssertionError("tümünü okuma")
        override suspend fun upsert(embedding: PhotoEmbedding) = throw UnsupportedOperationException()
        override suspend fun getByPhotoId(photoId: Long): PhotoEmbedding? = throw UnsupportedOperationException()
        override suspend fun count(): Int = throw UnsupportedOperationException()
        override suspend fun deleteNotMatching(modelVersion: String) = throw UnsupportedOperationException()
    }

    private fun usedMb(): Long {
        repeat(3) { System.gc() }
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
    }

    private fun scale(total: Int) = runBlocking {
        val dim = 512
        val dao = GeneratingDao(total, dim)
        val q = randomUnit(Random(99), dim)
        val repo = RoomSearchRepository(FixedTextEncoder(q, com.ktu.aigaleri.domain.EmbeddingSpec(dim, TEST_MODEL)), dao, dispatcher = Dispatchers.Default)
        val before = usedMb()
        val t0 = System.nanoTime()
        val result = repo.search("x", 50)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val retainedMb = usedMb() - before

        assertEquals(50, result.size)
        val pageSize = RoomSearchRepository.DEFAULT_PAGE_SIZE
        assertEquals((total + pageSize - 1) / pageSize + if (total % pageSize == 0) 1 else 0, dao.pages)
        assertTrue("sayfa boyutu aşıldı: ${dao.maxRowsPerPage}", dao.maxRowsPerPage <= RoomSearchRepository.DEFAULT_PAGE_SIZE)
        // Sıralama doğru mu (örneklem): azalan skor
        assertTrue(result.zipWithNext().all { (a, b) -> a.score > b.score || (a.score == b.score && a.photoId < b.photoId) })
        // Arama sonrası kalıcı bellek: indeks (~total*2 KB) değil, birkaç MB'ı geçmemeli.
        assertTrue("arama sonrası tutulan bellek ${retainedMb} MB", retainedMb < 30)
        // Çok cömert üst sınır: JVM'de çok yavaş CI makinesinde bile geçer; telefon süresi DEĞİLDİR (bkz. KDoc).
        assertTrue("süre ${ms} ms", ms < 60_000)
        println("search scale total=$total pages=${dao.pages} ms=$ms retainedMb=$retainedMb (JVM, üretim süresi dahil)")
    }

    @Test fun fiftyThousandRecords_scanStaysBoundedAndFast() = scale(50_000)

    @Test fun hundredThousandRecords_scanStaysBoundedAndFast() = scale(100_000)
}
