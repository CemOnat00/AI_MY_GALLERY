package com.ktu.aigaleri.data

import com.ktu.aigaleri.domain.EmbeddingSpec
import com.ktu.aigaleri.domain.TextEncoder
import java.util.Random
import kotlin.math.sqrt

internal const val TEST_MODEL = "test-model-v2"
internal const val TEST_DIM = 8
internal val TEST_SPEC = EmbeddingSpec(TEST_DIM, TEST_MODEL)

/** Sabit vektör döndüren sahte sorgu kodlayıcı; çağrı sayısını sayar. */
internal class FixedTextEncoder(
    var vector: FloatArray,
    override val embeddingSpec: EmbeddingSpec = TEST_SPEC,
    var failure: Throwable? = null,
) : TextEncoder {
    var calls = 0
    override suspend fun encode(query: String): FloatArray {
        calls++
        failure?.let { throw it }
        return vector
    }
}

/** Test satırı: [IndexedVector] + model sürümü. */
internal data class Row(val vec: IndexedVector, val modelVersion: String)

/**
 * Bellek içi sahte DAO: [getPageForModel] gerçek SQL sözleşmesini (photoId artan, > afterId, model süzme, LIMIT) taklit
 * eder. [getAllForModel] çağrılırsa test düşer (arama tüm vektörleri tek seferde okumamalı).
 */
internal open class FakeEmbeddingDao(rows: List<Row> = emptyList()) : PhotoEmbeddingDao {
    private val sorted = rows.sortedBy { it.vec.photoId }
    val pageCalls = mutableListOf<Triple<String, Long, Int>>()
    var onPage: (suspend (callIndex: Int) -> Unit)? = null

    override suspend fun getPageForModel(modelVersion: String, afterId: Long, limit: Int): List<IndexedVector> {
        pageCalls += Triple(modelVersion, afterId, limit)
        onPage?.invoke(pageCalls.size)
        return sorted.asSequence().filter { it.modelVersion == modelVersion && it.vec.photoId > afterId }
            .take(limit).map { it.vec }.toList()
    }

    override suspend fun getAllForModel(modelVersion: String): List<IndexedVector> =
        throw AssertionError("arama getAllForModel kullanmamalı")

    override suspend fun upsert(embedding: PhotoEmbedding) = throw UnsupportedOperationException()
    override suspend fun getByPhotoId(photoId: Long): PhotoEmbedding? = throw UnsupportedOperationException()
    override suspend fun count(): Int = throw UnsupportedOperationException()
    override suspend fun deleteNotMatching(modelVersion: String) = throw UnsupportedOperationException()
}

internal fun row(id: Long, vector: FloatArray, model: String = TEST_MODEL, uri: String = "content://media/$id", date: Long? = id * 10) =
    Row(IndexedVector(id, uri, date, VectorCodec.encode(vector)), model)

/** İlk bileşeni [dot] olan birim vektör (sorgu e0 ise skor tam [dot] olur). */
internal fun unitWithFirst(dot: Float, dim: Int = TEST_DIM): FloatArray {
    val v = FloatArray(dim)
    v[0] = dot
    v[1] = sqrt(1f - dot * dot)
    return v
}

internal fun e0(dim: Int = TEST_DIM) = FloatArray(dim).also { it[0] = 1f }

/** Sabit tohumlu rastgele L2 normalize vektör. */
internal fun randomUnit(rnd: Random, dim: Int): FloatArray {
    val v = FloatArray(dim) { rnd.nextGaussian().toFloat() }
    val n = sqrt(v.sumOf { it.toDouble() * it }).toFloat()
    return FloatArray(dim) { v[it] / n }
}

/** Kaba kuvvet referansı: aynı nokta çarpımı, tam sıralama (skor azalan, photoId artan). */
internal fun bruteForce(rows: List<Row>, query: FloatArray, limit: Int, model: String = TEST_MODEL): List<Pair<Long, Float>> =
    rows.filter { it.modelVersion == model }
        .map { it.vec.photoId to RoomSearchRepository.dot(query, VectorCodec.decode(it.vec.vector)) + 0f }
        .sortedWith(compareByDescending<Pair<Long, Float>> { it.second }.thenBy { it.first })
        .take(limit)
