package com.ktu.aigaleri.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * İndekse alınmış fotoğraf (architecture.md Bölüm 5). Fotoğrafın kendisi saklanmaz, yalnızca
 * MediaStore referansı tutulur.
 *
 * @property mediaStoreId MediaStore kimliği; birincil anahtar.
 * @property uri MediaStore URI'sinin metin hali.
 * @property dateTaken çekim zamanı (epoch ms); bilinmiyorsa null.
 * @property indexedAt indekse yazıldığı zaman (epoch ms).
 * @property indexVersion indeksleme hattının sürümü; hat değişince eski kayıtlar yeniden işlenir.
 */
@Entity(tableName = "photo")
data class Photo(
    @PrimaryKey val mediaStoreId: Long,
    val uri: String,
    val dateTaken: Long?,
    val indexedAt: Long,
    val indexVersion: Int,
)

/**
 * Fotoğrafın gömme vektörü. Vektör boyutu model seçimine bağlı olduğundan sabit kodlanmaz;
 * [vector] ham BLOB'dur (kodlama için bkz. [VectorCodec]). Fotoğraf silinince kayıt da silinir.
 *
 * @property photoId [Photo.mediaStoreId] (FK, CASCADE); fotoğraf başına tek vektör.
 * @property modelVersion vektörü üreten modelin sürüm etiketi.
 */
@Entity(
    tableName = "photo_embedding",
    foreignKeys = [
        ForeignKey(
            entity = Photo::class,
            parentColumns = ["mediaStoreId"],
            childColumns = ["photoId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PhotoEmbedding(
    @PrimaryKey val photoId: Long,
    val vector: ByteArray,
    val modelVersion: String,
) {
    // ByteArray alanı için içerik bazlı eşitlik.
    override fun equals(other: Any?): Boolean =
        this === other || (other is PhotoEmbedding &&
            photoId == other.photoId &&
            modelVersion == other.modelVersion &&
            vector.contentEquals(other.vector))

    override fun hashCode(): Int =
        31 * (31 * photoId.hashCode() + modelVersion.hashCode()) + vector.contentHashCode()
}

/**
 * İndeksleme durumu; tek satırlık tablo ([SINGLETON_ID]).
 *
 * @property total son çalıştırmada işlenecek toplam fotoğraf.
 * @property processed işlenen fotoğraf sayısı.
 * @property lastRunAt son çalışma zamanı (epoch ms); hiç çalışmadıysa null.
 */
@Entity(tableName = "index_state")
data class IndexState(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val total: Int,
    val processed: Int,
    val lastRunAt: Long?,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

/** Arama için fotoğraf bilgisi ile vektörün birleşik satırı ([PhotoEmbeddingDao.getAllForModel]). */
data class IndexedVector(
    val photoId: Long,
    val uri: String,
    val dateTaken: Long?,
    val vector: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is IndexedVector &&
            photoId == other.photoId &&
            uri == other.uri &&
            dateTaken == other.dateTaken &&
            vector.contentEquals(other.vector))

    override fun hashCode(): Int =
        31 * (31 * (31 * photoId.hashCode() + uri.hashCode()) + (dateTaken?.hashCode() ?: 0)) +
            vector.contentHashCode()
}
