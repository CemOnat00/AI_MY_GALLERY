package com.ktu.aigaleri.domain

/** Varsayılan en fazla sonuç sayısı. */
const val DEFAULT_SEARCH_LIMIT = 50

/**
 * Türkçe istemi önceden hesaplanmış indeksle eşleştirir (architecture.md Bölüm 1, 2).
 * Arama sırasında fotoğraf analizi yapılmaz; yalnızca indeks okunur.
 *
 * Sözleşme:
 * - Arama yalnızca güncel [EmbeddingSpec.modelVersion] ile yazılmış kayıtları tarar; eski sürüm
 *   kayıtları aramaya girmez. Sorgu kodlayıcı (T-007) indeksleyiciyle aynı [EmbeddingSpec]'i
 *   kullanmak zorundadır.
 * - Sonuçlar [SearchResult.score] azalan sırada döner; eşit skorda [SearchResult.photoId]
 *   artan sıra kullanılır (deterministik).
 * - En fazla [limit] sonuç döner; indeks boşsa boş liste (hata değil). Asgari skor eşiği
 *   davranışı T-008'de belirlenecek; o zamana kadar eşik yoktur.
 * - [limit] <= 0 ise [IllegalArgumentException] fırlatılır.
 * - Sorgu metninin uzunluk/karakter doğrulaması bu arayüzün dışındadır (T-007, çağıran taraf).
 * - İstem ve sonuçlar log'a yazılmaz.
 * - Suspend; ağır iş çağıranın thread'inde yapılmaz. İptal edilebilir: iptalde
 *   `CancellationException` fırlar ve sonuç dönmez.
 */
interface SearchRepository {
    suspend fun search(query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<SearchResult>
}
