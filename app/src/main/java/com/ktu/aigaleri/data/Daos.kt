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

    /**
     * Tüm kimlikler, artan sıralı (indeksleyici taramasında MediaStore ile fark almak için).
     * Bellek: kimlik başına ~32 bayt (boxed Long); 100 bin fotoğrafta ~3 MB.
     */
    @Query("SELECT mediaStoreId FROM photo ORDER BY mediaStoreId")
    suspend fun allIds(): List<Long>

    /** En yüksek `indexVersion` (kayıt yoksa null); indeksleyicinin hedef sürümünün tabanı. */
    @Query("SELECT MAX(indexVersion) FROM photo")
    suspend fun maxIndexVersion(): Int?

    /**
     * Yeniden işlenmesi gereken kayıtlar: `indexVersion` hedefin altında VEYA [modelVersion] ile yazılmış
     * vektörü yok (eksik ya da eski model). Kimlik azalan sıralı (yeni eklenenler önce).
     */
    @Query(
        "SELECT mediaStoreId FROM photo WHERE indexVersion < :targetVersion OR mediaStoreId NOT IN " +
            "(SELECT photoId FROM photo_embedding WHERE modelVersion = :modelVersion) " +
            "ORDER BY mediaStoreId DESC",
    )
    suspend fun idsNeedingIndex(targetVersion: Int, modelVersion: String): List<Long>

    @Query("DELETE FROM photo WHERE mediaStoreId = :mediaStoreId")
    suspend fun deleteById(mediaStoreId: Long)

    /**
     * Toplu silme (CASCADE embedding'i de siler). Çağıran SQLite değişken sınırı için parçalar
     * (<= 500 kimlik/çağrı).
     */
    @Query("DELETE FROM photo WHERE mediaStoreId IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM photo")
    suspend fun deleteAll()
}

@Dao
interface PhotoEmbeddingDao {
    @Upsert
    suspend fun upsert(embedding: PhotoEmbedding)

    @Query("SELECT * FROM photo_embedding WHERE photoId = :photoId")
    suspend fun getByPhotoId(photoId: Long): PhotoEmbedding?

    /**
     * Arama için: verilen modelin tüm vektörleri, fotoğraf bilgisiyle birlikte.
     *
     * Bellek sınırı: sonuç tek seferde belleğe yüklenir. 512 boyutta 10 bin fotoğraf ≈ 20 MB,
     * 50 bin ≈ 100 MB+ (uri her satırda tekrar yüklenir). ARAMA İÇİN KULLANILMAZ (T-008): arama [getPageForModel]
     * ile sayfalı tarar; bu sorgu yalnızca küçük veri/test içindir.
     */
    @Query(
        "SELECT p.mediaStoreId AS photoId, p.uri AS uri, p.dateTaken AS dateTaken, e.vector AS vector " +
            "FROM photo_embedding e INNER JOIN photo p ON p.mediaStoreId = e.photoId " +
            "WHERE e.modelVersion = :modelVersion ORDER BY p.mediaStoreId",
    )
    suspend fun getAllForModel(modelVersion: String): List<IndexedVector>

    /**
     * Arama için sayfalı (keyset) okuma: verilen modelin vektörleri, `photoId > [afterId]` olan ilk [limit] satır,
     * `photoId` artan. İlk sayfa için [afterId] = `Long.MIN_VALUE`; sonraki sayfa için önceki sayfanın son `photoId`'si.
     * OFFSET kullanılmaz (sayfa maliyeti sabit: `photo_embedding` rowid aralık taraması + `photo` PK araması) ve
     * tarama sürerken yazan indeksleyiciyle tutarlıdır (kimlik tekrarı olmaz; yeni satır görünür ya da görünmez).
     * Sayfa belleği ~[limit] x (vektör BLOB + uri); SQLite CursorWindow (2 MB) için [limit] <= ~500 tutulmalıdır
     * (512 boyutta satır ~2,1 KB).
     */
    @Query(
        "SELECT p.mediaStoreId AS photoId, p.uri AS uri, p.dateTaken AS dateTaken, e.vector AS vector " +
            "FROM photo_embedding e INNER JOIN photo p ON p.mediaStoreId = e.photoId " +
            "WHERE e.modelVersion = :modelVersion AND e.photoId > :afterId ORDER BY e.photoId LIMIT :limit",
    )
    suspend fun getPageForModel(modelVersion: String, afterId: Long, limit: Int): List<IndexedVector>

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
