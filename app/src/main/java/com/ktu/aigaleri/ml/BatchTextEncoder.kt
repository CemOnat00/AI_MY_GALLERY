package com.ktu.aigaleri.ml

/**
 * Dar, ml-içi toplu kodlama arayüzü (T-033). [com.ktu.aigaleri.domain.TextEncoder] sözleşmesi DEĞİŞMEZ; bu arayüz yalnızca
 * [PromptEnsembleTextEncoder] gibi aynı oturumda ardışık çok çıkarım isteyenler içindir.
 *
 * Neden: her `encode` çağrısı tek ONNX oturum kilidini ([SingleSessionSlot]) ayrı alır. İndeksleme sürerken kilit FIFO
 * olduğundan iki çağrı arasına görüntü oturumu girip ~97 MB metin oturumunu kapatabilir ve sorgu başına birden fazla
 * oturum açılışı oluşur. [encodeAll] tüm sorguları TEK kilit altında çalıştırır.
 *
 * Sözleşme: önce TÜM sorgular doğrulanır (biri geçersizse [com.ktu.aigaleri.domain.InvalidQueryException], hiçbir çıkarım ve
 * oturum açılışı yapılmaz); sonuç girdiyle aynı sırada ve boyutta, her biri `embeddingSpec.dimension` uzunluğunda L2 normalize
 * vektör; iptal her çıkarım arasında denetlenir (`CancellationException`, kısmi sonuç dönmez); boş liste boş liste döner.
 */
interface BatchTextEncoder {
    suspend fun encodeAll(queries: List<String>): List<FloatArray>
}
