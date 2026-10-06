package com.ktu.aigaleri.ml

/** Token kimliklerinden `last_hidden_state` üretir: satır sıralı `[seqLen][HIDDEN_SIZE]`. */
fun interface HiddenStateRunner {
    fun run(inputIds: LongArray, attentionMask: LongArray): FloatArray
}

/**
 * Normalleştirilmiş sorgudan 512 boyutlu birim vektör: WordPiece -> model -> mean pooling -> Dense -> L2.
 * Model çalıştırma [HiddenStateRunner] arkasındadır (testte sahte, uygulamada ONNX Runtime).
 * İş parçacığı güvenliği runner'a bağlıdır; çağıran serileştirir ([SingleSessionSlot]).
 */
class TextEmbeddingPipeline(
    private val tokenizer: WordPieceTokenizer,
    private val denseWeights: FloatArray,
    private val runner: HiddenStateRunner,
) {
    init {
        require(denseWeights.size == OUTPUT_DIM * HIDDEN_SIZE) { "Dense ağırlığı 512x768 olmalı" }
    }

    fun embed(normalizedQuery: String): FloatArray {
        val ids = tokenizer.encode(normalizedQuery)
        val inputIds = LongArray(ids.size) { ids[it].toLong() }
        val mask = LongArray(ids.size) { 1L }
        val hidden = runner.run(inputIds, mask)
        check(hidden.size == ids.size * HIDDEN_SIZE) { "model çıktı boyutu beklenmeyen" }
        val pooled = EmbeddingMath.meanPool(hidden, mask, ids.size, HIDDEN_SIZE)
        val projected = EmbeddingMath.denseNoBias(denseWeights, pooled, OUTPUT_DIM, HIDDEN_SIZE)
        return EmbeddingMath.l2Normalize(projected)
    }

    companion object {
        const val HIDDEN_SIZE = 768
        const val OUTPUT_DIM = 512
    }
}
