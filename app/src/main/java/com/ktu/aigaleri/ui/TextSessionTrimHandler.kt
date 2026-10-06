package com.ktu.aigaleri.ui

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Arka plana alınınca veya bellek baskısında metin ONNX oturumunu (~135 MB + sözlük) bırakır; böylece süreç arka
 * planda büyük model belleği tutmaz. Sonraki arama modeli yeniden açar (gecikme ölçülmedi). Uygulama bağlamına
 * `registerComponentCallbacks` ile `AppDependencies` içinde kaydedilir (yeni bağımlılık yok).
 *
 * `onTrimMemory` ana thread'de çağrılır; bırakma [scope]'ta başlatılır, ana thread bloklanmaz. Görüntü oturumuna
 * (indeksleme) dokunulmaz: yalnızca metin anahtarı kapatılır. Hatalar yutulur, log yazılmaz.
 *
 * Eşik: [ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW] (10) ve üstü; yani ön planda baskı (düşük/kritik), arayüz
 * gizlendi (20, arka plana alma) ve arka plan kademeleri. Daha hafif baskı ([TRIM_MEMORY_RUNNING_MODERATE]) yok sayılır.
 *
 * @param release metin oturumunu bırakır (`OnnxTextEncoder.release`).
 * @param afterRelease bırakma sonrası (ör. ısınma durumunu sıfırlama).
 */
class TextSessionTrimHandler(
    private val scope: CoroutineScope,
    private val release: suspend () -> Unit,
    private val afterRelease: () -> Unit = {},
) : ComponentCallbacks2 {
    override fun onTrimMemory(level: Int) {
        if (shouldRelease(level)) launchRelease()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onLowMemory() = launchRelease()

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    private fun launchRelease() {
        scope.launch {
            try {
                release()
                afterRelease()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // En iyi çaba: ayrıntı loglanmaz.
            }
        }
    }

    companion object {
        fun shouldRelease(level: Int): Boolean = level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
    }
}
