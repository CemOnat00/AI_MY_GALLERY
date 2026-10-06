package com.ktu.aigaleri.ui.stub

import com.ktu.aigaleri.domain.IndexMode
import com.ktu.aigaleri.ui.IndexLauncher

/**
 * Yalnızca UI testleri/önizleme için: hiçbir şey yapmaz ve "yeniden indeksle" devre dışı görünür
 * ([isAvailable] = false). T-006'dan beri uygulama [com.ktu.aigaleri.ui.AppDependencies] içinde gerçek
 * `WorkManagerIndexLauncher` kullanır.
 */
class StubIndexLauncher : IndexLauncher {
    override val isAvailable: Boolean = false

    override fun launch(mode: IndexMode) = Unit
}
