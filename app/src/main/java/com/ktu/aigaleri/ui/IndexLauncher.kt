package com.ktu.aigaleri.ui

import com.ktu.aigaleri.domain.IndexMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * İndeksleme işinin ince durumu (T-016). [IDLE]: kuyrukta iş yok; [WAITING]: iş kuyrukta ama çalışmıyor (ENQUEUED/
 * BLOCKED; ör. pil düşük kısıtı, yeniden deneme bekleme süresi); [RUNNING]: iş şu an çalışıyor. Arayüz "beklemede" ile
 * "çalışıyor"u buradan ayırt edebilir.
 */
enum class IndexRunState { IDLE, WAITING, RUNNING }

/**
 * İndeksleme işini başlatma çağrı noktası (UI -> iş hattı). Gerçek implementasyon WorkManager tabanlıdır
 * (`work.WorkManagerIndexLauncher`, T-006); UI yalnızca bu arayüzü bilir. Çağrı hemen döner (iş arka planda).
 */
interface IndexLauncher {
    /** İş hattı gerçekten bağlı mı; false ise UI "yeniden indeksle"yi devre dışı bırakıp açıklar. */
    val isAvailable: Boolean get() = true

    /**
     * İndeksleme işi kuyrukta veya çalışıyor mu (iş hattı durumundan türetilir). Bağlı değilse hep false.
     * UI bu değer true iken yeni "yeniden indeksle" isteği göndermez: çalışan (INCREMENTAL dahil) iş sürerken FULL
     * arayüzden başlatılamaz; gerçek launcher'ın FULL için REPLACE politikasına yalnızca arayüz dışı çağrılarla ulaşılır.
     */
    val isRunning: Flow<Boolean> get() = flowOf(false)

    /**
     * [isRunning]'in ince hali (T-016, geriye uyumlu ek): [IndexRunState.WAITING] ile [IndexRunState.RUNNING] ayrılır.
     * Varsayılan [isRunning]'den türetilir (true -> RUNNING, muhafazakâr), yalnızca [isRunning]'i geçersiz kılan
     * launcher'lar/sahteler bozulmaz. Tutarlılık: `isRunning == (runState != IDLE)`. Isınma yalnızca RUNNING iken ertelenir.
     */
    val runState: Flow<IndexRunState> get() = isRunning.map { if (it) IndexRunState.RUNNING else IndexRunState.IDLE }

    fun launch(mode: IndexMode)
}
