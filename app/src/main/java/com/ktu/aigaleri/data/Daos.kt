package com.ktu.aigaleri.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Tüm sorgular parametrelidir (architecture.md Bölüm 8); suspend/Flow ile ana thread dışında çalışır. */
@Dao
interface PhotoDao {
    /** Var olan kaydı günceller; REPLACE kullanılmaz çünkü silip eklemek embedding'i CASCADE ile uçurur. */
    @Upsert
    suspend fun upsert(photo: Photo)

    @Query("SELECT * FROM photo WHERE mediaStoreId = :mediaStoreId")
    suspend fun getById(mediaStoreId: Long): Photo?

    @Query("SELECT COUNT(*) FROM photo")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM photo")
    suspend fun count(): Int

    /** Verilen modelle henüz vektörü çıkarılmamış fotoğrafların kimlikleri (artımlı indeksleme). */
    @Query(
        "SELECT mediaStoreId FROM photo WHERE mediaStoreId NOT IN " +
            "(SELECT photoId FROM photo_embedding WHERE modelVersion = :modelVersion) " +
            "ORDER BY mediaStoreId",
    )
    suspend fun idsWithoutEmbedding(modelVersion: String): List<Long>

    @Query("DELETE FROM photo WHERE mediaStoreId = :mediaStoreId")
    suspend fun deleteById(mediaStoreId: Long)

    @Query("DELETE FROM photo")
    suspend fun deleteAll()
}

@Dao
interface PhotoEmbeddingDao {
    @Upsert
    suspend fun upsert(embedding: PhotoEmbedding)

    @Query("SELECT * FROM photo_embedding WHERE photoId = :photoId")
    suspend fun getByPhotoId(photoId: Long): PhotoEmbedding?

    /** Arama için: verilen modelin tüm vektörleri, fotoğraf bilgisiyle birlikte. */
    @Query(
        "SELECT p.mediaStoreId AS photoId, p.uri AS uri, p.dateTaken AS dateTaken, e.vector AS vector " +
            "FROM photo_embedding e INNER JOIN photo p ON p.mediaStoreId = e.photoId " +
            "WHERE e.modelVersion = :modelVersion ORDER BY p.mediaStoreId",
    )
    suspend fun getAllForModel(modelVersion: String): List<IndexedVector>

    @Query("SELECT COUNT(*) FROM photo_embedding")
    suspend fun count(): Int

    /** Model değişince eski sürümün vektörlerini temizlemek için. */
    @Query("DELETE FROM photo_embedding WHERE modelVersion <> :modelVersion")
    suspend fun deleteNotMatching(modelVersion: String)
}

@Dao
interface IndexStateDao {
    @Upsert
    suspend fun upsert(state: IndexState)

    @Query("SELECT * FROM index_state WHERE id = :id")
    suspend fun get(id: Int = IndexState.SINGLETON_ID): IndexState?

    @Query("SELECT * FROM index_state WHERE id = :id")
    fun observe(id: Int = IndexState.SINGLETON_ID): Flow<IndexState?>
}
