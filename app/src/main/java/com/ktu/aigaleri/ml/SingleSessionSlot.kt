package com.ktu.aigaleri.ml

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * "Tek oturum" kuralı (model-research.md 5.1): aynı anda en fazla bir ONNX oturumu açık kalır
 * (arama için metin, indeksleme için görüntü). Başka anahtarla istek gelirse önce açık olan kapatılır.
 * İş (blok) kilit altında çalışır; böylece oturum bir çıkarımın ortasında kapanmaz.
 * Oturum türünden bağımsızdır ([AutoCloseable]); ORT olmadan test edilebilir.
 */
class SingleSessionSlot {
    private val mutex = Mutex()
    private var currentKey: String? = null
    private var current: AutoCloseable? = null

    /** [key] oturumu açık değilse [open] ile açar (önceki farklı oturumu kapatarak) ve [block]'u çalıştırır. */
    suspend fun <S : AutoCloseable, R> withSession(key: String, open: () -> S, block: (S) -> R): R =
        mutex.withLock {
            if (currentKey != key) {
                closeLocked()
                current = open()
                currentKey = key
            }
            @Suppress("UNCHECKED_CAST")
            block(current as S)
        }

    /** [key] oturumu açıksa kapatır; ardından (her durumda, aynı kilit altında) [afterClose] çalışır. */
    suspend fun close(key: String, afterClose: () -> Unit = {}) = mutex.withLock {
        if (currentKey == key) closeLocked()
        afterClose()
    }

    suspend fun closeAll() = mutex.withLock { closeLocked() }

    private fun closeLocked() {
        val s = current
        current = null
        currentKey = null
        s?.close()
    }

    companion object {
        /** Uygulama genelinde paylaşılan yuva; T-006 görüntü kodlayıcısı da bunu kullanmalıdır. */
        val shared = SingleSessionSlot()
    }
}
