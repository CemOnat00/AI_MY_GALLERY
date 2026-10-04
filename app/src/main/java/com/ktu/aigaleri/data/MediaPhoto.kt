package com.ktu.aigaleri.data

/**
 * MediaStore'dan okunan fotoğraf referansı (architecture.md Bölüm 5). Fotoğrafın içeriği veya dosya
 * yolu tutulmaz; yalnızca kimlik ve content URI.
 *
 * @property id MediaStore kimliği ([Photo.mediaStoreId]).
 * @property uri content URI'sinin metin hali.
 * @property dateTaken çekim zamanı (epoch ms); bilinmiyorsa null.
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
