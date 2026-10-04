package com.ktu.aigaleri.ui.stub

import com.ktu.aigaleri.domain.IndexMode
import com.ktu.aigaleri.ui.IndexLauncher

/**
 * GEÇİCİ stub: gerçek WorkManager hattı (T-006) gelene kadar hiçbir şey yapmaz ve UI'da
 * "yeniden indeksle" devre dışı görünür ([isAvailable] = false).
 * T-006'da [com.ktu.aigaleri.ui.AppDependencies] içinde gerçek implementasyonla değiştirilir.
 */
class StubIndexLauncher : IndexLauncher {
    override val isAvailable: Boolean = false

    override fun launch(mode: IndexMode) = Unit
}
