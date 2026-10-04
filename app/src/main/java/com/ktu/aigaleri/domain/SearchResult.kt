package com.ktu.aigaleri.domain

/**
 * Tek bir arama sonucu. Android veya model tipi içermez; [uri] MediaStore URI'sinin metin halidir.
 *
 * @property photoId MediaStore kimliği (indeksteki fotoğrafı tekil belirler).
 * @property uri fotoğrafın MediaStore URI'si (string).
 * @property score benzerlik skoru; büyük olan daha benzer. Cosine benzerliği için [-1, 1] aralığı.
 * @property dateTaken çekim zamanı (epoch ms); bilinmiyorsa null.
 */
data class SearchResult(
    val photoId: Long,
    val uri: String,
    val score: Float,
    val dateTaken: Long? = null,
) {
    init {
        require(uri.isNotBlank()) { "uri boş olamaz" }
        require(!score.isNaN()) { "score NaN olamaz" }
    }
}
