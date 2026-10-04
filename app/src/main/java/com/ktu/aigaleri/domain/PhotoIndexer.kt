package com.ktu.aigaleri.domain

import kotlinx.coroutines.flow.Flow

/**
 * Galerideki fotoğrafları önceden analiz edip indekse yazar (architecture.md Bölüm 1, 2).
 * Arama sırasında çağrılmaz; yalnızca arka plan işi (WorkManager) tarafından kullanılır.
 *
 * Sözleşme:
 * - [index] soğuk (cold) bir Flow döndürür; toplanmaya başlayınca iş başlar, toplama iptal
 *   edilince iş durur. Kesintiye kadar yazılan kayıtlar korunur, sonraki [IndexMode.INCREMENTAL]
 *   çalıştırma kaldığı yerden devam eder.
 * - Akış sırası: bir [IndexPhase.SCANNING], ardından her fotoğraf için bir [IndexPhase.INDEXING],
 *   en sonda bir [IndexPhase.COMPLETED] (processed == total). Fotoğraf yoksa yalnızca
 *   SCANNING ve COMPLETED yayılır.
 * - Tek fotoğrafta hata olursa fotoğraf atlanır, [IndexProgress.failed] artar, iş devam eder
 *   (architecture.md Bölüm 9). Akış yalnızca fotoğraftan bağımsız (ör. izin yok, veritabanı)
 *   hatalarda exception ile biter.
 * - Ağır iş çağıranın thread'inde yapılmaz; implementasyon uygun dispatcher'a geçer.
 * - Fotoğraf yolu, içerik veya istem log'a yazılmaz.
 * - Hiçbir model tipi (tensor, oturum vb.) arayüzden sızmaz.
 */
interface PhotoIndexer {
    /** Kullanılan gömme modelinin boyut/sürüm bilgisi. */
    val embeddingSpec: EmbeddingSpec

    fun index(mode: IndexMode = IndexMode.INCREMENTAL): Flow<IndexProgress>
}
