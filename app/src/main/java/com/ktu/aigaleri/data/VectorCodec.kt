package com.ktu.aigaleri.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Float vektör <-> BLOB dönüşümü (little-endian, vektör başına 4 bayt/eleman). Boyut sabit değildir;
 * BLOB uzunluğundan çıkarılır. Vektörün değerini hesaplamak ml katmanının işidir.
 */
object VectorCodec {
    fun encode(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(vector)
        return buffer.array()
    }

    /**
     * [blob]'u yeni dizi ayırmadan [out]'a çözer (arama taramasında satır başına tahsisi önler; [out] yeniden
     * kullanılır). [blob] uzunluğu tam `out.size * 4` olmalıdır, aksi halde [IllegalArgumentException].
     */
    fun decodeInto(blob: ByteArray, out: FloatArray) {
        require(blob.size == out.size * Float.SIZE_BYTES) { "BLOB uzunluğu out.size*4 olmalı" }
        // Toplu çözme: bayt bayt birleştirmenin yerine LITTLE_ENDIAN FloatBuffer.get (bit desenleri, NaN dahil, aynı).
        ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
    }

    fun decode(blob: ByteArray): FloatArray {
        require(blob.size % Float.SIZE_BYTES == 0) { "BLOB uzunluğu 4'ün katı olmalı" }
        val out = FloatArray(blob.size / Float.SIZE_BYTES)
        ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }
}
