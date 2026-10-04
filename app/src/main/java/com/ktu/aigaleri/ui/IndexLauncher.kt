package com.ktu.aigaleri.ui

import com.ktu.aigaleri.domain.IndexMode

/**
 * İndeksleme işini başlatma çağrı noktası (UI -> iş hattı). Gerçek implementasyon T-006'da
 * WorkManager ile yazılacak; UI yalnızca bu arayüzü bilir. Çağrı hemen döner (iş arka planda).
 */
fun interface IndexLauncher {
    fun launch(mode: IndexMode)
}
