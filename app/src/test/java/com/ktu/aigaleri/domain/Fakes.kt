package com.ktu.aigaleri.domain

import kotlin.math.sqrt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.yield

/** Test verisi: gerçek fotoğraf yerine etiket metni taşıyan sahte fotoğraf. */
data class FakePhoto(val id: Long, val tags: String, val dateTaken: Long? = null) {
    val uri: String get() = "content://fake/media/$id"
}

/** Sahte gömme: kelimeleri boyuta hash'leyen deterministik torba-kelime vektörü (L2 normalize). */
class FakeEmbedder(val spec: EmbeddingSpec) {
    fun embed(text: String): FloatArray {
        val v = FloatArray(spec.dimension)
        text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach {
            v[Math.floorMod(it.hashCode(), spec.dimension)] += 1f
        }
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0f) for (i in v.indices) v[i] /= norm
        return v
    }
}

/** Sahte indeks kaydı; [fullRun] kaydı yazan FULL çalıştırmanın numarası (0 = artımlı yazım). */
class FakeRecord(
    val photo: FakePhoto,
    val vector: FloatArray,
    val modelVersion: String,
    val fullRun: Int,
)

/** Bellek içi sahte indeks; PhotoIndexer yazar, SearchRepository okur. */
class FakeIndex {
    val records = linkedMapOf<Long, FakeRecord>()

    /** Başlatılmış son FULL çalıştırmanın numarası; bundan eski kayıtlar INCREMENTAL'da yenilenir. */
    var latestFullRun = 0
}

class FakePhotoIndexer(
    override val embeddingSpec: EmbeddingSpec,
    private val gallery: List<FakePhoto>,
    private val index: FakeIndex,
    /** Her çalıştırmada bu kimlikli fotoğraflar hata verir (kalıcı işaretlenmez, yeniden denenir). */
    val failingIds: MutableSet<Long> = mutableSetOf(),
    private val permissionGranted: Boolean = true,
) : PhotoIndexer {
    private val embedder = FakeEmbedder(embeddingSpec)

    override fun index(mode: IndexMode): Flow<IndexProgress> = flow {
        if (!permissionGranted) throw IndexException.PermissionMissing()
        emit(IndexProgress(IndexPhase.SCANNING, total = 0, processed = 0))
        val run = if (mode == IndexMode.FULL) ++index.latestFullRun else 0
        // FULL önce silmez; her fotoğraf yerine yazılır. INCREMENTAL eksik, eski sürüm, eski FULL
        // numaralı ve (başarısız olduğu için hiç yazılmamış) kayıtları işler.
        val todo = gallery.filter { photo ->
            val rec = index.records[photo.id]
            mode == IndexMode.FULL ||
                rec == null ||
                rec.modelVersion != embeddingSpec.modelVersion ||
                rec.fullRun < index.latestFullRun
        }
        var failed = 0
        todo.forEachIndexed { i, photo ->
            if (photo.id in failingIds) {
                failed++
            } else {
                index.records[photo.id] = FakeRecord(
                    photo, embedder.embed(photo.tags), embeddingSpec.modelVersion,
                    if (mode == IndexMode.FULL) run else index.latestFullRun,
                )
            }
            emit(IndexProgress(IndexPhase.INDEXING, todo.size, i + 1, failed))
        }
        emit(IndexProgress(IndexPhase.COMPLETED, todo.size, todo.size, failed))
    }
}

class FakeSearchRepository(
    private val spec: EmbeddingSpec,
    private val index: FakeIndex,
) : SearchRepository {
    private val embedder = FakeEmbedder(spec)

    override suspend fun search(query: String, limit: Int): List<SearchResult> {
        require(limit > 0) { "limit > 0 olmalı" }
        yield() // iptal noktası
        val q = embedder.embed(query)
        return index.records.values
            .filter { it.modelVersion == spec.modelVersion } // eski sürüm kayıtları aramaya girmez
            .map { SearchResult(it.photo.id, it.photo.uri, dot(q, it.vector), it.photo.dateTaken) }
            .sortedWith(compareByDescending<SearchResult> { it.score }.thenBy { it.photoId })
            .take(limit)
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
