package com.ktu.aigaleri.domain

import kotlinx.coroutines.flow.Flow

/**
 * Galerideki fotoğrafları önceden analiz edip indekse yazar (architecture.md Bölüm 1, 2).
 * Arama sırasında çağrılmaz.
 *
 * Kullanım: [index] yalnızca WorkManager worker içinde toplanır. UI indeks durumunu bu akıştan
 * değil, Room `IndexState` (DAO Flow) üzerinden okur. Her fotoğrafta bir değer yayıldığı için
 * tüketiciler (ör. worker'ın bildirim/ilerleme güncellemesi) `conflate()` veya `sample` kullanmalıdır.
 *
 * Sözleşme:
 * - [index] soğuk (cold) bir Flow döndürür; toplanmaya başlayınca iş başlar, toplama iptal
 *   edilince iş durur. Kesintiye kadar yazılan kayıtlar korunur (bkz. [IndexMode]).
 * - Akış sırası: bir [IndexPhase.SCANNING], ardından her fotoğraf için bir [IndexPhase.INDEXING],
 *   en sonda bir [IndexPhase.COMPLETED] (processed == total). İşlenecek fotoğraf yoksa yalnızca
 *   SCANNING ve COMPLETED yayılır.
 * - Tek fotoğrafta hata olursa fotoğraf atlanır, [IndexProgress.failed] artar, iş devam eder
 *   (architecture.md Bölüm 9). Başarısız fotoğraflar kalıcı işaretlenmez; her
 *   [IndexMode.INCREMENTAL] çalıştırmada yeniden denenir ve `failed` yalnızca o çalıştırmayı sayar.
 *   T-006 bu davranışı uygular; `IndexState` başarısız sayısı tutmaz.
 * - Fotoğraftan bağımsız hatalarda akış [IndexException] alt tipleriyle (en az
 *   [IndexException.PermissionMissing] ve [IndexException.Unexpected]) exception ile biter.
 * - Ağır iş çağıranın thread'inde yapılmaz; implementasyon uygun dispatcher'a geçer.
 * - Fotoğraf yolu, içerik veya istem log'a yazılmaz.
 * - Hiçbir model tipi (tensor, oturum vb.) arayüzden sızmaz.
 */
interface PhotoIndexer {
    /** Kullanılan gömme modelinin boyut/sürüm bilgisi. */
    val embeddingSpec: EmbeddingSpec

    fun index(mode: IndexMode = IndexMode.INCREMENTAL): Flow<IndexProgress>
}
