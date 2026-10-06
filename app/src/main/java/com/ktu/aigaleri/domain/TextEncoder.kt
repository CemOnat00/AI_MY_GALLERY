package com.ktu.aigaleri.domain

/**
 * Türkçe istemi, fotoğraf vektörleriyle aynı uzayda bir vektöre çevirir (T-007). Arama sırasında
 * çalışan tek ML bileşenidir; fotoğraf analizi yapmaz.
 *
 * Sözleşme:
 * - [embeddingSpec], indeksleyicinin ([PhotoIndexer.embeddingSpec]) spec'iyle aynı olmak zorundadır;
 *   aksi halde istem ve fotoğraf vektörleri aynı uzayda olmaz.
 * - [encode] doğrulanmış/normalleştirilmiş sorgu için `embeddingSpec.dimension` uzunluğunda,
 *   L2 normu 1 olan vektör döner.
 * - Geçersiz sorguda ([InvalidQueryException]) model çalıştırılmaz.
 * - Suspend; ağır iş çağıranın thread'inde yapılmaz. İptalde `CancellationException`.
 * - İstem ve vektör log'a yazılmaz.
 */
interface TextEncoder {
    val embeddingSpec: EmbeddingSpec

    suspend fun encode(query: String): FloatArray
}

/**
 * Sorgu doğrulamasında reddedilen girdi (architecture.md Bölüm 8). Mesaj istemin kendisini içermez.
 * Programlama hatası değil, kullanıcı girdisi hatasıdır; arayüz katmanı yakalayıp uyarı gösterir.
 */
class InvalidQueryException(val reason: Reason) :
    IllegalArgumentException("Geçersiz sorgu: ${reason.name}") {
    enum class Reason {
        /** Temizlendikten sonra boş (yalnızca boşluk/kontrol karakteri dahil). */
        EMPTY,

        /** Ham girdi veya normalleştirilmiş sorgu uzunluk sınırını aşıyor. */
        TOO_LONG,
    }
}
