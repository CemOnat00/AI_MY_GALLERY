package com.ktu.aigaleri.domain

/** Varsayılan en fazla sonuç sayısı. */
const val DEFAULT_SEARCH_LIMIT = 50

/**
 * Türkçe istemi önceden hesaplanmış indeksle eşleştirir (architecture.md Bölüm 1, 2).
 * Arama sırasında fotoğraf analizi yapılmaz; yalnızca indeks okunur.
 *
 * Sözleşme:
 * - Sonuçlar [SearchResult.score] azalan sırada döner; eşit skorda [SearchResult.photoId]
 *   artan sıra kullanılır (deterministik).
 * - En fazla [limit] sonuç döner; indeks boşsa veya eşleşme yoksa boş liste (hata değil).
 * - [limit] <= 0 ise [IllegalArgumentException] fırlatılır.
 * - Sorgu metninin uzunluk/karakter doğrulaması bu arayüzün dışındadır (T-007, çağıran taraf).
 * - İstem ve sonuçlar log'a yazılmaz.
 * - Suspend; ağır iş çağıranın thread'inde yapılmaz. İptal edilebilir.
 */
interface SearchRepository {
    suspend fun search(query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<SearchResult>
}
