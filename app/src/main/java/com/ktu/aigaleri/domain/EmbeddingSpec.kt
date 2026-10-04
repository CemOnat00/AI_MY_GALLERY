package com.ktu.aigaleri.domain

/**
 * Gömme (embedding) vektörünün model bağımsız tanımı. Boyut ve sürüm model seçimine (T-004)
 * bağlıdır; domain katmanı bunları sabit varsaymaz, ml katmanı bu nesneyi sağlar.
 *
 * @property dimension vektör uzunluğu (> 0).
 * @property modelVersion indekse yazılan modelin sürüm etiketi; değişirse eski indeks geçersizdir.
 */
data class EmbeddingSpec(val dimension: Int, val modelVersion: String) {
    init {
        require(dimension > 0) { "dimension > 0 olmalı" }
        require(modelVersion.isNotBlank()) { "modelVersion boş olamaz" }
    }
}
