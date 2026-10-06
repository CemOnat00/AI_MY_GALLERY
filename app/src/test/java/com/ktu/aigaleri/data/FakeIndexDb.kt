package com.ktu.aigaleri.data

import com.ktu.aigaleri.domain.EmbeddingSpec
import com.ktu.aigaleri.domain.ImageEncoder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Bellek içi sahte veritabanı: Room DAO sözleşmesini (SQL anlamı dahil, CASCADE) taklit eder. */
class FakeIndexDb {
    val photos = linkedMapOf<Long, Photo>()
    val embeddings = linkedMapOf<Long, PhotoEmbedding>()
    var state: IndexState? = null
    val stateWrites = mutableListOf<IndexState>()
    val deleteByIdsCalls = mutableListOf<List<Long>>()

    /** Bir sonraki embedding yazımında fırlatılacak hata (işlem atomikliği testi). */
    var failEmbeddingUpsertWith: Throwable? = null

    /** Her sorgu çağrısında fırlatılacak hata. */
    var failAllIdsWith: Throwable? = null

    val photoDao = object : PhotoDao {
        override suspend fun upsert(photo: Photo) { photos[photo.mediaStoreId] = photo }
        override suspend fun getById(mediaStoreId: Long) = photos[mediaStoreId]
        override fun observeCount(): Flow<Int> = MutableStateFlow(photos.size)
        override suspend fun count() = photos.size
        override suspend fun idsWithoutEmbedding(modelVersion: String) =
            photos.keys.filter { embeddings[it]?.modelVersion != modelVersion }.sorted()
        override suspend fun allIds(): List<Long> {
            failAllIdsWith?.let { throw it }
            return photos.keys.sorted()
        }
        override suspend fun maxIndexVersion(): Int? = photos.values.maxOfOrNull { it.indexVersion }
        override suspend fun idsNeedingIndex(targetVersion: Int, modelVersion: String) =
            photos.values.filter { it.indexVersion < targetVersion || embeddings[it.mediaStoreId]?.modelVersion != modelVersion }
                .map { it.mediaStoreId }.sortedDescending()
        override suspend fun deleteById(mediaStoreId: Long) {
            photos.remove(mediaStoreId)
            embeddings.remove(mediaStoreId) // CASCADE
        }
        override suspend fun deleteByIds(ids: List<Long>) {
            deleteByIdsCalls += ids.toList()
            ids.forEach { deleteById(it) }
        }
        override suspend fun deleteAll() { photos.clear(); embeddings.clear() }
    }

    val embeddingDao = object : PhotoEmbeddingDao {
        override suspend fun upsert(embedding: PhotoEmbedding) {
            failEmbeddingUpsertWith?.let { throw it }
            check(embedding.photoId in photos) { "FK ihlali: photo yok" }
            embeddings[embedding.photoId] = embedding
        }
        override suspend fun getByPhotoId(photoId: Long) = embeddings[photoId]
        override suspend fun getAllForModel(modelVersion: String): List<IndexedVector> =
            embeddings.values.filter { it.modelVersion == modelVersion }.sortedBy { it.photoId }.map {
                val p = photos.getValue(it.photoId)
                IndexedVector(p.mediaStoreId, p.uri, p.dateTaken, it.vector)
            }
        override suspend fun count() = embeddings.size
        override suspend fun deleteNotMatching(modelVersion: String) {
            embeddings.entries.removeAll { it.value.modelVersion != modelVersion }
        }
    }

    val stateDao = object : IndexStateDao {
        override suspend fun upsert(state: IndexState) { this@FakeIndexDb.state = state; stateWrites += state }
        override suspend fun get(id: Int) = state
        override fun observe(id: Int): Flow<IndexState?> = MutableStateFlow(state).map { it }
    }

    /** Hata olursa photos/embeddings işlem öncesine döner (SQLite işlem geri alma taklidi). */
    val transactions = object : TransactionRunner {
        override suspend fun <T> run(block: suspend () -> T): T {
            val p = LinkedHashMap(photos)
            val e = LinkedHashMap(embeddings)
            try {
                return block()
            } catch (t: Throwable) {
                photos.clear(); photos.putAll(p)
                embeddings.clear(); embeddings.putAll(e)
                throw t
            }
        }
    }
}

/** MediaStore sahtesi: indeksleyicinin yalnızca kimlik/parça API'sini kullandığını denetler. */
class RecordingSource(photos: List<MediaPhoto> = emptyList()) : MediaPhotoSource {
    var gallery: List<MediaPhoto> = photos
    var loadPhotosCalls = 0
    val loadByIdsSizes = mutableListOf<Int>()
    var idsFailure: Throwable? = null

    /** loadByIds sonuçlarından gizlenecek kimlikler (tarama sonrası silinme taklidi). */
    val vanishedAfterScan = mutableSetOf<Long>()

    override suspend fun count() = gallery.size
    override suspend fun loadPhotos(): List<MediaPhoto> { loadPhotosCalls++; return gallery }
    override suspend fun loadIds(): LongArray {
        idsFailure?.let { throw it }
        return LongArray(gallery.size) { gallery[it].id }.also { it.reverse() } // sırasız gelsin
    }
    override suspend fun loadByIds(ids: List<Long>): List<MediaPhoto> {
        loadByIdsSizes += ids.size
        return gallery.filter { it.id in ids && it.id !in vanishedAfterScan }.shuffled(java.util.Random(1))
    }
}

fun mediaPhoto(id: Long) = MediaPhoto(id, "content://media/external/images/media/$id", 1_000L + id)

/** Deterministik sahte kodlayıcı: vektör = uri'den türetilen birim vektör. */
class FakeImageEncoder(override val embeddingSpec: EmbeddingSpec) : ImageEncoder {
    val encoded = mutableListOf<String>()
    var released = 0

    /** uri -> fırlatılacak hata. */
    val failures = mutableMapOf<String, Throwable>()
    var onEncode: suspend (String) -> Unit = {}
    var vectorSize = embeddingSpec.dimension

    override suspend fun encode(uri: String): FloatArray {
        encoded += uri
        onEncode(uri)
        failures[uri]?.let { throw it }
        val v = FloatArray(vectorSize)
        v[Math.floorMod(uri.hashCode(), vectorSize)] = 1f
        return v
    }

    override suspend fun release() { released++ }
}
