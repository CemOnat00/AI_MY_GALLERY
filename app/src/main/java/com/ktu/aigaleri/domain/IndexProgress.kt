package com.ktu.aigaleri.domain

/** İndeksleme çalıştırma biçimi. */
enum class IndexMode {
    /** Yalnızca indekslenmemiş (veya model sürümü eskimiş) fotoğrafları işler. */
    INCREMENTAL,

    /** Mevcut indeksi geçersiz sayıp tüm galeriyi yeniden işler ("yeniden indeksleme"). */
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
 * @property total bu çalıştırmada işlenecek fotoğraf sayısı.
 * @property processed ele alınan fotoğraf sayısı (başarısızlar dahil).
 * @property failed [processed] içinde atlanan (hata veren) fotoğraf sayısı.
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
