package com.ktu.aigaleri.domain

/**
 * Gömme (embedding) vektörünün model bağımsız tanımı. Boyut ve sürüm model seçimine (T-004)
 * bağlıdır; domain katmanı bunları sabit varsaymaz, ml katmanı bu nesneyi sağlar.
 *
 * İndeksleyici ve sorgu kodlayıcı (T-007) aynı [EmbeddingSpec]'i kullanmak zorundadır; aksi halde
 * fotoğraf ve istem vektörleri aynı uzayda olmaz.
 *
 * Geçersiz değerler programlama hatasıdır: [IllegalArgumentException] fırlatılır.
 *
 * @property dimension vektör uzunluğu (> 0).
 * @property modelVersion indekse yazılan modelin sürüm etiketi; değişirse eski sürüm kayıtları
 *   aramaya girmez ve [IndexMode.INCREMENTAL] onları yeniden işler.
 */
data class EmbeddingSpec(val dimension: Int, val modelVersion: String) {
    init {
        require(dimension > 0) { "dimension > 0 olmalı" }
        require(modelVersion.isNotBlank()) { "modelVersion boş olamaz" }
    }
}
