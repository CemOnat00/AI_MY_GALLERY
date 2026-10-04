package com.ktu.aigaleri.domain

import kotlin.math.sqrt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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

/** Bellek içi sahte indeks; PhotoIndexer yazar, SearchRepository okur. */
class FakeIndex {
    val vectors = linkedMapOf<Long, FloatArray>()
    val photos = linkedMapOf<Long, FakePhoto>()
}

class FakePhotoIndexer(
    override val embeddingSpec: EmbeddingSpec,
    private val gallery: List<FakePhoto>,
    private val index: FakeIndex,
    private val failingIds: Set<Long> = emptySet(),
) : PhotoIndexer {
    private val embedder = FakeEmbedder(embeddingSpec)

    override fun index(mode: IndexMode): Flow<IndexProgress> = flow {
        emit(IndexProgress(IndexPhase.SCANNING, total = 0, processed = 0))
        if (mode == IndexMode.FULL) {
            index.vectors.clear()
            index.photos.clear()
        }
        val todo = gallery.filter { it.id !in index.vectors }
        var failed = 0
        todo.forEachIndexed { i, photo ->
            if (photo.id in failingIds) {
                failed++
            } else {
                index.vectors[photo.id] = embedder.embed(photo.tags)
                index.photos[photo.id] = photo
            }
            emit(IndexProgress(IndexPhase.INDEXING, todo.size, i + 1, failed))
        }
        emit(IndexProgress(IndexPhase.COMPLETED, todo.size, todo.size, failed))
    }
}

class FakeSearchRepository(
    spec: EmbeddingSpec,
    private val index: FakeIndex,
) : SearchRepository {
    private val embedder = FakeEmbedder(spec)

    override suspend fun search(query: String, limit: Int): List<SearchResult> {
        require(limit > 0) { "limit > 0 olmalı" }
        val q = embedder.embed(query)
        return index.vectors.entries
            .map { (id, vec) ->
                val photo = index.photos.getValue(id)
                SearchResult(id, photo.uri, dot(q, vec), photo.dateTaken)
            }
            .filter { it.score > 0f }
            .sortedWith(compareByDescending<SearchResult> { it.score }.thenBy { it.photoId })
            .take(limit)
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
