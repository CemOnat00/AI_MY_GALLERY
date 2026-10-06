package com.ktu.aigaleri.domain

/** İndeksleme çalıştırma biçimi. */
enum class IndexMode {
    /**
     * Yalnızca eksik kayıtları, güncel [EmbeddingSpec.modelVersion] ile yazılmamış kayıtları,
     * önceki başarısız denemeleri ve yarım kalmış bir [FULL] çalıştırmanın kalanını işler.
     */
    INCREMENTAL,

    /**
     * Tüm galeriyi yeniden işler ("yeniden indeksleme"). Önce silmez: kayıtlar fotoğraf bazında
     * yerine yazılır. İptal veya hata halinde henüz yeniden yazılmamış eski kayıtlar korunur ve
     * aramada kullanılmaya devam eder; iptal edilen FULL'dan sonra [INCREMENTAL] kalanı tamamlar. FULL tamamlanana
     * dek aynı hedefle devam eder: yarım kalmış bir FULL yeniden istenirse baştan başlamaz, kalanı işler; yalnızca
     * tamamlanmış (işlenecek kalan olmayan) bir indeksten sonra istenen FULL yeni bir tur başlatır.
     */
    FULL,
}

/** İndeksleme aşaması. */
enum class IndexPhase {
    /** Galeri taranıyor; işlenecek fotoğraf sayısı henüz kesin değil ([IndexProgress.total] = 0). */
    SCANNING,

    /** Fotoğraflar analiz edilip indekse yazılıyor. */
    INDEXING,

    /** İş bitti; [IndexProgress.processed] == [IndexProgress.total]. */
    COMPLETED,
}

/**
 * [PhotoIndexer.index] akışının yaydığı ilerleme anlık görüntüsü.
 *
 * Değişmezler ihlal edilirse [IllegalArgumentException] fırlatılır; bu bir programlama hatasıdır
 * (implementasyon yanlış sayı üretmiştir), çalışma zamanı hata durumu değildir ve
 * [IndexException] ile karıştırılmamalıdır.
 *
 * @property total bu çalıştırmada işlenecek fotoğraf sayısı.
 * @property processed ele alınan fotoğraf sayısı (başarısızlar dahil).
 * @property failed [processed] içinde atlanan (hata veren) fotoğraf sayısı; yalnızca bu
 *   çalıştırmayı sayar, kalıcı değildir.
 */
data class IndexProgress(
    val phase: IndexPhase,
    val total: Int,
    val processed: Int,
    val failed: Int = 0,
) {
    init {
        require(total >= 0) { "total >= 0 olmalı" }
        require(processed in 0..total) { "processed 0..total aralığında olmalı" }
        require(failed in 0..processed) { "failed 0..processed aralığında olmalı" }
        require(phase != IndexPhase.COMPLETED || processed == total) {
            "COMPLETED aşamasında processed == total olmalı"
        }
    }

    /** 0f..1f ilerleme oranı; total 0 ise tamamlandıysa 1f, değilse 0f. */
    val fraction: Float
        get() = when {
            total > 0 -> processed.toFloat() / total
            phase == IndexPhase.COMPLETED -> 1f
            else -> 0f
        }
}
