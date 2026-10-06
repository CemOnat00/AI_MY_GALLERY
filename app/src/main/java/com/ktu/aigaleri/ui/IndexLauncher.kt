package com.ktu.aigaleri.ui

import com.ktu.aigaleri.domain.IndexMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

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

    fun launch(mode: IndexMode)
}
