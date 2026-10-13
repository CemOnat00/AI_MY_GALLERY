package com.ktu.aigaleri.data

import com.ktu.aigaleri.domain.SearchResult
import java.util.Random
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * T-025 test düzeneği: sentetik indeks + (a) yeni arayüzü (JOIN'siz sayfa + toplu uri) taklit eden sahte DAO,
 * (b) ESKİ davranışı (JOIN'li sayfa: photo satırı olmayan yetim embedding hiç görünmez) taklit eden kaynak ve
 * (c) eski taramanın BİREBİR kopyası ([legacySearch], bayt bayt çözme dahil). Üretim koduna bağımlılık yalnızca
 * [TopKCollector], [RoomSearchRepository.dot] ve (yeni yol için) [VectorCodec]'tir.
 */
internal class SyntheticIndex(
    val ids: LongArray,
    val vectors: Array<ByteArray>,
    val models: Array<String>,
    val uris: Array<String>,
    val dates: Array<Long?>,
    val hasPhoto: BooleanArray,
) {
    val size get() = ids.size

    private val posById: HashMap<Long, Int> by lazy {
        HashMap<Long, Int>(size * 2).also { m -> for (i in 0 until size) if (hasPhoto[i]) m[ids[i]] = i }
    }

    /** `ids[i] > after` olan ilk indeks (ids artan). */
    private fun firstAfter(after: Long): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ids[mid] > after) hi = mid else lo = mid + 1
        }
        return lo
    }

    /** Yeni arayüz: yalnızca photo_embedding (yetimler dahil) + toplu photo sorgusu. */
    inner class NewDao : PhotoEmbeddingDao {
        val pageCalls = mutableListOf<Long>()
        val refCallSizes = mutableListOf<Int>()
        var refIdsTotal = 0

        override suspend fun getPageForModel(modelVersion: String, afterId: Long, limit: Int): List<EmbeddingRow> {
            pageCalls += afterId
            val out = ArrayList<EmbeddingRow>(minOf(limit, 512))
            var i = firstAfter(afterId)
            while (i < size && out.size < limit) {
                if (models[i] == modelVersion) out += EmbeddingRow(ids[i], vectors[i])
                i++
            }
            return out
        }

        override suspend fun getPhotoRefsByIds(ids: List<Long>): List<PhotoRef> {
            require(ids.size <= RoomSearchRepository.REF_CHUNK)
            refCallSizes += ids.size
            refIdsTotal += ids.size
            val out = ArrayList<PhotoRef>(ids.size)
            for (id in ids) posById[id]?.let { out += PhotoRef(id, uris[it], dates[it]) }
            return out
        }

        override suspend fun getAllForModel(modelVersion: String): List<IndexedVector> = throw AssertionError("tümünü okuma")
        override suspend fun upsert(embedding: PhotoEmbedding) = throw UnsupportedOperationException()
        override suspend fun getByPhotoId(photoId: Long): PhotoEmbedding? = throw UnsupportedOperationException()
        override suspend fun count(): Int = throw UnsupportedOperationException()
        override suspend fun deleteNotMatching(modelVersion: String) = throw UnsupportedOperationException()
    }

    /** Eski arayüz: `photo_embedding INNER JOIN photo` (yetim satır yok), uri/dateTaken her satırda. */
    inner class LegacySource {
        var pages = 0

        suspend fun page(modelVersion: String, afterId: Long, limit: Int): List<IndexedVector> {
            pages++
            val out = ArrayList<IndexedVector>(minOf(limit, 512))
            var i = firstAfter(afterId)
            while (i < size && out.size < limit) {
                if (models[i] == modelVersion && hasPhoto[i]) out += IndexedVector(ids[i], uris[i], dates[i], vectors[i])
                i++
            }
            return out
        }
    }

    companion object {
        /** Eski `VectorCodec.decodeInto`'nun birebir kopyası (bayt bayt birleştirme). */
        fun legacyDecodeInto(blob: ByteArray, out: FloatArray) {
            require(blob.size == out.size * Float.SIZE_BYTES)
            var j = 0
            for (i in out.indices) {
                val bits = (blob[j].toInt() and 0xFF) or
                    ((blob[j + 1].toInt() and 0xFF) shl 8) or
                    ((blob[j + 2].toInt() and 0xFF) shl 16) or
                    ((blob[j + 3].toInt() and 0xFF) shl 24)
                out[i] = Float.fromBits(bits)
                j += Float.SIZE_BYTES
            }
        }

        /**
         * Eski `RoomSearchRepository.scan`'in BİREBİR kopyası (T-025 öncesi; sorgu doğrulaması hariç): JOIN'li sayfa,
         * boş uri/uzunluk/skor atlama, hepsi atlanırsa IllegalStateException, TopKCollector(limit).
         */
        suspend fun legacySearch(
            src: LegacySource,
            modelVersion: String,
            q: FloatArray,
            limit: Int,
            pageSize: Int,
            minScore: Float?,
        ): List<SearchResult> {
            val dim = q.size
            val expectedBytes = dim * Float.SIZE_BYTES
            val collector = TopKCollector(limit)
            val scratch = FloatArray(dim)
            var scanned = 0L
            var skipped = 0L
            var after = Long.MIN_VALUE
            while (true) {
                coroutineContext.ensureActive()
                val page = src.page(modelVersion, after, pageSize)
                if (page.isEmpty()) break
                for (row in page) {
                    scanned++
                    if (row.vector.size != expectedBytes || row.uri.isBlank()) {
                        skipped++
                        continue
                    }
                    legacyDecodeInto(row.vector, scratch)
                    val score = RoomSearchRepository.dot(q, scratch)
                    if (!score.isFinite()) {
                        skipped++
                        continue
                    }
                    if (minScore != null && score < minScore) continue
                    collector.offer(row.photoId, score, row.uri, row.dateTaken)
                }
                val last = page.last().photoId
                check(last > after) { "sayfalı okuma ilerlemiyor" }
                after = last
                if (page.size < pageSize) break
            }
            check(!(scanned > 0 && skipped == scanned)) { "indeksteki tüm kayıtlar okunamadı (spec/indeks uyuşmazlığı)" }
            return collector.drainSorted()
        }

        /**
         * Sabit tohumlu sentetik indeks. [pool] > 0 ise kayıtların [tieFraction]'ı küçük bir vektör havuzundan gelir
         * (eşit skorlar). Bozuk/boş uri/başka model/yetim oranları kaydın bağımsız özellikleridir.
         */
        fun build(
            n: Int,
            dim: Int,
            seed: Long,
            model: String = TEST_MODEL,
            otherModelFraction: Double = 0.0,
            corruptFraction: Double = 0.0,
            blankUriFraction: Double = 0.0,
            orphanFraction: Double = 0.0,
            tieFraction: Double = 0.0,
            poolSize: Int = 8,
            maxGap: Int = 1,
        ): SyntheticIndex {
            val rnd = Random(seed)
            val pool = Array(poolSize) { VectorCodec.encode(randomUnit(rnd, dim)) }
            val ids = LongArray(n)
            var id = 0L
            for (i in 0 until n) {
                id += 1 + (if (maxGap > 1) rnd.nextInt(maxGap) else 0)
                ids[i] = id
            }
            val vectors = Array(n) { ByteArray(0) }
            val models = Array(n) { model }
            val uris = Array(n) { "content://media/external/images/media/${ids[it]}" }
            val dates = Array<Long?>(n) { if (it % 7 == 0) null else ids[it] * 10 }
            val hasPhoto = BooleanArray(n) { true }
            for (i in 0 until n) {
                vectors[i] = if (rnd.nextDouble() < tieFraction) pool[rnd.nextInt(poolSize)] else VectorCodec.encode(randomUnit(rnd, dim))
                if (rnd.nextDouble() < otherModelFraction) models[i] = "other-model"
                if (rnd.nextDouble() < corruptFraction) {
                    vectors[i] = when (rnd.nextInt(4)) {
                        0 -> ByteArray((dim - 1) * 4)
                        1 -> ByteArray(dim * 4 + 1)
                        2 -> VectorCodec.encode(FloatArray(dim) { Float.NaN })
                        else -> VectorCodec.encode(FloatArray(dim) { Float.POSITIVE_INFINITY })
                    }
                }
                if (rnd.nextDouble() < blankUriFraction) uris[i] = if (rnd.nextBoolean()) "" else "   "
                if (rnd.nextDouble() < orphanFraction) hasPhoto[i] = false
            }
            return SyntheticIndex(ids, vectors, models, uris, dates, hasPhoto)
        }
    }
}
