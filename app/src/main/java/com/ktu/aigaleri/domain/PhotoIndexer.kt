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
 *   [IndexException.PermissionMissing] ve [IndexException.Unexpected]) exception ile biter. Fotoğraf okurken
 *   `SecurityException` (izin geri alındı) tek fotoğraf hatası değil, [IndexException.PermissionMissing]'dir.
 *   Model kaynaklı hatalar (ONNX çalıştırma/çıktı) tek fotoğrafta atlanır, ancak bu çalıştırmada hiç başarı yokken
 *   ardışık hata eşiği (T-006: 20) aşılırsa akış [IndexException.Unexpected] ile kesilir (bozuk model tüm galeriyi
 *   sessizce tamamlamasın); fotoğrafa özgü çözme hataları eşiğe sayılmaz. İlk başarıdan sonra bozulan model bu eşikle
 *   yakalanmaz. Eşiği aşan fotoğraf `processed` sayımına girmez. `OutOfMemoryError` gibi `Error`'lar yakalanmaz:
 *   yayılır ve çalıştırma durur.
 * - FULL kalıcı tur durumu taşır (Room `IndexState.fullTargetVersion`): yarım kalmış FULL, hiç yazmadan kesilse de
 *   yeniden istenince baştan başlamaz, aynı hedefle kalanı işler; FULL çalıştırması (başarısız fotoğraflarla bile)
 *   bitince tur kapanır ve sonraki FULL yeni bir tur açar. INCREMENTAL turu kapatmaz ama kalanı tamamlar.
 * - Boş MediaStore istisnası: MediaStore hiç fotoğraf döndürmez (izin hatası atılmadan; ör. Android 14 kısmi erişim,
 *   geçici sağlayıcı hatası) ve indeks doluysa hiçbir kayıt silinmez ve hiçbir şey işlenmez (akış SCANNING,
 *   COMPLETED(0, 0) ile biter, `lastRunAt` güncellenir). Gerçekten boşalan bir galerinin eski kayıtları bu yüzden
 *   temizlenmez; galeri yeniden dolduğunda bir sonraki taramada temizlenir.
 * - Ağır iş çağıranın thread'inde yapılmaz; implementasyon uygun dispatcher'a geçer.
 * - Fotoğraf yolu, içerik veya istem log'a yazılmaz.
 * - Hiçbir model tipi (tensor, oturum vb.) arayüzden sızmaz.
 */
interface PhotoIndexer {
    /** Kullanılan gömme modelinin boyut/sürüm bilgisi. */
    val embeddingSpec: EmbeddingSpec

    fun index(mode: IndexMode = IndexMode.INCREMENTAL): Flow<IndexProgress>
}
