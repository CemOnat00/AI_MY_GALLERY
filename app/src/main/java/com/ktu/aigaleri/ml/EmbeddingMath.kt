package com.ktu.aigaleri.ml

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Metin gömme son işlemleri (sentence-transformers: Pooling(mean) -> Dense(768->512, bias yok) ->
 * L2 normalizasyon uygulama tarafında). Saf Kotlin; toplamlar double ile biriktirilir.
 */
object EmbeddingMath {
    /**
     * Attention mask'li mean pooling. [hidden] satır sıralı `[seqLen][hiddenSize]`; maske 0 olan
     * konumlar katılmaz. Maske toplamı 0 ise sıfır vektör döner (sentence-transformers gibi 1e-9 alt sınırı).
     */
    fun meanPool(hidden: FloatArray, attentionMask: LongArray, seqLen: Int, hiddenSize: Int): FloatArray {
        require(seqLen > 0 && hiddenSize > 0) { "seqLen ve hiddenSize > 0 olmalı" }
        require(hidden.size == seqLen * hiddenSize) { "hidden boyutu seqLen*hiddenSize olmalı" }
        require(attentionMask.size == seqLen) { "maske uzunluğu seqLen olmalı" }
        val acc = DoubleArray(hiddenSize)
        var count = 0.0
        for (t in 0 until seqLen) {
            if (attentionMask[t] == 0L) continue
            count += 1.0
            val base = t * hiddenSize
            for (j in 0 until hiddenSize) acc[j] += hidden[base + j].toDouble()
        }
        val denom = maxOf(count, 1e-9)
        return FloatArray(hiddenSize) { (acc[it] / denom).toFloat() }
    }

    /** y = W x, W satır sıralı `[outDim][inDim]` (bias yok). */
    fun denseNoBias(weights: FloatArray, input: FloatArray, outDim: Int, inDim: Int): FloatArray {
        require(weights.size == outDim * inDim) { "ağırlık boyutu outDim*inDim olmalı" }
        require(input.size == inDim) { "girdi boyutu inDim olmalı" }
        return FloatArray(outDim) { i ->
            var s = 0.0
            val base = i * inDim
            for (j in 0 until inDim) s += weights[base + j].toDouble() * input[j].toDouble()
            s.toFloat()
        }
    }

    /** Birim uzunluğa getirir (yeni dizi). Norm 0 veya sonlu değilse [IllegalStateException]. */
    fun l2Normalize(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x.toDouble() * x.toDouble()
        val norm = sqrt(s)
        check(norm > 0.0 && norm.isFinite()) { "L2 normu sıfır veya sonlu değil" }
        return FloatArray(v.size) { (v[it] / norm).toFloat() }
    }

    /** Ham float32 little-endian baytlarını okur; boyut tam [expectedCount]*4 olmalı. */
    fun readFloat32LittleEndian(bytes: ByteArray, expectedCount: Int): FloatArray {
        require(bytes.size == expectedCount * 4) { "beklenmeyen bayt sayısı" }
        val out = FloatArray(expectedCount)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }
}
