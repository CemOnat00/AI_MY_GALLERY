package com.ktu.aigaleri.data

/**
 * MediaStore'dan okunan fotoğraf referansı (architecture.md Bölüm 5). Fotoğrafın içeriği veya dosya
 * yolu tutulmaz; yalnızca kimlik ve content URI.
 *
 * @property id MediaStore kimliği ([Photo.mediaStoreId]).
 * @property uri content URI'sinin metin hali.
 * @property dateTaken çekim zamanı (epoch ms); MediaStore'da yoksa eklenme zamanı (DATE_ADDED * 1000);
 *   ikisi de bilinmiyorsa null.
 */
data class MediaPhoto(
    val id: Long,
    val uri: String,
    val dateTaken: Long?,
) {
    /** İndeks kaydına çevirir; [indexedAt] ve [indexVersion] indeksleyici tarafından verilir. */
    fun toPhoto(indexedAt: Long, indexVersion: Int): Photo =
        Photo(mediaStoreId = id, uri = uri, dateTaken = dateTaken, indexedAt = indexedAt, indexVersion = indexVersion)
}

/** MediaStore satırından [MediaPhoto] üretiminin saf (Android'siz, JVM'de test edilebilir) mantığı. */
internal object MediaPhotoMapping {
    /**
     * Çekim zamanı (ms): [dateTakenMs] null veya 0 (<= 0) ise [dateAddedSec] * 1000 yedeği; o da
     * yoksa veya <= 0 ise null.
     */
    fun effectiveDate(dateTakenMs: Long?, dateAddedSec: Long?): Long? = when {
        dateTakenMs != null && dateTakenMs > 0 -> dateTakenMs
        dateAddedSec != null && dateAddedSec > 0 -> dateAddedSec * 1000
        else -> null
    }

    /** `ContentUris.withAppendedId` ile aynı biçim: `<koleksiyon>/<id>`. */
    fun uriFor(collectionUri: String, id: Long): String = "${collectionUri.trimEnd('/')}/$id"

    fun toMediaPhoto(collectionUri: String, id: Long, dateTakenMs: Long?, dateAddedSec: Long?): MediaPhoto =
        MediaPhoto(id, uriFor(collectionUri, id), effectiveDate(dateTakenMs, dateAddedSec))

    /** Yeniden eskiye; tarihi bilinmeyenler sonda, eşitlikte kimlik azalan. */
    fun sortNewestFirst(photos: List<MediaPhoto>): List<MediaPhoto> =
        photos.sortedWith(compareByDescending<MediaPhoto> { it.dateTaken ?: Long.MIN_VALUE }.thenByDescending { it.id })
}
