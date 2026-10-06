package com.ktu.aigaleri.domain

/**
 * Fotoğrafı, istem vektörleriyle ([TextEncoder]) aynı uzayda bir vektöre çevirir (T-006). Yalnızca
 * indeksleme sırasında çağrılır; arama sırasında kullanılmaz.
 *
 * Sözleşme:
 * - [embeddingSpec], [TextEncoder.embeddingSpec] ile aynı olmak zorundadır.
 * - [encode] `embeddingSpec.dimension` uzunluğunda, L2 normu 1 olan vektör döner (kosinüs = nokta çarpımı).
 * - Suspend; ağır iş çağıranın thread'inde yapılmaz. İptalde `CancellationException`.
 * - Tek fotoğrafa özgü hatalar (çözülemeyen/bozuk görüntü) istisna olarak yayılır; çağıran (indeksleyici)
 *   fotoğrafı atlar. Model/çalışma zamanı hataları ml katmanının `ModelException` tipleridir.
 * - Fotoğraf yolu, içerik ve vektör log'a yazılmaz.
 */
interface ImageEncoder {
    val embeddingSpec: EmbeddingSpec

    /** [uri]: MediaStore content URI'sinin metin hali. */
    suspend fun encode(uri: String): FloatArray

    /** Model oturumunu ve belleğini bırakır (indeksleme bitince); sonraki [encode] yeniden açar. */
    suspend fun release()
}
